package com.labteto.dshmobile.local.model

import com.labteto.dshmobile.local.io.NetworkInputTooLargeException
import com.labteto.dshmobile.local.io.readBoundedLine

import com.labteto.dshmobile.local.LocalModelException
import com.labteto.dshmobile.local.TokenPromptBreakdown
import com.labteto.dshmobile.local.estimatePromptBreakdown
import java.io.IOException
import java.net.SocketTimeoutException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody

@Singleton
internal class AnthropicMessagesClient @Inject constructor(
    http: OkHttpClient,
    private val json: Json,
) {
    private val client = http.newBuilder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    suspend fun complete(
        route: LocalResolvedModelRoute,
        messages: List<LocalCanonicalMessage>,
        tools: List<LocalCanonicalToolDefinition>,
        temperature: Double?,
        streaming: Boolean,
        onDelta: (LocalModelDelta) -> Unit = {},
    ): LocalModelReply = withContext(Dispatchers.IO) {
        require(route.bearerToken.isNotBlank()) { "当前模型 API Key 为空" }
        require(route.model.isNotBlank()) { "当前模型名称为空" }
        val legacyMessages = LocalCanonicalModelCodec.toLegacyMessages(
            messages,
            LocalModelAdapterIds.ANTHROPIC_MESSAGES,
            route.fingerprint,
        )
        val legacyTools = LocalCanonicalModelCodec.toLegacyTools(tools)
        val promptBreakdown = estimatePromptBreakdown(legacyMessages, legacyTools)
        val payload = buildPayload(
            route = route,
            messages = messages,
            tools = tools,
            temperature = temperature,
        )
        val wirePayload = payload.toString().toByteArray(Charsets.UTF_8)
        if (wirePayload.size > 32_000_000) {
            throw LocalModelException("MODEL_REQUEST_TOO_LARGE", "Anthropic 请求超过 32 MB，请减少图片或历史内容", false)
        }
        val admissionTracker = LocalModelAdmissionTracker()
        val request = Request.Builder()
            .url(endpoint(route.baseUrl))
            .header("x-api-key", route.bearerToken)
            .header("anthropic-version", ANTHROPIC_VERSION)
            .header("accept", "text/event-stream")
            .header("content-type", "application/json")
            .post(wirePayload.toRequestBody(JSON_MEDIA).withModelAdmissionTracking(admissionTracker))
            .build()
        var admittedRequestId: String? = null
        try {
            withCancellableModelResponse(client.newCall(request), admissionTracker) { response ->
                val requestId = response.header("request-id")
                    ?: response.header("anthropic-request-id")
                val retryAfterMs = parseRetryAfterMillis(response.header("Retry-After"))
                if (!response.isSuccessful) {
                    val body = response.readBoundedModelError(ERROR_BODY_LIMIT)
                    throw httpFailure(response.code, body, requestId, retryAfterMs)
                }
                admissionTracker.markAdmitted()
                admittedRequestId = requestId
                val body = response.body ?: throw modelPostAdmissionFailure(
                    code = "ANTHROPIC_STREAM_INTERRUPTED_AFTER_ADMISSION",
                    detail = "Anthropic Messages 返回了空响应",
                    requestId = requestId,
                )
                parseStream(
                    body = body,
                    route = route,
                    promptBreakdown = promptBreakdown,
                    requestId = requestId,
                    retryAfterMs = retryAfterMs,
                    onDelta = if (streaming) onDelta else { _: LocalModelDelta -> },
                )
            }
        } catch (error: LocalModelException) {
            if (admissionTracker.snapshot() == LocalModelAdmissionState.ADMITTED && error.retryable) {
                throw error.withoutReplayAfterAdmission()
            }
            throw error
        } catch (error: SocketTimeoutException) {
            if (admissionTracker.snapshot() == LocalModelAdmissionState.ADMITTED) {
                throw modelPostAdmissionFailure(
                    code = "ANTHROPIC_STREAM_INTERRUPTED_AFTER_ADMISSION",
                    detail = "Anthropic 模型流式响应超时",
                    requestId = admittedRequestId,
                    cause = error,
                )
            }
            throw modelTransportFailure(
                code = "MODEL_TIMEOUT",
                detail = "Anthropic 模型请求超时：${error.message ?: "请求未在时限内完成"}",
                tracker = admissionTracker,
                requestId = admittedRequestId,
                cause = error,
            )
        } catch (error: IOException) {
            if (admissionTracker.snapshot() == LocalModelAdmissionState.ADMITTED) {
                throw modelPostAdmissionFailure(
                    code = "ANTHROPIC_STREAM_INTERRUPTED_AFTER_ADMISSION",
                    detail = "Anthropic 模型流式连接中断",
                    requestId = admittedRequestId,
                    cause = error,
                )
            }
            throw modelTransportFailure(
                code = "MODEL_NETWORK",
                detail = "Anthropic 模型网络请求失败：${error.message ?: "网络异常"}",
                tracker = admissionTracker,
                requestId = admittedRequestId,
                cause = error,
            )
        }
    }

    internal fun buildPayload(
        route: LocalResolvedModelRoute,
        messages: List<LocalCanonicalMessage>,
        tools: List<LocalCanonicalToolDefinition>,
        temperature: Double?,
    ): JsonObject {
        val system = messages
            .filter { it.role == LocalCanonicalRole.SYSTEM || it.role == LocalCanonicalRole.DEVELOPER }
            .flatMap { it.content }
            .filterIsInstance<LocalCanonicalContent.Text>()
            .joinToString("\n\n") { it.text }
        return buildJsonObject {
            put("model", route.model)
            put("max_tokens", DEFAULT_MAX_TOKENS)
            put("stream", true)
            if (system.isNotBlank()) put("system", system)
            put("messages", mergeAdjacentTurns(buildJsonArray {
                messages.forEach { message ->
                    if (message.role == LocalCanonicalRole.SYSTEM || message.role == LocalCanonicalRole.DEVELOPER) {
                        return@forEach
                    }
                    val converted = when (message.role) {
                        LocalCanonicalRole.ASSISTANT -> assistantContent(message, route)
                        LocalCanonicalRole.TOOL -> toolResultContent(message)
                        else -> userContent(message)
                    }
                    if (converted.isEmpty()) return@forEach
                    add(buildJsonObject {
                        put(
                            "role",
                            if (message.role == LocalCanonicalRole.ASSISTANT) "assistant" else "user",
                        )
                        put("content", converted)
                    })
                }
            }))
            if (tools.isNotEmpty()) put("tools", buildJsonArray {
                tools.forEach { tool ->
                    if (tool.parameters["type"]?.jsonPrimitive?.contentOrNull != "object") {
                        throw protocolError("工具 input_schema 根节点必须是 object", null)
                    }
                    add(buildJsonObject {
                        put("name", tool.name)
                        put("description", tool.description)
                        put("input_schema", tool.parameters)
                    })
                }
            })
            temperature?.takeIf { route.capabilities.temperature }?.let { put("temperature", it) }
        }
    }

    private fun assistantContent(
        message: LocalCanonicalMessage,
        route: LocalResolvedModelRoute,
    ): JsonArray {
        LocalCanonicalModelCodec.compatibleReplay(
            message,
            LocalModelAdapterIds.ANTHROPIC_MESSAGES,
            route.fingerprint,
        )?.payload?.get("content")?.let { replay ->
            if (replay is JsonArray) {
                val canonical = assistantContent(message.copy(replay = null), route)
                // Only signed reasoning is immutable; visible text and calls follow current history.
                return buildJsonArray {
                    replay.forEach { raw ->
                        val type = (raw as? JsonObject)?.get("type")?.jsonPrimitive?.contentOrNull
                        if (type in setOf("thinking", "redacted_thinking")) add(raw)
                    }
                    canonical.forEach(::add)
                }
            }
        }
        return buildJsonArray {
            message.content.forEach { block ->
                when (block) {
                    is LocalCanonicalContent.Text -> if (block.text.isNotEmpty()) {
                        add(buildJsonObject {
                            put("type", "text")
                            put("text", block.text)
                        })
                    }
                    is LocalCanonicalContent.ToolCall -> add(buildJsonObject {
                        put("type", "tool_use")
                        put("id", block.id)
                        put("name", block.name)
                        put("input", block.arguments)
                    })
                    // Thinking blocks without their provider signature are intentionally not replayed.
                    // Same-route Anthropic history uses the lossless replay envelope above.
                    else -> Unit
                }
            }
        }
    }

    private fun userContent(message: LocalCanonicalMessage): JsonArray = buildJsonArray {
        message.content.forEach { block ->
            when (block) {
                is LocalCanonicalContent.Text -> if (block.text.isNotEmpty()) {
                    add(buildJsonObject {
                        put("type", "text")
                        put("text", block.text)
                    })
                }
                is LocalCanonicalContent.Image -> {
                    parseDataImage(block.dataUrl).let { image ->
                        add(buildJsonObject {
                            put("type", "image")
                            put("source", buildJsonObject {
                                if (image.first == "url") {
                                    put("type", "url")
                                    put("url", image.second)
                                } else {
                                    put("type", "base64")
                                    put("media_type", image.first)
                                    put("data", image.second)
                                }
                            })
                        })
                    }
                }
                else -> throw protocolError("Anthropic 不支持此输入块", null)
            }
        }
    }

    private fun toolResultContent(message: LocalCanonicalMessage): JsonArray = buildJsonArray {
        message.content.filterIsInstance<LocalCanonicalContent.ToolResult>().forEach { result ->
            add(buildJsonObject {
                put("type", "tool_result")
                put("tool_use_id", result.callId)
                put("content", result.content)
                result.isError?.let { put("is_error", it) }
            })
        }
    }

    private fun parseStream(
        body: ResponseBody,
        route: LocalResolvedModelRoute,
        promptBreakdown: TokenPromptBreakdown,
        requestId: String?,
        retryAfterMs: Long?,
        onDelta: (LocalModelDelta) -> Unit,
    ): LocalModelReply {
        val blocks = linkedMapOf<Int, StreamBlock>()
        var messageId: String? = null
        var inputTokens = 0L
        var cacheReadTokens = 0L
        var cacheCreationTokens = 0L
        var outputTokens = 0L
        var stopReason: String? = null
        var sawMessageStop = false
        var sawMessageStart = false
        val closedBlocks = mutableSetOf<Int>()
        var totalBytes = 0

        val reader = body.charStream().buffered()
        run {
            while (true) {
                val line = try {
                            readBoundedLine(reader, MAX_SSE_LINE_CHARS)
                        } catch (failure: NetworkInputTooLargeException) {
                            throw LocalModelException(
                                code = "MODEL_RESPONSE_TOO_LARGE",
                                message = "Anthropic 流式响应单行超过安全上限",
                                retryable = false,
                                requestId = requestId,
                            )
                        } ?: break
                totalBytes += line.toByteArray(Charsets.UTF_8).size + 1
                if (totalBytes > MAX_STREAM_BYTES) {
                    throw LocalModelException(
                        code = "MODEL_RESPONSE_TOO_LARGE",
                        message = "Anthropic 流式响应超过本机安全上限",
                        retryable = false,
                        requestId = requestId,
                    )
                }
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data.isBlank()) continue
                val event = runCatching { json.parseToJsonElement(data).jsonObject }
                    .getOrElse { cause ->
                        throw protocolError("Anthropic SSE 包含无法解析的数据帧", requestId, cause)
                    }
                when (event["type"]?.jsonPrimitive?.contentOrNull) {
                    "message_start" -> {
                        if (sawMessageStart) throw protocolError("重复 message_start", requestId)
                        sawMessageStart = true
                        val message = event["message"] as? JsonObject
                            ?: throw protocolError("message_start 缺少 message", requestId)
                        messageId = message["id"]?.jsonPrimitive?.contentOrNull ?: messageId
                        (message["usage"] as? JsonObject)?.let { usage ->
                            inputTokens = usage["input_tokens"]?.jsonPrimitive?.longOrNull ?: inputTokens
                            cacheReadTokens = usage["cache_read_input_tokens"]?.jsonPrimitive?.longOrNull ?: cacheReadTokens
                            cacheCreationTokens = usage["cache_creation_input_tokens"]?.jsonPrimitive?.longOrNull ?: cacheCreationTokens
                            outputTokens = usage["output_tokens"]?.jsonPrimitive?.longOrNull ?: outputTokens
                        }
                    }
                    "content_block_start" -> {
                        val index = event["index"]?.jsonPrimitive?.intOrNull
                            ?: throw protocolError("content_block_start 缺少 index", requestId)
                        val block = event["content_block"] as? JsonObject
                            ?: throw protocolError("content_block_start 缺少 content_block", requestId)
                        if (index < 0 || index in blocks) throw protocolError("content block index 无效或重复", requestId)
                        blocks[index] = StreamBlock.from(block)
                    }
                    "content_block_delta" -> {
                        val index = event["index"]?.jsonPrimitive?.intOrNull
                            ?: throw protocolError("content_block_delta 缺少 index", requestId)
                        if (index in closedBlocks) throw protocolError("delta 引用了已结束 block", requestId)
                        val accumulator = blocks[index]
                            ?: throw protocolError("content_block_delta 引用了未知 block", requestId)
                        val delta = event["delta"] as? JsonObject
                            ?: throw protocolError("content_block_delta 缺少 delta", requestId)
                        val deltaType = delta["type"]?.jsonPrimitive?.contentOrNull
                        val expectedBlock = when (deltaType) {
                            "text_delta", "citations_delta" -> "text"
                            "thinking_delta", "signature_delta" -> "thinking"
                            "input_json_delta" -> "tool_use"
                            else -> null
                        }
                        if (expectedBlock != null && accumulator.type != expectedBlock) {
                            throw protocolError("delta 类型与内容块不一致", requestId)
                        }
                        when (deltaType) {
                            "text_delta" -> delta["text"]?.jsonPrimitive?.contentOrNull
                                ?.takeIf(String::isNotEmpty)
                                ?.let {
                                    accumulator.text.append(it)
                                    onDelta(LocalModelDelta(content = it))
                                }
                            "thinking_delta" -> delta["thinking"]?.jsonPrimitive?.contentOrNull
                                ?.takeIf(String::isNotEmpty)
                                ?.let {
                                    accumulator.thinking.append(it)
                                    onDelta(LocalModelDelta(reasoning = it))
                                }
                            "signature_delta" -> delta["signature"]?.jsonPrimitive?.contentOrNull
                                ?.let(accumulator.signature::append)
                            "input_json_delta" -> delta["partial_json"]?.jsonPrimitive?.contentOrNull
                                ?.let(accumulator.inputJson::append)
                            "citations_delta" -> delta["citation"]?.let(accumulator.citations::add)
                        }
                    }
                    "message_delta" -> {
                        val delta = event["delta"] as? JsonObject
                        stopReason = delta?.get("stop_reason")?.jsonPrimitive?.contentOrNull ?: stopReason
                        (event["usage"] as? JsonObject)?.let { usage ->
                            outputTokens = usage["output_tokens"]?.jsonPrimitive?.longOrNull ?: outputTokens
                            inputTokens = usage["input_tokens"]?.jsonPrimitive?.longOrNull ?: inputTokens
                            cacheReadTokens = usage["cache_read_input_tokens"]?.jsonPrimitive?.longOrNull ?: cacheReadTokens
                            cacheCreationTokens = usage["cache_creation_input_tokens"]?.jsonPrimitive?.longOrNull ?: cacheCreationTokens
                        }
                    }
                    "message_stop" -> {
                        sawMessageStop = true
                        break
                    }
                    "error" -> throw streamError(event, requestId, retryAfterMs)
                    "content_block_stop" -> {
                        val index = event["index"]?.jsonPrimitive?.intOrNull
                            ?: throw protocolError("content_block_stop 缺少 index", requestId)
                        if (index !in blocks || !closedBlocks.add(index)) throw protocolError("block stop 无效或重复", requestId)
                    }
                    "ping" -> Unit
                }
            }
        }

        if (!sawMessageStop) {
            throw modelPostAdmissionFailure(
                code = "ANTHROPIC_STREAM_INTERRUPTED_AFTER_ADMISSION",
                detail = "Anthropic Messages 流在 message_stop 前结束",
                requestId = requestId ?: messageId,
            )
        }
        if (!sawMessageStart || messageId.isNullOrBlank() || closedBlocks.size != blocks.size) {
            throw protocolError("message 起始身份或 block 终态不完整", requestId)
        }
        requireCompleteStopReason(stopReason, requestId ?: messageId)

        val nativeContent = buildJsonArray {
            blocks.toSortedMap().values.forEach { add(it.toNative(json, requestId ?: messageId)) }
        }
        val canonicalBlocks = mutableListOf<LocalCanonicalContent>()
        nativeContent.forEach { raw ->
            val block = raw as? JsonObject ?: return@forEach
            when (block["type"]?.jsonPrimitive?.contentOrNull) {
                "text" -> block["text"]?.jsonPrimitive?.contentOrNull?.let {
                    canonicalBlocks += LocalCanonicalContent.Text(it)
                }
                "thinking" -> block["thinking"]?.jsonPrimitive?.contentOrNull?.let {
                    canonicalBlocks += LocalCanonicalContent.Reasoning(it)
                }
                "tool_use" -> {
                    val id = block["id"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
                        ?: throw protocolError("tool_use 缺少 id", requestId)
                    val name = block["name"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
                        ?: throw protocolError("tool_use 缺少 name", requestId)
                    val input = block["input"] as? JsonObject
                        ?: throw protocolError("tool_use input 不是对象", requestId)
                    canonicalBlocks += LocalCanonicalContent.ToolCall(
                        id = id,
                        name = name,
                        arguments = input,
                        rawArguments = input.toString(),
                    )
                }
            }
        }
        val ids = canonicalBlocks.filterIsInstance<LocalCanonicalContent.ToolCall>().map { it.id }
        if (ids.distinct().size != ids.size) throw protocolError("工具调用 id 重复", requestId)
        if ((stopReason == "tool_use") != ids.isNotEmpty()) throw protocolError("stop_reason 与工具调用不一致", requestId)
        val replay = LocalModelReplayEnvelope(
            adapterId = LocalModelAdapterIds.ANTHROPIC_MESSAGES,
            routeFingerprint = route.fingerprint,
            payload = buildJsonObject {
                put("content", nativeContent)
                stopReason?.let { put("stop_reason", it) }
                messageId?.let { put("message_id", it) }
            },
        )
        val canonical = LocalCanonicalMessage(
            role = LocalCanonicalRole.ASSISTANT,
            content = canonicalBlocks,
            replay = replay,
        )
        val promptTokens = safeAdd(inputTokens, safeAdd(cacheReadTokens, cacheCreationTokens))
        val toolCalls = canonicalBlocks.filterIsInstance<LocalCanonicalContent.ToolCall>()
            .map(LocalCanonicalContent.ToolCall::toLocalToolCall)
        return LocalModelReply(
            message = LocalCanonicalModelCodec.toHistoryMessage(canonical),
            content = canonicalBlocks.filterIsInstance<LocalCanonicalContent.Text>()
                .joinToString("") { it.text }.ifBlank { null },
            reasoning = canonicalBlocks.filterIsInstance<LocalCanonicalContent.Reasoning>()
                .joinToString("") { it.text }.ifBlank { null },
            toolCalls = toolCalls,
            usage = DeepSeekTokenUsage(
                promptTokens = promptTokens,
                cacheHitTokens = cacheReadTokens.coerceAtLeast(0L),
                cacheMissTokens = safeAdd(inputTokens, cacheCreationTokens),
                completionTokens = outputTokens.coerceAtLeast(0L),
                reported = true,
            ),
            requestId = requestId ?: messageId.orEmpty(),
            promptBreakdown = promptBreakdown,
            canonicalMessage = canonical,
        )
    }

    private data class StreamBlock(
        val type: String,
        val id: String?,
        val name: String?,
        val initialInput: JsonObject?,
        val rawStart: JsonObject,
        val text: StringBuilder = StringBuilder(),
        val thinking: StringBuilder = StringBuilder(),
        val signature: StringBuilder = StringBuilder(),
        val inputJson: StringBuilder = StringBuilder(),
        val citations: MutableList<JsonElement> = mutableListOf(),
    ) {
        fun toNative(json: Json, requestId: String?): JsonObject = when (type) {
            "text" -> buildJsonObject {
                rawStart.forEach { (key, value) -> if (key !in setOf("text", "citations")) put(key, value) }
                put("type", "text")
                put("text", text.toString())
                if (citations.isNotEmpty()) put("citations", JsonArray(citations))
            }
            "thinking" -> buildJsonObject {
                if (signature.isEmpty()) throw protocolErrorStatic("thinking 缺少 signature", requestId)
                rawStart.forEach { (key, value) ->
                    if (key !in setOf("thinking", "signature")) put(key, value)
                }
                put("type", "thinking")
                put("thinking", thinking.toString())
                if (signature.isNotEmpty()) put("signature", signature.toString())
            }
            "tool_use" -> {
                val input = if (inputJson.isNotEmpty()) {
                    runCatching { json.parseToJsonElement(inputJson.toString()).jsonObject }
                        .getOrElse { cause ->
                            throw protocolErrorStatic("tool_use 参数不是合法 JSON 对象", requestId, cause)
                        }
                } else {
                    initialInput ?: throw protocolErrorStatic("tool_use input 缺失或不是对象", requestId)
                }
                buildJsonObject {
                    rawStart.forEach { (key, value) ->
                        if (key !in setOf("input", "id", "name")) put(key, value)
                    }
                    put("type", "tool_use")
                    id?.let { put("id", it) }
                    name?.let { put("name", it) }
                    put("input", input)
                }
            }
            else -> rawStart
        }

        companion object {
            fun from(block: JsonObject): StreamBlock {
                val type = block["type"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (type !in setOf("text", "thinking", "redacted_thinking", "tool_use")) {
                    throw protocolErrorStatic("不支持的 Anthropic 内容块：$type", null)
                }
                return StreamBlock(
                    type = type,
                    id = block["id"]?.jsonPrimitive?.contentOrNull,
                    name = block["name"]?.jsonPrimitive?.contentOrNull,
                    initialInput = block["input"] as? JsonObject,
                    rawStart = block,
                    text = StringBuilder(block["text"]?.jsonPrimitive?.contentOrNull.orEmpty()),
                    thinking = StringBuilder(block["thinking"]?.jsonPrimitive?.contentOrNull.orEmpty()),
                    signature = StringBuilder(block["signature"]?.jsonPrimitive?.contentOrNull.orEmpty()),
                )
            }
        }
    }

    private fun parseDataImage(value: String): Pair<String, String> {
        if (value.startsWith("https://")) return "url" to value
        val split = value.indexOf(";base64,")
        if (!value.startsWith("data:image/") || split <= 5) throw protocolError("图片必须为 data URL 或 HTTPS URL", null)
        val mediaType = value.substring(5, split)
        val data = value.substring(split + 8)
        if (mediaType !in SUPPORTED_IMAGE_MEDIA_TYPES || data.isEmpty() || data.length % 4 != 0 ||
            data.length > 10_000_000) {
            throw protocolError("图片格式无效或超过 Anthropic 10 MB base64 限制", null)
        }
        return mediaType to data
    }

    private fun mergeAdjacentTurns(messages: JsonArray): JsonArray {
        val merged = mutableListOf<JsonObject>()
        messages.forEach { raw ->
            val message = raw.jsonObject
            val previous = merged.lastOrNull()
            if (previous != null && previous["role"] == message["role"]) {
                merged[merged.lastIndex] = buildJsonObject {
                    put("role", message.getValue("role"))
                    put("content", JsonArray((previous.getValue("content") as JsonArray) + (message.getValue("content") as JsonArray)))
                }
            } else merged.add(message)
        }
        return JsonArray(merged)
    }

    private fun endpoint(baseUrl: String): String {
        val clean = normalizeModelBaseUrl(baseUrl).trimEnd('/')
        return if (clean.endsWith("/messages")) clean else "$clean/messages"
    }

    private fun httpFailure(
        status: Int,
        body: String,
        requestId: String?,
        retryAfterMs: Long?,
    ): LocalModelException {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
        val error = root?.get("error") as? JsonObject
        val type = error?.get("type")?.jsonPrimitive?.contentOrNull
        val detail = error?.get("message")?.jsonPrimitive?.contentOrNull ?: body.take(500)
        return LocalModelException(
            code = "MODEL_HTTP_$status",
            message = "Anthropic 模型请求失败（HTTP $status）：$detail",
            retryable = status == 408 || status == 429 || status >= 500 ||
                type in setOf("overloaded_error", "rate_limit_error", "api_error"),
            status = status,
            providerRetryAfterMs = retryAfterMs,
            requestId = requestId,
            providerCode = type,
            admissionState = LocalModelAdmissionState.REJECTED,
        )
    }

    private fun streamError(
        event: JsonObject,
        requestId: String?,
        retryAfterMs: Long?,
    ): LocalModelException {
        val error = event["error"] as? JsonObject
        val type = error?.get("type")?.jsonPrimitive?.contentOrNull
        val detail = error?.get("message")?.jsonPrimitive?.contentOrNull ?: "Anthropic 流式请求失败"
        return LocalModelException(
            code = "ANTHROPIC_STREAM_ERROR",
            message = detail,
            retryable = false,
            providerRetryAfterMs = retryAfterMs,
            requestId = requestId,
            providerCode = type ?: "stream_error_after_admission",
        )
    }

    private fun requireCompleteStopReason(reason: String?, requestId: String?) {
        when (reason) {
            "end_turn", "stop_sequence", "tool_use", "refusal" -> return
            "max_tokens" -> throw LocalModelException(
                code = "MODEL_OUTPUT_TRUNCATED",
                message = "模型输出达到长度上限，回复未完整生成，请缩短任务后重试。",
                retryable = false,
                requestId = requestId,
            )
            else -> throw LocalModelException(
                code = "MODEL_FINISH_$reason",
                message = "Anthropic 模型未正常完成回复（$reason）",
                retryable = false,
                requestId = requestId,
            )
        }
    }

    private fun protocolError(
        detail: String,
        requestId: String?,
        cause: Throwable? = null,
    ): LocalModelException = protocolErrorStatic(detail, requestId, cause)

    private fun parseRetryAfterMillis(
        value: String?,
        nowMillis: Long = System.currentTimeMillis(),
    ): Long? {
        val raw = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
        raw.toLongOrNull()?.let { return it.coerceIn(0L, Long.MAX_VALUE / 1_000L) * 1_000L }
        return runCatching {
            val atMillis = ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME)
                .toInstant().toEpochMilli()
            (atMillis - nowMillis).coerceAtLeast(0L)
        }.getOrNull()
    }

    private fun safeAdd(left: Long, right: Long): Long =
        if (left > Long.MAX_VALUE - right.coerceAtLeast(0L)) Long.MAX_VALUE
        else left.coerceAtLeast(0L) + right.coerceAtLeast(0L)

    private companion object {
        fun protocolErrorStatic(
            detail: String,
            requestId: String?,
            cause: Throwable? = null,
        ) = LocalModelException(
            code = "ANTHROPIC_PROTOCOL_ERROR",
            message = detail,
            retryable = false,
            requestId = requestId,
            cause = cause,
        )

        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        const val ANTHROPIC_VERSION = "2023-06-01"
        const val DEFAULT_MAX_TOKENS = 8192
        const val CONNECT_TIMEOUT_SECONDS = 20L
        const val READ_TIMEOUT_SECONDS = 300L
        const val WRITE_TIMEOUT_SECONDS = 60L
        const val CALL_TIMEOUT_SECONDS = 360L
        const val MAX_STREAM_BYTES = 32 * 1024 * 1024
        private const val MAX_SSE_LINE_CHARS = 4 * 1024 * 1024
        const val ERROR_BODY_LIMIT = 8_000
        val SUPPORTED_IMAGE_MEDIA_TYPES = setOf(
            "image/jpeg",
            "image/png",
            "image/gif",
            "image/webp",
        )
    }
}

package com.labteto.dshmobile.local.model

import com.labteto.dshmobile.local.DeepSeekTokenUsage
import com.labteto.dshmobile.local.LocalModelDelta
import com.labteto.dshmobile.local.LocalModelException
import com.labteto.dshmobile.local.LocalModelReply
import com.labteto.dshmobile.local.LocalToolCall
import com.labteto.dshmobile.local.TokenPromptBreakdown
import com.labteto.dshmobile.local.estimatePromptBreakdown
import com.labteto.dshmobile.local.normalizeModelBaseUrl
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
import okhttp3.ResponseBody
import okhttp3.RequestBody.Companion.toRequestBody

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
        val request = Request.Builder()
            .url(endpoint(route.baseUrl))
            .header("x-api-key", route.bearerToken)
            .header("anthropic-version", ANTHROPIC_VERSION)
            .header("accept", "text/event-stream")
            .header("content-type", "application/json")
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .build()
        try {
            withCancellableModelResponse(client.newCall(request)) { response ->
                val requestId = response.header("request-id")
                    ?: response.header("anthropic-request-id")
                val retryAfterMs = parseRetryAfterMillis(response.header("Retry-After"))
                if (!response.isSuccessful) {
                    val body = response.body?.readBounded(ERROR_BODY_LIMIT).orEmpty()
                    throw httpFailure(response.code, body, requestId, retryAfterMs)
                }
                val body = response.body ?: throw LocalModelException(
                    code = "ANTHROPIC_STREAM_INCOMPLETE",
                    message = "Anthropic Messages 返回了空响应",
                    retryable = true,
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
            throw error
        } catch (error: SocketTimeoutException) {
            throw LocalModelException(
                code = "MODEL_TIMEOUT",
                message = "Anthropic 模型请求超时：${error.message ?: "请求未在时限内完成"}",
                retryable = true,
                cause = error,
            )
        } catch (error: IOException) {
            throw LocalModelException(
                code = "MODEL_NETWORK",
                message = "Anthropic 模型网络请求失败：${error.message ?: "网络异常"}",
                retryable = true,
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
            put("messages", buildJsonArray {
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
            })
            if (tools.isNotEmpty()) put("tools", buildJsonArray {
                tools.forEach { tool ->
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
            if (replay is JsonArray) return replay
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
                    parseDataImage(block.dataUrl)?.let { image ->
                        add(buildJsonObject {
                            put("type", "image")
                            put("source", buildJsonObject {
                                put("type", "base64")
                                put("media_type", image.first)
                                put("data", image.second)
                            })
                        })
                    }
                }
                else -> Unit
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
        var totalBytes = 0

        body.charStream().buffered().use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
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
                        blocks[index] = StreamBlock.from(block)
                    }
                    "content_block_delta" -> {
                        val index = event["index"]?.jsonPrimitive?.intOrNull
                            ?: throw protocolError("content_block_delta 缺少 index", requestId)
                        val accumulator = blocks[index]
                            ?: throw protocolError("content_block_delta 引用了未知 block", requestId)
                        val delta = event["delta"] as? JsonObject
                            ?: throw protocolError("content_block_delta 缺少 delta", requestId)
                        when (delta["type"]?.jsonPrimitive?.contentOrNull) {
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
                    "ping", "content_block_stop" -> Unit
                }
            }
        }

        if (!sawMessageStop) {
            throw LocalModelException(
                code = "ANTHROPIC_STREAM_INCOMPLETE",
                message = "Anthropic Messages 流在 message_stop 前结束",
                retryable = true,
                requestId = requestId ?: messageId,
            )
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
                    val id = block["id"]?.jsonPrimitive?.contentOrNull
                        ?: throw protocolError("tool_use 缺少 id", requestId)
                    val name = block["name"]?.jsonPrimitive?.contentOrNull
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
                cacheHitTokens = cacheReadTokens,
                cacheMissTokens = safeAdd(inputTokens, cacheCreationTokens),
                completionTokens = outputTokens,
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
                    initialInput ?: JsonObject(emptyMap())
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

    private fun parseDataImage(value: String): Pair<String, String>? {
        if (!value.startsWith("data:image/")) return null
        val marker = ";base64,"
        val split = value.indexOf(marker)
        if (split <= 5) return null
        val mediaType = value.substring(5, split)
        val data = value.substring(split + marker.length)
        return mediaType.takeIf { it in SUPPORTED_IMAGE_MEDIA_TYPES && data.isNotBlank() }?.let { it to data }
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
            retryable = type in setOf("overloaded_error", "rate_limit_error", "api_error"),
            providerRetryAfterMs = retryAfterMs,
            requestId = requestId,
            providerCode = type,
        )
    }

    private fun requireCompleteStopReason(reason: String?, requestId: String?) {
        when (reason) {
            null, "end_turn", "stop_sequence", "tool_use" -> return
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
        raw.toLongOrNull()?.let { return it.coerceAtLeast(0L) * 1_000L }
        return runCatching {
            val atMillis = ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME)
                .toInstant().toEpochMilli()
            (atMillis - nowMillis).coerceAtLeast(0L)
        }.getOrNull()
    }

    private fun ResponseBody.readBounded(maxBytes: Int): String {
        val declared = contentLength()
        if (declared > maxBytes) {
            throw LocalModelException(
                code = "MODEL_RESPONSE_TOO_LARGE",
                message = "Anthropic 错误响应超过本机安全上限",
                retryable = false,
            )
        }
        val bytes = byteStream().use { input ->
            val output = java.io.ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
            val buffer = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > maxBytes) {
                    throw LocalModelException(
                        code = "MODEL_RESPONSE_TOO_LARGE",
                        message = "Anthropic 错误响应超过本机安全上限",
                        retryable = false,
                    )
                }
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        }
        return bytes.toString(Charsets.UTF_8)
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
        const val ERROR_BODY_LIMIT = 8_000
        val SUPPORTED_IMAGE_MEDIA_TYPES = setOf(
            "image/jpeg",
            "image/png",
            "image/gif",
            "image/webp",
        )
    }
}

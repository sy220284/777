package com.labteto.dshmobile.local

import java.io.ByteArrayOutputStream
import java.net.SocketTimeoutException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import com.labteto.dshmobile.local.model.withCancellableModelResponse
import com.labteto.dshmobile.local.model.validatedModelToolCall
import com.labteto.dshmobile.local.model.requireUniqueModelToolCallIds
import com.labteto.dshmobile.local.model.modelPostAdmissionFailure
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import java.util.concurrent.TimeUnit

/** OpenAI-compatible DeepSeek transport used by the on-device agent loop. */
@Singleton
class DeepSeekClient @Inject constructor(
    private val http: OkHttpClient,
    private val json: Json,
) {
    private val modelHttp = http.newBuilder()
        .connectTimeout(MODEL_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(MODEL_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(MODEL_WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(MODEL_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()
    /** Run one model step and preserve its raw assistant message for tool continuation. */
    suspend fun complete(
        apiKey: String,
        baseUrl: String,
        model: String,
        messages: List<JsonObject>,
        tools: JsonArray = LocalToolCatalog.specs,
        temperature: Double? = null,
    ): LocalModelReply = withContext(Dispatchers.IO) {
        val requestId = UUID.randomUUID().toString()
        val promptBreakdown = estimatePromptBreakdown(messages, tools)
        val toolCallingMode = resolveToolCallingMode(model, baseUrl, tools)
        val payload = buildJsonObject {
            put("model", model)
            put("messages", JsonArray(messages))
            put("stream", false)
            temperature?.let { put("temperature", it) }
            if (
                toolCallingMode == LocalModelToolCallingMode.CHAT_COMPLETIONS_NO_REASONING &&
                (tools.isNotEmpty() || temperature != null)
            ) {
                put("reasoning_effort", "none")
            }
            if (tools.isNotEmpty()) {
                put("tools", tools)
                if (shouldSendToolChoice(baseUrl, model)) put("tool_choice", "auto")
            }
        }
        val request = Request.Builder()
            .url(endpoint(baseUrl))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .build()
        var responseAdmitted = false
        try {
            withCancellableModelResponse(modelHttp.newCall(request)) { response ->
                if (!response.isSuccessful) {
                    val body = response.readModelBodyBounded()
                    val detail = providerErrorDetail(body, json)
                    throw LocalModelException(
                        code = "MODEL_HTTP_${response.code}",
                        message = "模型请求失败（HTTP ${response.code}）：${detail ?: body.take(500)}",
                        retryable = response.code == 408 || response.code == 429 || response.code >= 500,
                    )
                }
                responseAdmitted = true
                val body = response.readModelBodyBounded()
                parse(body).copy(
                    requestId = requestId,
                    promptBreakdown = promptBreakdown,
                )
            }
        } catch (error: LocalModelException) {
            throw error
        } catch (error: SocketTimeoutException) {
            if (responseAdmitted) {
                throw modelPostAdmissionFailure(
                    code = "MODEL_RESPONSE_INTERRUPTED_AFTER_ADMISSION",
                    detail = "模型响应读取超时",
                    requestId = requestId,
                    cause = error,
                )
            }
            throw LocalModelException(
                code = "MODEL_TIMEOUT",
                message = "模型推理超时：${error.message ?: "请求未在时限内完成"}",
                retryable = true,
                cause = error,
            )
        } catch (error: java.io.IOException) {
            if (responseAdmitted) {
                throw modelPostAdmissionFailure(
                    code = "MODEL_RESPONSE_INTERRUPTED_AFTER_ADMISSION",
                    detail = "模型响应连接中断",
                    requestId = requestId,
                    cause = error,
                )
            }
            throw LocalModelException(
                code = "MODEL_NETWORK",
                message = "模型网络请求失败：${error.message ?: "网络异常"}",
                retryable = true,
                cause = error,
            )
        }
    }

    suspend fun completeStreaming(
        apiKey: String,
        baseUrl: String,
        model: String,
        messages: List<JsonObject>,
        tools: JsonArray = LocalToolCatalog.specs,
        temperature: Double? = null,
        onDelta: (LocalModelDelta) -> Unit = { },
    ): LocalModelReply = withContext(Dispatchers.IO) {
        val requestId = UUID.randomUUID().toString()
        val promptBreakdown = estimatePromptBreakdown(messages, tools)
        val toolCallingMode = resolveToolCallingMode(model, baseUrl, tools)
        val payload = buildJsonObject {
            put("model", model)
            put("messages", JsonArray(messages))
            put("stream", true)
            temperature?.let { put("temperature", it) }
            put("stream_options", buildJsonObject { put("include_usage", true) })
            if (
                toolCallingMode == LocalModelToolCallingMode.CHAT_COMPLETIONS_NO_REASONING &&
                (tools.isNotEmpty() || temperature != null)
            ) {
                put("reasoning_effort", "none")
            }
            if (tools.isNotEmpty()) {
                put("tools", tools)
                if (shouldSendToolChoice(baseUrl, model)) put("tool_choice", "auto")
            }
        }
        val request = Request.Builder()
            .url(endpoint(baseUrl))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .build()
        var responseAdmitted = false
        try {
            withCancellableModelResponse(modelHttp.newCall(request)) { response ->
                if (!response.isSuccessful) {
                    val body = response.readModelBodyBounded()
                    val detail = providerErrorDetail(body, json)
                    throw LocalModelException(
                        code = "MODEL_HTTP_${response.code}",
                        message = "模型请求失败（HTTP ${response.code}）：${detail ?: body.take(500)}",
                        retryable = response.code == 408 || response.code == 429 || response.code >= 500,
                    )
                }
                responseAdmitted = true
                val responseBody = response.body ?: throw modelPostAdmissionFailure(
                    code = "MODEL_STREAM_INTERRUPTED_AFTER_ADMISSION",
                    detail = "模型流式响应为空",
                    requestId = requestId,
                )
                val content = StringBuilder()
                val reasoning = StringBuilder()
                val fallback = StringBuilder()
                val toolCalls = linkedMapOf<Int, StreamToolCall>()
                var streamUsage: JsonObject? = null
                var totalBytes = 0
                var sawStreamData = false
                var sawTerminalFrame = false
                // Response 统一由 withCancellableModelResponse 收尾，避免成功后 reader.close()
                // 的连接异常覆盖已完成结果并触发重复请求。
                val reader = responseBody.charStream().buffered()
                run {
                    while (true) {
                        val line = reader.readLine() ?: break
                        totalBytes += line.toByteArray(Charsets.UTF_8).size + 1
                        if (totalBytes > MAX_MODEL_RESPONSE_BYTES) {
                            throw LocalModelException(
                                code = "MODEL_RESPONSE_TOO_LARGE",
                                message = "模型流式响应超过 ${MAX_MODEL_RESPONSE_BYTES} 字节上限",
                                retryable = false,
                            )
                        }
                        if (!line.startsWith("data:")) {
                            if (line.isNotBlank()) fallback.append(line)
                            continue
                        }
                        sawStreamData = true
                        val data = line.removePrefix("data:").trim()
                        if (data.isBlank()) continue
                        if (data == "[DONE]") {
                            sawTerminalFrame = true
                            break
                        }
                        val root = try {
                            json.parseToJsonElement(data) as? JsonObject
                                ?: throw streamProtocolError("模型流式响应数据帧不是 JSON 对象")
                        } catch (error: LocalModelException) {
                            throw error
                        } catch (error: Exception) {
                            throw streamProtocolError("模型流式响应包含无法解析的数据帧", error)
                        }
                        if (root["error"] != null && root["error"] !is JsonNull) {
                            throw LocalModelException(
                                code = "MODEL_STREAM_ERROR",
                                message = "模型流式请求失败：${providerErrorDetail(root.toString(), json) ?: "未知错误"}",
                                retryable = false,
                                requestId = requestId,
                            )
                        }
                        (root["usage"] as? JsonObject)?.let { streamUsage = it }
                        val firstChoice = (root["choices"] as? JsonArray)
                            ?.firstOrNull() as? JsonObject
                        val finishReason = (firstChoice?.get("finish_reason") as? JsonPrimitive)
                            ?.contentOrNull
                        if (!finishReason.isNullOrBlank()) {
                            requireCompleteFinishReason(finishReason)
                            sawTerminalFrame = true
                        }
                        val delta = firstChoice?.get("delta") as? JsonObject
                        if (delta == null) continue
                        val textDelta = assistantText(delta["content"]).orEmpty() +
                            (delta["refusal"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                        val reasoningDelta = (delta["reasoning_content"] as? JsonPrimitive)
                            ?.contentOrNull.orEmpty()
                        if (textDelta.isNotEmpty()) content.append(textDelta)
                        if (reasoningDelta.isNotEmpty()) reasoning.append(reasoningDelta)
                        if (textDelta.isNotEmpty() || reasoningDelta.isNotEmpty()) {
                            onDelta(LocalModelDelta(textDelta, reasoningDelta))
                        }
                        val callDeltas = delta["tool_calls"]?.takeUnless { it == JsonNull }?.let {
                            it as? JsonArray ?: throw streamProtocolError("tool_calls 必须是数组")
                        }
                        callDeltas.orEmpty().forEach { element ->
                            val item = element as? JsonObject
                                ?: throw streamProtocolError("模型流式工具调用数据帧格式错误")
                            val index = (item["index"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
                                ?.takeIf { it >= 0 } ?: throw streamProtocolError("工具调用 index 无效或缺失")
                            val acc = toolCalls.getOrPut(index) { StreamToolCall() }
                            acc.metadata = mergeModelMetadata(acc.metadata, JsonObject(item.filterKeys {
                                it !in setOf("index", "id", "type", "function")
                            }))
                            streamToolString(item["id"], "id")?.let { id ->
                                if (acc.id != null && acc.id != id) throw streamProtocolError("同一 index 的工具调用 id 发生变化")
                                acc.id = id
                            }
                            val function = item["function"]?.takeUnless { it == JsonNull }?.let {
                                it as? JsonObject ?: throw streamProtocolError("工具调用 function 必须是对象")
                            }
                            streamToolString(function?.get("name"), "name")?.let { name ->
                                if (acc.name != null && acc.name != name) throw streamProtocolError("同一 index 的工具名称发生变化")
                                acc.name = name
                            }
                            streamToolString(function?.get("arguments"), "arguments", allowEmpty = true)
                                ?.let(acc.arguments::append)
                        }
                    }
                }
                if (!sawStreamData) {
                    if (fallback.isBlank()) {
                        throw modelPostAdmissionFailure(
                            code = "MODEL_STREAM_INTERRUPTED_AFTER_ADMISSION",
                            detail = "模型流式响应为空",
                            requestId = requestId,
                        )
                    }
                    return@withCancellableModelResponse try {
                        parse(fallback.toString()).copy(
                            requestId = requestId,
                            promptBreakdown = promptBreakdown,
                        )
                    } catch (error: LocalModelException) {
                        throw error
                    } catch (error: Exception) {
                        throw streamProtocolError("模型响应无法解析", error)
                    }
                }
                if (!sawTerminalFrame) {
                    throw modelPostAdmissionFailure(
                        code = "MODEL_STREAM_INTERRUPTED_AFTER_ADMISSION",
                        detail = "模型流式响应提前结束，未收到完成标记",
                        requestId = requestId,
                    )
                }
                val accumulated = toolCalls.toSortedMap().values.toList()
                val calls = accumulated.map { call ->
                    validatedModelToolCall(
                        id = call.id?.let(::JsonPrimitive),
                        name = call.name?.let(::JsonPrimitive),
                        arguments = JsonPrimitive(call.arguments.toString()),
                        code = "MODEL_STREAM_PROTOCOL",
                        retryable = false,
                    )
                }
                requireUniqueModelToolCallIds(calls, "MODEL_STREAM_PROTOCOL", retryable = false)
                val message = buildJsonObject {
                    put("role", "assistant")
                    put("content", content.toString())
                    if (reasoning.isNotEmpty()) put("reasoning_content", reasoning.toString())
                    if (calls.isNotEmpty()) {
                        put("tool_calls", buildJsonArray {
                            calls.zip(accumulated).forEach { (call, accumulator) ->
                                add(buildJsonObject {
                                    put("id", call.id)
                                    put("type", "function")
                                    accumulator.metadata.forEach { (key, value) -> put(key, value) }
                                    put("function", buildJsonObject {
                                        put("name", call.name)
                                        put("arguments", call.rawArguments)
                                    })
                                })
                            }
                        })
                    }
                }
                val usage = streamUsage?.let { value ->
                    parseDeepSeekOpenAiUsage(buildJsonObject { put("usage", value) })
                } ?: DeepSeekTokenUsage(reported = false)
                LocalModelReply(
                    message = message,
                    content = content.toString(),
                    reasoning = reasoning.toString().takeIf(String::isNotBlank),
                    toolCalls = calls,
                    usage = usage,
                    requestId = requestId,
                    promptBreakdown = promptBreakdown,
                )
            }
        } catch (error: LocalModelException) {
            throw error
        } catch (error: SocketTimeoutException) {
            if (responseAdmitted) {
                throw modelPostAdmissionFailure(
                    code = "MODEL_STREAM_INTERRUPTED_AFTER_ADMISSION",
                    detail = "模型流式响应超时",
                    requestId = requestId,
                    cause = error,
                )
            }
            throw LocalModelException(
                code = "MODEL_TIMEOUT",
                message = "模型推理超时：${error.message ?: "请求未在时限内完成"}",
                retryable = true,
                cause = error,
            )
        } catch (error: java.io.IOException) {
            if (responseAdmitted) {
                throw modelPostAdmissionFailure(
                    code = "MODEL_STREAM_INTERRUPTED_AFTER_ADMISSION",
                    detail = "模型流式连接中断",
                    requestId = requestId,
                    cause = error,
                )
            }
            throw LocalModelException(
                code = "MODEL_NETWORK",
                message = "模型网络请求失败：${error.message ?: "网络异常"}",
                retryable = true,
                cause = error,
            )
        }
    }

    private data class StreamToolCall(
        var id: String? = null,
        var name: String? = null,
        val arguments: StringBuilder = StringBuilder(),
        var metadata: JsonObject = JsonObject(emptyMap()),
    )
    internal fun parse(body: String): LocalModelReply {
        val root = json.parseToJsonElement(body).jsonObject
        val choice = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
        (choice?.get("finish_reason") as? JsonPrimitive)?.contentOrNull
            ?.takeIf(String::isNotBlank)?.let(::requireCompleteFinishReason)
        val message = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("message")?.jsonObject ?: error("模型响应缺少 choices[0].message")
        val rawToolCalls = message["tool_calls"]?.takeUnless { it == JsonNull }?.let {
            it as? JsonArray ?: throw LocalModelException("MODEL_RESPONSE_PROTOCOL", "tool_calls 必须是数组", false)
        }.orEmpty()
        val normalizedMessage = if (
            rawToolCalls.isNotEmpty() && (message["content"] == null || message["content"] is JsonNull)
        ) {
            JsonObject(message + ("content" to JsonPrimitive("")))
        } else {
            message
        }
        val calls = rawToolCalls.map { element ->
            val item = element as? JsonObject
                ?: throw LocalModelException("MODEL_RESPONSE_PROTOCOL", "工具调用必须是对象", false)
            val function = item["function"] as? JsonObject
                ?: throw LocalModelException("MODEL_RESPONSE_PROTOCOL", "工具调用缺少 function 对象", false)
            validatedModelToolCall(item["id"], function["name"], function["arguments"], "MODEL_RESPONSE_PROTOCOL")
        }
        requireUniqueModelToolCallIds(calls, "MODEL_RESPONSE_PROTOCOL")
        val usage = parseDeepSeekOpenAiUsage(root)
        return LocalModelReply(
            message = normalizedMessage,
            content = assistantText(message["content"])
                ?: (message["refusal"] as? JsonPrimitive)?.contentOrNull,
            reasoning = message["reasoning_content"]?.jsonPrimitive?.contentOrNull,
            toolCalls = calls,
            usage = usage,
        )
    }

    private fun assistantText(content: kotlinx.serialization.json.JsonElement?): String? = when (content) {
        is JsonPrimitive -> content.contentOrNull
        is JsonArray -> content.mapNotNull { part ->
            val obj = part as? JsonObject ?: return@mapNotNull null
            when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                "text", "output_text" -> obj["text"]?.jsonPrimitive?.contentOrNull
                else -> null
            }
        }.joinToString("\n").takeIf(String::isNotBlank)
        else -> null
    }

    private fun Response.readModelBodyBounded(maxBytes: Int = MAX_MODEL_RESPONSE_BYTES): String {
        val responseBody = body ?: return ""
        val declared = responseBody.contentLength()
        if (declared > maxBytes) {
            throw LocalModelException(
                code = "MODEL_RESPONSE_TOO_LARGE",
                message = "模型响应超过 ${maxBytes} 字节上限",
                retryable = false,
            )
        }
        val output = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
        responseBody.byteStream().use { input ->
            val buffer = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > maxBytes) {
                    throw LocalModelException(
                        code = "MODEL_RESPONSE_TOO_LARGE",
                        message = "模型响应超过 ${maxBytes} 字节上限",
                        retryable = false,
                    )
                }
                output.write(buffer, 0, read)
            }
        }
        return output.toString(Charsets.UTF_8.name())
    }

    private fun resolveToolCallingMode(
        model: String,
        baseUrl: String,
        tools: JsonArray,
    ): LocalModelToolCallingMode {
        val mode = LocalModelPresets.toolCallingModeFor(model, baseUrl)
        if (tools.isNotEmpty() && mode == LocalModelToolCallingMode.RESPONSES_ONLY) {
            throw LocalModelException(
                code = "MODEL_TOOL_CALLING_UNSUPPORTED",
                message = "当前模型在工作模式下需要 Responses API 才支持工具调用，当前调用链暂不支持，请切换支持 Chat Completions 工具调用的模型后重试。",
                retryable = false,
            )
        }
        return mode
    }

    private fun endpoint(baseUrl: String): String {
        val clean = normalizeModelBaseUrl(baseUrl).trimEnd('/')
        return if (clean.endsWith("/chat/completions")) clean else "$clean/chat/completions"
    }

    private companion object {
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        const val MODEL_CONNECT_TIMEOUT_SECONDS = 15L
        const val MODEL_READ_TIMEOUT_SECONDS = 180L
        const val MODEL_WRITE_TIMEOUT_SECONDS = 60L
        const val MODEL_CALL_TIMEOUT_SECONDS = 210L
        const val MAX_MODEL_RESPONSE_BYTES = 16 * 1024 * 1024
    }
}

internal fun mergeModelMetadata(previous: JsonObject, next: JsonObject): JsonObject =
    JsonObject(previous.toMutableMap().apply {
        next.forEach { (key, value) ->
            val old = get(key)
            if (value !is JsonNull || old == null) {
                put(key, if (old is JsonObject && value is JsonObject) mergeModelMetadata(old, value) else value)
            }
        }
    })

private fun requireCompleteFinishReason(reason: String) {
    if (reason in setOf("stop", "tool_calls", "function_call")) return
    throw LocalModelException(
        code = if (reason == "length") "MODEL_OUTPUT_TRUNCATED" else "MODEL_FINISH_$reason",
        message = if (reason == "length") "模型输出达到长度上限，回复未完整生成，请缩短任务后重试。"
            else "模型未正常完成回复（$reason），请调整请求后重试。",
        retryable = false,
    )
}

internal fun shouldSendToolChoice(baseUrl: String, model: String): Boolean {
    val officialDeepSeek = normalizeModelBaseUrl(baseUrl)
        .lowercase()
        .contains("api.deepseek.com")
    val thinkingModel = model.trim().lowercase() in setOf(
        "deepseek-flash",
        "deepseek-v4-pro",
        "deepseek-reasoner",
    )
    return !(officialDeepSeek && thinkingModel)
}

private fun streamToolString(value: JsonElement?, field: String, allowEmpty: Boolean = false): String? {
    if (value == null || value == JsonNull) return null
    val primitive = value as? JsonPrimitive
    if (primitive == null || !primitive.isString || (!allowEmpty && primitive.content.isBlank())) {
        throw streamProtocolError("工具调用 $field 必须是字符串")
    }
    return primitive.content
}

private fun streamProtocolError(
    detail: String,
    cause: Throwable? = null,
): LocalModelException = LocalModelException(
    code = "MODEL_STREAM_PROTOCOL",
    message = detail,
    retryable = false,
    cause = cause,
    providerCode = "protocol_error_after_admission",
)

internal fun providerErrorDetail(body: String, json: Json): String? = runCatching {
    val root = json.parseToJsonElement(body).jsonObject
    val error = root["error"]
    when (error) {
        is JsonObject -> listOf("message", "detail", "type", "code")
            .mapNotNull { key -> error[key]?.jsonPrimitive?.contentOrNull }
            .firstOrNull(String::isNotBlank)
        is JsonPrimitive -> error.contentOrNull
        else -> null
    } ?: listOf("message", "detail", "msg")
        .mapNotNull { key -> root[key]?.jsonPrimitive?.contentOrNull }
        .firstOrNull(String::isNotBlank)
}.getOrNull()

class LocalModelException(
    val code: String,
    message: String,
    val retryable: Boolean,
    cause: Throwable? = null,
    val status: Int? = null,
    val providerRetryAfterMs: Long? = null,
    val requestId: String? = null,
    val providerCode: String? = null,
    val providerParam: String? = null,
) : Exception(message, cause)

internal fun contextWindowExceeded(error: Throwable): Boolean {
    val modelError = error as? LocalModelException ?: return false
    if (modelError.code !in setOf("MODEL_HTTP_400", "MODEL_HTTP_413", "MODEL_HTTP_422")) {
        return false
    }
    val detail = modelError.message.orEmpty().lowercase()
    return listOf(
        "context_length_exceeded",
        "context length",
        "context window",
        "maximum context",
        "max context",
        "too many tokens",
        "prompt is too long",
        "input is too long",
        "token limit",
        "上下文长度",
        "上下文窗口",
        "输入过长",
        "token 数量超过",
    ).any(detail::contains)
}

/** Model-facing tools mirroring the official Harness capability families on Android. */
object LocalToolCatalog {
    val specs: JsonArray = buildJsonArray {
        add(tool("read", "读取本机工作区内的文本文件", properties(
            "path" to string("相对工作区的路径"),
            "start_line" to integer("起始行，默认 1"),
            "end_line" to integer("结束行，默认读取 400 行"),
        ), listOf("path")))
        add(tool("tool_output_read", "按 UTF-8 字节区间读取此前因上下文预算而省略的完整工具结果", properties(
            "call_id" to string("原工具调用编号"),
            "start_byte" to integer("起始 UTF-8 字节偏移，默认 0；优先使用上次结果给出的下一偏移"),
            "max_bytes" to integer("单次读取字节数，默认 24576，允许 1024–49152"),
        ), listOf("call_id")))
        add(tool("write", "创建或完整替换工作区文件", properties(
            "path" to string("相对工作区的路径"),
            "content" to string("完整文件内容"),
        ), listOf("path", "content")))
        add(tool("edit", "唯一字面量替换；修改前必须先用 read 读取包含待替换内容的目标区域，write 创建或覆盖不算读取", properties(
            "path" to string("相对工作区的路径"),
            "old_text" to string("必须只出现一次的原文"),
            "new_text" to string("替换后的文字"),
        ), listOf("path", "old_text", "new_text")))
        add(tool("apply_patch", "应用标准 unified diff 补丁到工作区；先执行 git apply --check，成功后原子式应用", properties(
            "patch" to string("完整 unified diff 文本"),
        ), listOf("patch")))
        add(tool("file_inspect", "检查工作区文件元数据；图片返回宽高和可用的常见 EXIF，不解码整张图片", properties(
            "path" to string("相对工作区的文件路径"),
        ), listOf("path")))
        add(tool("list_files", "列出工作区目录", properties(
            "path" to string("相对路径，默认 ."),
            "depth" to integer("递归深度，1 到 8"),
        )))
        add(tool("glob", "按 glob 模式发现工作区文件", properties(
            "pattern" to string("例如 **/*.kt"),
            "path" to string("相对路径，默认 ."),
        ), listOf("pattern")))
        add(tool("grep", "在工作区文件中搜索文字；默认字面量，可指定正则表达式", properties(
            "query" to string("搜索内容；regex=true 时按正则表达式解析"),
            "path" to string("相对路径，默认 ."),
            "regex" to boolean("是否按正则表达式搜索，默认 false"),
        ), listOf("query")))
        add(tool("bash", "在应用工作区执行 Android 系统 shell", properties(
            "command" to string("shell 命令"),
            "timeout_seconds" to integer("进程执行超时秒数；前台默认 30/最大 120，后台默认 300/最大 900；会话切换等主动取消不受该值约束"),
            "run_in_background" to boolean("是否转为后台任务，默认 false；普通后台 shell 属当前会话非持久任务，创建、切换或删除会话时会取消"),
        ), listOf("command")))
        add(tool("job_list", "列出本机会话创建的后台任务", properties()))
        add(tool("job_output", "读取后台任务状态和输出", properties(
            "job_id" to string("后台任务编号"),
        ), listOf("job_id")))
        add(tool("job_kill", "停止一个后台任务", properties(
            "job_id" to string("后台任务编号"),
        ), listOf("job_id")))
        add(tool("web_search", "通过 DeepSeek 官方搜索能力查询最新网页信息", properties(
            "queries" to buildJsonObject {
                put("type", "array")
                put("description", "1 到 4 个搜索词")
                put("items", buildJsonObject { put("type", "string") })
            },
        ), listOf("queries")))
        add(tool("web_fetch", "通过 HTTPS 或 HTTP 获取网页内容；大响应会自动完整落盘并返回工作区路径", properties(
            "url" to string("完整网址"),
            "max_bytes" to integer("最多读取字节数，默认 4194304，最大 4194304"),
            "format" to buildJsonObject {
                put("type", "string")
                put("description", "text 提取可读文本，raw 保留原始响应；默认 text")
                put("enum", buildJsonArray { add(JsonPrimitive("text")); add(JsonPrimitive("raw")) })
            },
            "run_in_background" to boolean("是否转为后台抓取任务；后台模式使用更长网络时限，默认 false"),
        ), listOf("url")))
        add(tool("http_request", "向公网 HTTP/HTTPS API 发起受限请求；支持 GET/HEAD/POST/PUT/PATCH/DELETE；GET/HEAD 对瞬时传输失败自动最多重试 3 次并返回白名单限流响应头；认证类请求头禁止写入工具参数", properties(
            "method" to string("GET、HEAD、POST、PUT、PATCH 或 DELETE"),
            "url" to string("完整公网 HTTP/HTTPS 地址"),
            "headers" to buildJsonObject {
                put("type", "object")
                put("description", "可选；仅允许 Accept、Content-Type、If-None-Match、If-Modified-Since")
                put("additionalProperties", buildJsonObject { put("type", "string") })
            },
            "body" to string("可选；请求体，最大 1 MiB"),
            "max_bytes" to integer("最多读取响应字节数，最大 4194304"),
        ), listOf("method", "url")))
        add(tool("download_file", "把公网 HTTP/HTTPS 文件流式下载到工作区并计算 SHA-256", properties(
            "url" to string("完整公网 HTTP/HTTPS 地址"),
            "path" to string("工作区相对目标路径"),
            "max_bytes" to integer("最大下载字节数，默认 20971520，最高 104857600"),
        ), listOf("url", "path")))
        add(tool("json_query", "从工作区 JSON 文件读取一个字段/数组片段，无需 jq；支持 a.b[0].c 形式", properties(
            "path" to string("JSON 文件的工作区相对路径"),
            "query" to string("字段路径，例如 items[0].name；留空返回根节点摘要"),
        ), listOf("path")))
        add(tool("network_diagnose", "诊断域名解析、系统代理、VPN/TUN、安全策略并最多连续 3 次探测 HTTP/TLS 连通性；单次 EOF/超时不会直接判定为阻断", properties(
            "url" to string("要诊断的网址或域名"),
        ), listOf("url")))
        add(tool("environment_info", "查看安卓本机 Harness 的可用环境能力与限制", properties()))
        add(tool("capability_search", "按需发现并启用当前回合的扩展工具；需要 Android、视觉、运行时、GitHub、MCP、LSP、自动化或 Webhook 能力时先调用", properties(
            "query" to string("能力关键词，例如 GitHub PR、Android 界面、视觉、MCP、LSP、终端、自动化"),
        ), listOf("query")))
        add(tool("update_plan", "更新当前任务计划", properties(
            "items" to buildJsonObject {
                put("type", "array")
                put("description", "按执行顺序排列的计划项")
                put("items", buildJsonObject { put("type", "string") })
            },
        ), listOf("items")))
        add(tool("exit_plan_mode", "提交完整计划供用户审批；仅在规划模式中调用", properties(
            "plan" to string("完整、可直接执行的计划"),
        ), listOf("plan")))
        add(tool("todo_write", "替换当前实现任务清单", properties(
            "items" to buildJsonObject {
                put("type", "array")
                put("items", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("content", string("任务内容"))
                        put("status", buildJsonObject {
                            put("type", "string")
                            put("enum", buildJsonArray {
                                add(JsonPrimitive("pending")); add(JsonPrimitive("in_progress")); add(JsonPrimitive("completed"))
                            })
                        })
                    })
                    put("required", buildJsonArray { add(JsonPrimitive("content")); add(JsonPrimitive("status")) })
                    put("additionalProperties", false)
                })
            },
        ), listOf("items")))
        add(tool("create_goal", "创建或替换当前会话目标", properties(
            "description" to string("目标和完成标准"),
        ), listOf("description")))
        add(tool("get_goal", "读取当前会话目标", properties()))
        add(tool("update_goal", "更新目标状态", properties(
            "status" to string("active、paused、completed 或 blocked"),
            "note" to string("状态说明"),
        ), listOf("status")))
        add(tool("ask_user_question", "暂停当前轮次并向用户询问无法自行确定的选择", properties(
            "question" to string("清晰、可直接回答的问题"),
            "options" to buildJsonObject {
                put("type", "array")
                put("items", buildJsonObject { put("type", "string") })
            },
        ), listOf("question")))
        add(tool("skill", "列出技能，或读取指定技能的 SKILL.md", properties(
            "name" to string("可选；留空列出技能，填写后读取技能"),
        )))
        add(tool("subagent", "启动一个只读子代理处理独立子任务；同一工具块中的多个子代理可并行且互不级联取消", properties(
            "task" to string("交给子代理的完整任务"),
            "model" to string("可选；使用 list_subagent_models 返回的 profileId，或无歧义的模型名；留空继承父代理模型"),
            "max_steps" to integer("最大模型/工具循环步数，默认 20，可配置 1 到 128"),
            "virtual_screen" to boolean("是否为子代理分配独立虚拟屏；用于并行操作 Android 界面，默认 false"),
            "run_in_background" to boolean("是否转为后台任务，默认 false"),
        ), listOf("task")))
        add(tool("subagent_fork", "继承当前会话上下文并启动子代理", properties(
            "task" to string("需要结合当前上下文处理的任务"),
        ), listOf("task")))
        add(tool("list_subagent_models", "列出安卓本机子代理可使用的模型路由", properties()))
        add(tool("list_agents", "列出当前会话启动的后台代理", properties()))
        add(tool("send_message", "向正在运行的后台代理追加消息", properties(
            "agent_id" to string("后台代理编号"),
            "message" to string("需要追加的消息"),
        ), listOf("agent_id", "message")))
        add(tool("interrupt_agent", "停止正在运行的后台代理", properties(
            "agent_id" to string("后台代理编号"),
        ), listOf("agent_id")))
        add(tool("workflow", "并行执行只读子任务，或按顺序传递结果；逐项验收，失败最多重新指派一次", properties(
            "tasks" to buildJsonObject {
                put("type", "array")
                put("items", buildJsonObject { put("type", "string") })
            },
            "mode" to buildJsonObject {
                put("type", "string")
                put("description", "parallel 并行或 pipeline 顺序执行，默认 parallel")
                put("enum", buildJsonArray {
                    add(JsonPrimitive("parallel"))
                    add(JsonPrimitive("pipeline"))
                })
            },
            "required_evidence" to buildJsonObject {
                put("type", "array")
                put("description", "可选；与 tasks 逐项对应的产出中必须出现的可核对原文。不提供时仅检查产出非空。")
                put("items", buildJsonObject { put("type", "string") })
            },
        ), listOf("tasks")))
        add(tool("session_event_search", "分页搜索当前会话的追加式事件日志；单页结果受上下文安全上限约束", properties(
            "query" to string("搜索内容"),
            "session_id" to string("可选；留空使用当前会话"),
            "limit" to integer("单页最多返回条数，默认 50，最大 100"),
            "after_sequence" to integer("可选分页游标；继续上一页时传结果末尾提示的事件序号"),
        ), listOf("query")))
        add(tool("session_search", "跨本机历史会话搜索事件", properties(
            "query" to string("搜索内容"),
        ), listOf("query")))
        add(tool("memory_search", "搜索当前会话允许作用域内的长期记忆", properties(
            "query" to string("搜索内容"),
        ), listOf("query")))
        add(tool("memory_list", "列出当前会话允许查看的最近长期记忆，仅在用户明确要求查看记忆时使用", properties()))
        add(tool("memory_remember", "保存一条长期记忆；只用于明确长期规则、偏好、项目决定或用户明确要求记住的内容", properties(
            "content" to string("需要长期保存的精炼内容"),
            "scope" to buildJsonObject {
                put("type", "string")
                put("description", "global 全局、project 当前项目、lineage 当前连续任务")
                put("enum", buildJsonArray {
                    add(JsonPrimitive("global"))
                    add(JsonPrimitive("project"))
                    add(JsonPrimitive("lineage"))
                })
            },
            "kind" to buildJsonObject {
                put("type", "string")
                put("description", "rule / preference / fact / decision / constraint / state / summary")
                put("enum", buildJsonArray {
                    add(JsonPrimitive("rule"))
                    add(JsonPrimitive("preference"))
                    add(JsonPrimitive("fact"))
                    add(JsonPrimitive("decision"))
                    add(JsonPrimitive("constraint"))
                    add(JsonPrimitive("state"))
                    add(JsonPrimitive("summary"))
                })
            },
        ), listOf("content", "scope")))
        add(tool("memory_update", "按稳定记忆 id 更新当前作用域内的一条长期记忆；执行前需要用户批准", properties(
            "id" to string("memory_search 或 memory_list 返回的完整 id，也可使用唯一前缀"),
            "content" to string("可选；新的记忆内容"),
            "kind" to buildJsonObject {
                put("type", "string")
                put("enum", buildJsonArray {
                    add(JsonPrimitive("rule")); add(JsonPrimitive("preference")); add(JsonPrimitive("fact"))
                    add(JsonPrimitive("decision")); add(JsonPrimitive("constraint")); add(JsonPrimitive("state")); add(JsonPrimitive("summary"))
                })
            },
            "importance" to integer("可选；0 到 100"),
            "pinned" to boolean("可选；是否固定提高召回优先级"),
        ), listOf("id")))
        add(tool("memory_forget", "按稳定记忆 id 停用当前作用域内的一条长期记忆；执行前需要用户批准", properties(
            "id" to string("memory_search 或 memory_list 返回的完整 id，也可使用唯一前缀"),
        ), listOf("id")))
        add(tool("session_trace", "读取一个会话最近的事件轨迹", properties(
            "session_id" to string("可选；留空使用当前会话"),
            "limit" to integer("返回条数，默认 40，最大 200"),
        )))
        add(tool("session_event_trace", "读取指定会话事件及其直接邻近事件", properties(
            "session_id" to string("可选；留空使用当前会话"),
            "seq" to integer("事件序号"),
        ), listOf("seq")))
        add(tool("session_event_read", "按字符分页读取事件和可选的前后事件", properties(
            "session_id" to string("可选；留空使用当前会话"),
            "seq" to integer("事件序号"),
            "before" to integer("前置事件数，最多 20"),
            "after" to integer("后置事件数，最多 20"),
            "offset_chars" to integer("可选；按上一页提示继续读取的字符偏移，默认 0"),
        ), listOf("seq")))
        add(tool("present", "把工作区中的成果文件标记为最终交付物", properties(
            "path" to string("成果文件的相对路径"),
        ), listOf("path")))
    }

    private fun tool(name: String, description: String, parameters: JsonObject, required: List<String> = emptyList()) =
        buildJsonObject {
            put("type", "function")
            put("function", buildJsonObject {
                put("name", name)
                put("description", description)
                put("parameters", if (required.isEmpty()) parameters else JsonObject(parameters + (
                    "required" to JsonArray(required.map(::JsonPrimitive))
                    )))
            })
        }

    private fun properties(vararg entries: Pair<String, JsonObject>) = buildJsonObject {
        put("type", "object")
        put("properties", JsonObject(entries.toMap()))
        put("additionalProperties", false)
    }

    private fun string(description: String) = buildJsonObject {
        put("type", "string")
        put("description", description)
    }

    private fun integer(description: String) = buildJsonObject {
        put("type", "integer")
        put("description", description)
    }

    private fun boolean(description: String) = buildJsonObject {
        put("type", "boolean")
        put("description", description)
    }
}

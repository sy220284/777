package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.io.NetworkInputTooLargeException
import com.labteto.dshmobile.local.io.readBoundedLine

import com.labteto.dshmobile.local.model.DeepSeekTokenUsage
import com.labteto.dshmobile.local.model.LocalModelAdmissionState
import com.labteto.dshmobile.local.model.LocalModelAdmissionTracker
import com.labteto.dshmobile.local.model.LocalModelDelta
import com.labteto.dshmobile.local.model.LocalModelPresets
import com.labteto.dshmobile.local.model.LocalModelReply
import com.labteto.dshmobile.local.model.LocalModelToolCallingMode
import com.labteto.dshmobile.local.model.modelPostAdmissionFailure
import com.labteto.dshmobile.local.model.modelTransportFailure
import com.labteto.dshmobile.local.model.normalizeModelBaseUrl
import com.labteto.dshmobile.local.model.parseDeepSeekOpenAiUsage
import com.labteto.dshmobile.local.model.requireUniqueModelToolCallIds
import com.labteto.dshmobile.local.model.validatedModelToolCall
import com.labteto.dshmobile.local.model.withCancellableModelResponse
import com.labteto.dshmobile.local.model.withModelAdmissionTracking
import com.labteto.dshmobile.local.tools.LocalToolCatalog
import com.labteto.dshmobile.local.tools.long
import java.io.ByteArrayOutputStream
import java.net.SocketTimeoutException
import java.util.UUID
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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

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
        reasoningEffort: String? = null,
    ): LocalModelReply = withContext(Dispatchers.IO) {
        val requestId = UUID.randomUUID().toString()
        val promptBreakdown = estimatePromptBreakdown(messages, tools)
        val toolCallingMode = resolveToolCallingMode(model, baseUrl, tools)
        val payload = buildJsonObject {
            put("model", model)
            put("messages", JsonArray(messages))
            put("stream", false)
            temperature?.let { put("temperature", it) }
            if (reasoningEffort != null) {
                put("thinking", buildJsonObject {
                    put("type", if (reasoningEffort == "none") "disabled" else "enabled")
                })
                put("reasoning_effort", reasoningEffort)
            } else if (
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
        val admissionTracker = LocalModelAdmissionTracker()
        val request = Request.Builder()
            .url(endpoint(baseUrl))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody(JSON_MEDIA).withModelAdmissionTracking(admissionTracker))
            .build()
        try {
            withCancellableModelResponse(modelHttp.newCall(request), admissionTracker) { response ->
                if (!response.isSuccessful) {
                    val body = response.readModelBodyBounded()
                    val detail = providerErrorDetail(body, json)
                    throw LocalModelException(
                        code = "MODEL_HTTP_${response.code}",
                        message = "模型请求失败（HTTP ${response.code}）：${detail ?: body.take(500)}",
                        retryable = response.code == 408 || response.code == 429 || response.code >= 500,
                        status = response.code,
                        requestId = requestId,
                        admissionState = LocalModelAdmissionState.REJECTED,
                    )
                }
                admissionTracker.markAdmitted()
                val body = response.readModelBodyBounded()
                parse(body).copy(
                    requestId = requestId,
                    promptBreakdown = promptBreakdown,
                )
            }
        } catch (error: LocalModelException) {
            throw error
        } catch (error: SocketTimeoutException) {
            if (admissionTracker.snapshot() == LocalModelAdmissionState.ADMITTED) {
                throw modelPostAdmissionFailure(
                    code = "MODEL_RESPONSE_INTERRUPTED_AFTER_ADMISSION",
                    detail = "模型响应读取超时",
                    requestId = requestId,
                    cause = error,
                )
            }
            throw modelTransportFailure(
                code = "MODEL_TIMEOUT",
                detail = "模型推理超时：${error.message ?: "请求未在时限内完成"}",
                tracker = admissionTracker,
                requestId = requestId,
                cause = error,
            )
        } catch (error: java.io.IOException) {
            if (admissionTracker.snapshot() == LocalModelAdmissionState.ADMITTED) {
                throw modelPostAdmissionFailure(
                    code = "MODEL_RESPONSE_INTERRUPTED_AFTER_ADMISSION",
                    detail = "模型响应连接中断",
                    requestId = requestId,
                    cause = error,
                )
            }
            throw modelTransportFailure(
                code = "MODEL_NETWORK",
                detail = "模型网络请求失败：${error.message ?: "网络异常"}",
                tracker = admissionTracker,
                requestId = requestId,
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
        reasoningEffort: String? = null,
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
            if (reasoningEffort != null) {
                put("thinking", buildJsonObject {
                    put("type", if (reasoningEffort == "none") "disabled" else "enabled")
                })
                put("reasoning_effort", reasoningEffort)
            } else if (
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
        val admissionTracker = LocalModelAdmissionTracker()
        val request = Request.Builder()
            .url(endpoint(baseUrl))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody(JSON_MEDIA).withModelAdmissionTracking(admissionTracker))
            .build()
        try {
            withCancellableModelResponse(modelHttp.newCall(request), admissionTracker) { response ->
                if (!response.isSuccessful) {
                    val body = response.readModelBodyBounded()
                    val detail = providerErrorDetail(body, json)
                    throw LocalModelException(
                        code = "MODEL_HTTP_${response.code}",
                        message = "模型请求失败（HTTP ${response.code}）：${detail ?: body.take(500)}",
                        retryable = response.code == 408 || response.code == 429 || response.code >= 500,
                        status = response.code,
                        requestId = requestId,
                        admissionState = LocalModelAdmissionState.REJECTED,
                    )
                }
                admissionTracker.markAdmitted()
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
                        val line = try {
                            readBoundedLine(reader, MAX_SSE_LINE_CHARS)
                        } catch (failure: NetworkInputTooLargeException) {
                            throw LocalModelException(
                                code = "MODEL_RESPONSE_TOO_LARGE",
                                message = "模型流式响应单行超过安全上限",
                                retryable = false,
                            )
                        } ?: break
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
            if (admissionTracker.snapshot() == LocalModelAdmissionState.ADMITTED) {
                throw modelPostAdmissionFailure(
                    code = "MODEL_STREAM_INTERRUPTED_AFTER_ADMISSION",
                    detail = "模型流式响应超时",
                    requestId = requestId,
                    cause = error,
                )
            }
            throw modelTransportFailure(
                code = "MODEL_TIMEOUT",
                detail = "模型推理超时：${error.message ?: "请求未在时限内完成"}",
                tracker = admissionTracker,
                requestId = requestId,
                cause = error,
            )
        } catch (error: java.io.IOException) {
            if (admissionTracker.snapshot() == LocalModelAdmissionState.ADMITTED) {
                throw modelPostAdmissionFailure(
                    code = "MODEL_STREAM_INTERRUPTED_AFTER_ADMISSION",
                    detail = "模型流式连接中断",
                    requestId = requestId,
                    cause = error,
                )
            }
            throw modelTransportFailure(
                code = "MODEL_NETWORK",
                detail = "模型网络请求失败：${error.message ?: "网络异常"}",
                tracker = admissionTracker,
                requestId = requestId,
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
        private const val MAX_SSE_LINE_CHARS = 4 * 1024 * 1024
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

internal class LocalModelException(
    val code: String,
    message: String,
    val retryable: Boolean,
    cause: Throwable? = null,
    val status: Int? = null,
    val providerRetryAfterMs: Long? = null,
    val requestId: String? = null,
    val providerCode: String? = null,
    val providerParam: String? = null,
    val admissionState: LocalModelAdmissionState = LocalModelAdmissionState.NOT_APPLICABLE,
    val continuationEligible: Boolean = false,
) : Exception(message, cause)

internal fun contextWindowExceeded(error: Throwable): Boolean {
    val modelError = error as? LocalModelException ?: return false
    if (modelError.code == "MODEL_CONTEXT_BUDGET_EXCEEDED") return true
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

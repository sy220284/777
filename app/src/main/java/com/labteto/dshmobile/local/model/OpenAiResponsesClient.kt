package com.labteto.dshmobile.local.model

import com.labteto.dshmobile.local.DeepSeekTokenUsage
import com.labteto.dshmobile.local.LocalModelDelta
import com.labteto.dshmobile.local.LocalModelException
import com.labteto.dshmobile.local.LocalModelReply
import com.labteto.dshmobile.local.LocalPromptCacheDiagnostic
import com.labteto.dshmobile.local.LocalToolCall
import com.labteto.dshmobile.local.TokenPromptBreakdown
import com.labteto.dshmobile.local.estimatePromptBreakdown
import com.labteto.dshmobile.local.normalizeModelBaseUrl
import com.labteto.dshmobile.local.model.chatgpt.CHATGPT_RESPONSES_URL
import java.io.IOException
import java.net.SocketTimeoutException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

@Singleton
class OpenAiResponsesClient @Inject constructor(
    http: OkHttpClient,
    private val json: Json,
) {
    private val modelHttp = http.newBuilder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(60, TimeUnit.SECONDS)
        .callTimeout(6, TimeUnit.MINUTES)
        .build()

    suspend fun completeStreaming(
        accessToken: String,
        baseUrl: String,
        model: String,
        messages: List<JsonObject>,
        tools: JsonArray,
        temperature: Double? = null,
        planSharing: Boolean,
        promptCacheComparisonResponseId: String? = null,
        promptCacheKey: String? = null,
        promptCacheTtl: String? = null,
        onDelta: (LocalModelDelta) -> Unit = {},
    ): LocalModelReply = withContext(Dispatchers.IO) {
        val promptBreakdown = estimatePromptBreakdown(messages, tools)
        val openAiContract = usesOpenAiResponsesContract(baseUrl, planSharing)
        val payload = buildPayload(
            model = model,
            messages = messages,
            tools = tools,
            temperature = temperature,
            planSharing = planSharing,
            includeEncryptedReasoning = openAiContract,
            enforceOpenAiToolSchema = openAiContract,
            promptCacheComparisonResponseId = promptCacheComparisonResponseId,
            promptCacheKey = promptCacheKey,
            promptCacheTtl = promptCacheTtl,
        )
        validateRequestPayload(payload)
        val admissionTracker = LocalModelAdmissionTracker()
        val request = Request.Builder()
            .url(responsesEndpoint(baseUrl, planSharing))
            .header("Authorization", "Bearer $accessToken")
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody(JSON_MEDIA).withModelAdmissionTracking(admissionTracker))
            .build()
        var admittedRequestId: String? = null
        try {
            withCancellableModelResponse(modelHttp.newCall(request), admissionTracker) { response ->
                val requestId = response.header("x-request-id")
                    ?: response.header("openai-request-id")
                val retryAfterMs = parseRetryAfterMillis(response.header("Retry-After"))
                if (!response.isSuccessful) {
                    val body = response.readBoundedModelError(ERROR_BODY_LIMIT)
                    throw httpError(
                        status = response.code,
                        body = body,
                        planSharing = planSharing,
                        requestId = requestId,
                        retryAfterMs = retryAfterMs,
                    )
                }
                admissionTracker.markAdmitted()
                admittedRequestId = requestId
                val responseBody = response.body ?: throw streamInterruptedAfterAdmission(
                    planSharing = planSharing,
                    requestId = requestId,
                    detail = "Responses API 返回了空响应",
                )
                var completedResponse: JsonObject? = null
                val streamed = OpenAiResponsesStreamSnapshot()
                var totalBytes = 0
                // Response 生命周期由 withCancellableModelResponse 统一关闭；这里不要单独 use(reader)，
                // 否则成功终态后的 reader.close() 异常仍可能把成功请求翻成失败。
                val reader = responseBody.charStream().buffered()
                run {
                    while (true) {
                        val line = reader.readLine() ?: break
                        totalBytes += line.toByteArray(Charsets.UTF_8).size + 1
                        if (totalBytes > MAX_STREAM_BYTES) {
                            throw LocalModelException(
                                code = "MODEL_RESPONSE_TOO_LARGE",
                                message = "Responses API 流式响应超过本机安全上限",
                                retryable = false,
                            )
                        }
                        if (!line.startsWith("data:")) continue
                        val data = line.removePrefix("data:").trim()
                        if (data.isBlank() || data == "[DONE]") continue
                        val event = runCatching { json.parseToJsonElement(data).jsonObject }
                            .getOrElse { cause ->
                                throw streamInterruptedAfterAdmission(
                                    planSharing = planSharing,
                                    requestId = requestId,
                                    detail = "Responses API 返回了无法解析的数据帧",
                                    cause = cause,
                                )
                            }
                        streamed.record(event)?.let { onDelta(it) }
                        when (event["type"]?.jsonPrimitive?.contentOrNull) {
                            "error" -> throw streamError(event, planSharing, requestId, retryAfterMs)
                            "response.failed" -> throw responseFailure(
                                event = event,
                                planSharing = planSharing,
                                requestId = requestId,
                                retryAfterMs = retryAfterMs,
                            )
                            "response.incomplete" -> throw LocalModelException(
                                code = "RESPONSES_INCOMPLETE",
                                message = incompleteReason(event),
                                retryable = false,
                            )
                            "response.completed" -> {
                                completedResponse = event["response"] as? JsonObject
                                    ?: throw responseProtocolError("completed 事件缺少 response")
                                // completed 是成功终态；不要再读取 EOF，避免终态后的连接收尾异常触发整轮重试。
                                break
                            }
                        }
                    }
                }
                val completed = completedResponse ?: throw streamInterruptedAfterAdmission(
                    planSharing = planSharing,
                    requestId = requestId,
                    detail = "Responses API 流在 response.completed 前结束",
                )
                parseCompleted(
                    response = streamed.settledResponse(completed),
                    promptBreakdown = promptBreakdown,
                    streamedContent = streamed.content,
                    streamedReasoning = streamed.reasoningText,
                )
            }
        } catch (error: LocalModelException) {
            // In-stream failures can arrive after real inference has started on any Responses route.
            // Preserve diagnostics but never blindly replay the whole accepted request.
            if (admissionTracker.snapshot() == LocalModelAdmissionState.ADMITTED && error.retryable) {
                throw error.withoutReplayAfterAdmission()
            }
            throw error
        } catch (error: SocketTimeoutException) {
            if (admissionTracker.snapshot() == LocalModelAdmissionState.ADMITTED) {
                throw streamInterruptedAfterAdmission(
                    planSharing = planSharing,
                    requestId = admittedRequestId,
                    detail = "Responses API 流式响应超时",
                    cause = error,
                )
            }
            throw modelTransportFailure(
                code = "MODEL_TIMEOUT",
                detail = "Responses API 推理超时",
                tracker = admissionTracker,
                requestId = admittedRequestId,
                cause = error,
            )
        } catch (error: IOException) {
            if (admissionTracker.snapshot() == LocalModelAdmissionState.ADMITTED) {
                throw streamInterruptedAfterAdmission(
                    planSharing = planSharing,
                    requestId = admittedRequestId,
                    detail = "Responses API 流式连接中断",
                    cause = error,
                )
            }
            throw modelTransportFailure(
                code = "MODEL_NETWORK",
                detail = "Responses API 网络请求失败：${error.message ?: "网络异常"}",
                tracker = admissionTracker,
                requestId = admittedRequestId,
                cause = error,
            )
        }
    }

    internal fun validateRequestPayload(payload: JsonObject) {
        val input = payload["input"] as? JsonArray
        if (input.isNullOrEmpty()) {
            throw LocalModelException(
                code = "RESPONSES_INPUT_REQUIRED",
                message = "Responses API 请求缺少 input；后台任务必须提供至少一条实际输入，不能只发送 instructions。",
                retryable = false,
            )
        }
    }

    internal fun streamInterruptedAfterAdmission(
        planSharing: Boolean,
        requestId: String?,
        detail: String,
        cause: Throwable? = null,
    ): LocalModelException =
        if (planSharing) {
            LocalModelException(
                code = "CHATGPT_PLAN_STREAM_INTERRUPTED",
                message = "$detail。请求已经进入 Responses 流，为避免重复推理和重复套餐消耗，777 不会自动重放本轮。",
                retryable = false,
                cause = cause,
                requestId = requestId,
                providerCode = "stream_interrupted_after_admission",
                admissionState = LocalModelAdmissionState.ADMITTED,
                continuationEligible = true,
            )
        } else {
            modelPostAdmissionFailure(
                code = "RESPONSES_STREAM_INTERRUPTED_AFTER_ADMISSION",
                detail = detail,
                requestId = requestId,
                cause = cause,
            )
        }

    internal fun buildPayload(
        model: String,
        messages: List<JsonObject>,
        tools: JsonArray,
        temperature: Double?,
        planSharing: Boolean = true,
        includeEncryptedReasoning: Boolean = true,
        enforceOpenAiToolSchema: Boolean = planSharing,
        promptCacheComparisonResponseId: String? = null,
        promptCacheKey: String? = null,
        promptCacheTtl: String? = null,
    ): JsonObject = buildJsonObject {
        put("model", model)
        responseInstructions(messages).takeIf(String::isNotBlank)?.let { put("instructions", it) }
        put("input", responseInput(messages))
        put("store", false)
        put("stream", true)
        if (!planSharing) {
            promptCacheKey?.trim()?.takeIf(String::isNotBlank)?.let { key ->
                put("prompt_cache_key", key.take(MAX_PROMPT_CACHE_KEY_CHARS))
            }
        }
        val cacheComparison = promptCacheComparisonResponseId
            ?.trim()
            ?.takeIf(String::isNotBlank)
        val cacheTtl = promptCacheTtl
            ?.trim()
            ?.takeIf { !planSharing && it in SUPPORTED_PROMPT_CACHE_TTLS }
        if (cacheComparison != null || cacheTtl != null) {
            put("prompt_cache_options", buildJsonObject {
                cacheComparison?.let { put("comparison_response_id", it.take(512)) }
                cacheTtl?.let {
                    put("mode", "implicit")
                    put("ttl", it)
                }
            })
        }
        if (includeEncryptedReasoning) {
            put("include", buildJsonArray { add(JsonPrimitive("reasoning.encrypted_content")) })
        }
        if (tools.isNotEmpty()) {
            put("tools", responseTools(tools, planSharing, enforceOpenAiToolSchema))
        }
        if (!planSharing) temperature?.let { put("temperature", it) }
        // ChatGPT plan sharing rejects sampling controls; API-key Responses keeps its own contract.
    }

    private fun responseInstructions(messages: List<JsonObject>): String =
        messages.asSequence()
            .filter { it["role"]?.jsonPrimitive?.contentOrNull == "system" }
            .mapNotNull { message -> responseInstructionText(message["content"]) }
            .filter(String::isNotBlank)
            .joinToString("\n\n")

    private fun responseInstructionText(content: JsonElement?): String? =
        when (content) {
            is JsonPrimitive -> content.contentOrNull
            is JsonArray -> content.mapNotNull { part ->
                val obj = part as? JsonObject ?: return@mapNotNull null
                when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                    "text", "input_text" -> obj["text"]?.jsonPrimitive?.contentOrNull
                    else -> null
                }
            }.joinToString("\n").takeIf(String::isNotBlank)
            else -> null
        }

    private fun responseInput(messages: List<JsonObject>): JsonArray = buildJsonArray {
        messages.forEach { message ->
            val role = message["role"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val raw = message[RESPONSES_OUTPUT_KEY] as? JsonArray
            if (raw != null) {
                val canonicalText = (message["content"] as? JsonPrimitive)?.contentOrNull
                var replacedText = false
                raw.forEach { item ->
                    val obj = item as? JsonObject
                    if (canonicalText != null && obj?.get("type")?.jsonPrimitive?.contentOrNull == "message" &&
                        obj["role"]?.jsonPrimitive?.contentOrNull == "assistant"
                    ) {
                        if (!replacedText) {
                            add(JsonObject(obj + ("content" to buildJsonArray {
                                add(buildJsonObject { put("type", "output_text"); put("text", canonicalText) })
                            })))
                            replacedText = true
                        }
                    } else add(item)
                }
                val streamedFallback = (message["content"] as? JsonPrimitive)
                    ?.contentOrNull
                    ?.takeIf(String::isNotBlank)
                val rawHasFunctionCall = raw.any { item ->
                    (item as? JsonObject)?.get("type")?.jsonPrimitive?.contentOrNull == "function_call"
                }
                val rawHasAssistantText = raw.any { item ->
                    val obj = item as? JsonObject ?: return@any false
                    obj["type"]?.jsonPrimitive?.contentOrNull == "message" &&
                        responseMessageText((obj["content"] as? JsonArray) ?: JsonArray(emptyList())).isNotBlank()
                }
                if (
                    role != "assistant" ||
                    streamedFallback == null ||
                    rawHasFunctionCall ||
                    rawHasAssistantText
                ) {
                    return@forEach
                }
            }
            if (role == "tool") {
                val callId = message["tool_call_id"]?.jsonPrimitive?.contentOrNull
                    ?: return@forEach
                add(buildJsonObject {
                    put("type", "function_call_output")
                    put("call_id", callId)
                    put("output", message["content"]?.jsonPrimitive?.contentOrNull.orEmpty())
                })
                return@forEach
            }
            if (role == "system") return@forEach
            if (role !in setOf("developer", "user", "assistant")) return@forEach
            val calls = if (role == "assistant") message["tool_calls"] as? JsonArray else null
            if (calls.isNullOrEmpty() || message["content"] !in listOf(null, JsonNull)) add(buildJsonObject {
                put("role", role)
                val content = message["content"]
                when (content) {
                    is JsonArray -> put("content", responseContent(content))
                    is JsonPrimitive -> put("content", content)
                    JsonNull, null -> put("content", "")
                    else -> put("content", content.toString())
                }
            })
            calls.orEmpty().forEach { element ->
                val call = element as? JsonObject ?: error("工具调用历史格式无效")
                val function = call["function"] as? JsonObject ?: error("工具调用历史缺少 function")
                add(buildJsonObject {
                    put("type", "function_call")
                    put("call_id", call["id"]?.jsonPrimitive?.contentOrNull
                        ?.takeIf(String::isNotBlank) ?: error("工具调用历史缺少 call_id"))
                    put("name", function["name"]?.jsonPrimitive?.contentOrNull
                        ?.takeIf(String::isNotBlank) ?: error("工具调用历史缺少 name"))
                    put("arguments", function["arguments"]?.jsonPrimitive?.contentOrNull ?: "{}")
                })
            }
        }
    }

    private fun responseContent(content: JsonArray): JsonArray = buildJsonArray {
        content.forEach { part ->
            val obj = part as? JsonObject ?: return@forEach
            when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                "text" -> add(buildJsonObject {
                    put("type", "input_text")
                    put("text", obj["text"]?.jsonPrimitive?.contentOrNull.orEmpty())
                })
                "image_url" -> {
                    val source = obj["image_url"]
                    val url = when (source) {
                        is JsonPrimitive -> source.contentOrNull
                        is JsonObject -> source["url"]?.jsonPrimitive?.contentOrNull
                        else -> null
                    }
                    if (!url.isNullOrBlank()) add(buildJsonObject {
                        put("type", "input_image")
                        put("image_url", url)
                        if (source is JsonObject) source["detail"]?.let { put("detail", it) }
                    })
                }
                "input_text", "input_image" -> add(obj)
                else -> throw responseProtocolError("不支持的输入内容类型：${obj["type"]}")
            }
        }
    }

    private fun responseTools(
        tools: JsonArray,
        planSharing: Boolean,
        enforceOpenAiToolSchema: Boolean,
    ): JsonArray = OpenAiResponsesToolAdapter.adapt(
        tools = tools,
        planSharing = planSharing,
        enforceOpenAiToolSchema = enforceOpenAiToolSchema,
    )

    internal fun responseMessageText(parts: JsonArray): String = buildString {
        parts.forEach { part ->
            val p = part as? JsonObject ?: return@forEach
            when (p["type"]?.jsonPrimitive?.contentOrNull) {
                "output_text" -> append(p["text"]?.jsonPrimitive?.contentOrNull.orEmpty())
                "refusal" -> append(p["refusal"]?.jsonPrimitive?.contentOrNull.orEmpty())
            }
        }
    }

    internal fun parseCompleted(
        response: JsonObject,
        promptBreakdown: TokenPromptBreakdown,
        streamedContent: String = "",
        streamedReasoning: String = "",
    ): LocalModelReply {
        val output = response["output"] as? JsonArray ?: JsonArray(emptyList())
        val content = StringBuilder()
        val reasoning = StringBuilder()
        val toolCalls = mutableListOf<LocalToolCall>()
        output.forEach { item ->
            val obj = item as? JsonObject ?: return@forEach
            when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                "message" -> {
                    content.append(responseMessageText((obj["content"] as? JsonArray) ?: JsonArray(emptyList())))
                }
                "reasoning" -> {
                    (obj["summary"] as? JsonArray).orEmpty().forEach { part ->
                        val p = part as? JsonObject ?: return@forEach
                        p["text"]?.jsonPrimitive?.contentOrNull?.let(reasoning::append)
                    }
                }
                "function_call" -> {
                    val callId = (obj["call_id"] as? JsonPrimitive)?.contentOrNull
                        ?.takeIf(String::isNotBlank)
                        ?: throw responseProtocolError("工具调用缺少 call_id")
                    val name = (obj["name"] as? JsonPrimitive)?.contentOrNull
                        ?.takeIf(String::isNotBlank)
                        ?: throw responseProtocolError("工具调用缺少 name")
                    val rawArguments = (obj["arguments"] as? JsonPrimitive)?.contentOrNull
                        ?: throw responseProtocolError("工具调用缺少 arguments")
                    val arguments = runCatching {
                        json.parseToJsonElement(rawArguments) as? JsonObject
                    }.getOrNull() ?: throw responseProtocolError("工具参数不是合法 JSON 对象")
                    toolCalls += LocalToolCall(callId, name, arguments, rawArguments)
                }
            }
        }
        val settledContent = content.toString().takeIf(String::isNotBlank)
            ?: streamedContent.takeIf(String::isNotBlank)
        val settledReasoning = reasoning.toString().takeIf(String::isNotBlank)
            ?: streamedReasoning.takeIf(String::isNotBlank)
        val message = buildJsonObject {
            put("role", "assistant")
            if (settledContent != null) put("content", settledContent) else put("content", JsonNull)
            if (toolCalls.isNotEmpty()) {
                put("tool_calls", buildJsonArray {
                    toolCalls.forEach { call ->
                        add(buildJsonObject {
                            put("id", call.id)
                            put("type", "function")
                            put("function", buildJsonObject {
                                put("name", call.name)
                                put("arguments", call.rawArguments)
                            })
                        })
                    }
                })
            }
            put(RESPONSES_OUTPUT_KEY, output)
        }
        return LocalModelReply(
            message = message,
            content = settledContent,
            reasoning = settledReasoning,
            toolCalls = toolCalls,
            usage = parseUsage(response["usage"] as? JsonObject),
            requestId = response["id"]?.jsonPrimitive?.contentOrNull ?: UUID.randomUUID().toString(),
            promptBreakdown = promptBreakdown,
            promptCacheDiagnostic = parsePromptCacheDiagnostic(
                response["prompt_cache_diagnostics"] as? JsonObject,
            ),
        ).also(::validateUsableModelReply)
    }

    private fun parsePromptCacheDiagnostic(value: JsonObject?): LocalPromptCacheDiagnostic? {
        value ?: return null
        val type = value["type"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
            ?: return null
        return LocalPromptCacheDiagnostic(
            type = type,
            reason = value["reason"]?.jsonPrimitive?.contentOrNull,
            comparisonReusableTokens = value["comparison_reusable_tokens"]?.jsonPrimitive?.longOrNull,
            cacheMissedTokens = value["cache_missed_tokens"]?.jsonPrimitive?.longOrNull,
        )
    }

    private fun parseUsage(usage: JsonObject?): DeepSeekTokenUsage {
        if (usage == null) return DeepSeekTokenUsage(reported = false)
        val input = usage["input_tokens"]?.jsonPrimitive?.longOrNull ?: 0L
        val output = usage["output_tokens"]?.jsonPrimitive?.longOrNull ?: 0L
        val inputDetails = usage["input_tokens_details"] as? JsonObject
        val cached = inputDetails
            ?.get("cached_tokens")?.jsonPrimitive?.longOrNull ?: 0L
        val cacheWrite = inputDetails
            ?.get("cache_write_tokens")?.jsonPrimitive?.longOrNull ?: 0L
        val reasoning = (usage["output_tokens_details"] as? JsonObject)
            ?.get("reasoning_tokens")?.jsonPrimitive?.longOrNull ?: 0L
        return DeepSeekTokenUsage(
            promptTokens = input.coerceAtLeast(0L),
            cacheHitTokens = cached.coerceAtLeast(0L),
            cacheMissTokens = (input - cached).coerceAtLeast(0L),
            cacheWriteTokens = cacheWrite.coerceAtLeast(0L)
                .coerceAtMost((input - cached).coerceAtLeast(0L)),
            completionTokens = output.coerceAtLeast(0L),
            reasoningTokens = reasoning.coerceAtLeast(0L),
            reported = true,
        )
    }

    private fun responseFailure(
        event: JsonObject,
        planSharing: Boolean,
        requestId: String?,
        retryAfterMs: Long?,
    ): LocalModelException {
        val response = event["response"] as? JsonObject
        val error = response?.get("error") as? JsonObject
        val code = error?.get("code")?.jsonPrimitive?.contentOrNull ?: "unknown_error"
        val param = error?.get("param")?.jsonPrimitive?.contentOrNull
        val message = error?.get("message")?.jsonPrimitive?.contentOrNull
            ?: "Responses API 请求失败"
        return if (planSharing) {
            structuredResponseError(code, param, message, requestId, retryAfterMs)
        } else {
            standardStreamError("RESPONSES_FAILED", code, param, message, requestId, retryAfterMs)
        }
    }

    internal fun streamError(
        event: JsonObject,
        planSharing: Boolean,
        requestId: String?,
        retryAfterMs: Long?,
    ): LocalModelException {
        val nested = event["error"] as? JsonObject
        val code = event["code"]?.jsonPrimitive?.contentOrNull
            ?: nested?.get("code")?.jsonPrimitive?.contentOrNull
            ?: "unknown_error"
        val param = event["param"]?.jsonPrimitive?.contentOrNull
            ?: nested?.get("param")?.jsonPrimitive?.contentOrNull
        val detail = event["message"]?.jsonPrimitive?.contentOrNull
            ?: nested?.get("message")?.jsonPrimitive?.contentOrNull
            ?: "Responses API 流式请求失败"
        return if (planSharing) {
            structuredResponseError(code, param, detail, requestId, retryAfterMs)
        } else {
            standardStreamError("RESPONSES_STREAM", code, param, detail, requestId, retryAfterMs)
        }
    }

    private fun standardStreamError(
        prefix: String,
        code: String,
        param: String?,
        detail: String,
        requestId: String?,
        retryAfterMs: Long?,
    ): LocalModelException = LocalModelException(
        code = "${prefix}_$code",
        message = detail,
        retryable = code in setOf("server_error", "server_overloaded", "rate_limit_exceeded"),
        providerRetryAfterMs = retryAfterMs,
        requestId = requestId,
        providerCode = code,
        providerParam = param,
    )

    private fun incompleteReason(event: JsonObject): String {
        val response = event["response"] as? JsonObject
        val details = response?.get("incomplete_details") as? JsonObject
        val reason = details?.get("reason")?.jsonPrimitive?.contentOrNull
        return if (reason.isNullOrBlank()) "Responses API 未完整完成本次请求" else "Responses API 未完整完成：$reason"
    }

    internal fun httpError(
        status: Int,
        body: String,
        planSharing: Boolean,
        requestId: String? = null,
        retryAfterMs: Long? = null,
    ): LocalModelException {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
        val error = root?.get("error") as? JsonObject
        val remoteCode = error?.get("code")?.jsonPrimitive?.contentOrNull
        val remoteParam = error?.get("param")?.jsonPrimitive?.contentOrNull
        val detail = error?.get("message")?.jsonPrimitive?.contentOrNull
            ?: root?.get("detail")?.jsonPrimitive?.contentOrNull
            ?: root?.get("message")?.jsonPrimitive?.contentOrNull

        if (planSharing && remoteCode != null) {
            return structuredResponseError(
                code = remoteCode,
                param = remoteParam,
                detail = detail,
                requestId = requestId,
                retryAfterMs = retryAfterMs,
                statusOverride = status,
                admissionState = LocalModelAdmissionState.REJECTED,
            )
        }

        if (planSharing) {
            val message = when (status) {
                401 -> "ChatGPT 套餐请求未通过身份或授权校验。请确认当前账户仍已授权套餐使用；若持续出现，再重新连接账户。"
                403 -> "ChatGPT 套餐请求被权限、策略或可用地区规则拒绝。请检查 ChatGPT 账户与当前网络环境。"
                503 -> "ChatGPT 套餐直连服务暂时不可用，777 会按有限次数退避重试。"
                else -> detail ?: "Responses API 请求失败（HTTP $status）"
            }
            return LocalModelException(
                code = when (status) {
                    401 -> "CHATGPT_PLAN_ADMISSION_401"
                    403 -> "CHATGPT_PLAN_ADMISSION_403"
                    503 -> "CHATGPT_PLAN_ROUTE_UNAVAILABLE"
                    else -> "MODEL_HTTP_$status"
                },
                message = detail?.takeIf(String::isNotBlank) ?: message,
                retryable = status == 408 || status == 429 || status >= 500,
                status = status,
                providerRetryAfterMs = retryAfterMs,
                requestId = requestId,
                admissionState = LocalModelAdmissionState.REJECTED,
            )
        }

        return LocalModelException(
            code = remoteCode?.let { "RESPONSES_HTTP_$it" }
                ?: if (status == 429) "MODEL_HTTP_429" else "MODEL_HTTP_$status",
            message = detail ?: "Responses API 请求失败（HTTP $status）",
            retryable = status == 408 || status == 429 || status >= 500,
            status = status,
            providerRetryAfterMs = retryAfterMs,
            requestId = requestId,
            providerCode = remoteCode,
            providerParam = remoteParam,
            admissionState = LocalModelAdmissionState.REJECTED,
        )
    }

    private fun structuredResponseError(
        code: String,
        param: String?,
        detail: String?,
        requestId: String?,
        retryAfterMs: Long?,
        statusOverride: Int? = null,
        admissionState: LocalModelAdmissionState = LocalModelAdmissionState.ADMITTED,
    ): LocalModelException {
        val knownStatus = statusOverride ?: when (code) {
            "subscription_sharing_user_not_eligible",
            "subscription_sharing_route_not_supported",
            "chatpass_v2_scope_not_authorized",
            "chatpass_v2_invalid_authorization_context" -> 403
            "subscription_sharing_usage_limit_exceeded" -> 429
            "subscription_sharing_usage_unavailable",
            "subscription_sharing_user_unavailable" -> 503
            "subscription_sharing_unsupported_capability" -> 400
            "subscription_sharing_invalid_user" -> 401
            else -> null
        }
        val mapped = when (code) {
            "subscription_sharing_usage_limit_exceeded" -> Triple(
                "CHATGPT_PLAN_LIMIT_REACHED",
                "ChatGPT 套餐用量请求达到当前限制。你的套餐总额度可能仍有剩余，也可能是此应用的单独限制；请在 ChatGPT「用量」中查看实际限制。",
                false,
            )
            "subscription_sharing_usage_unavailable",
            "subscription_sharing_user_unavailable" -> Triple(
                "CHATGPT_PLAN_USAGE_UNAVAILABLE",
                "ChatGPT 暂时无法确认套餐可用量，777 已保留登录状态。",
                true,
            )
            "subscription_sharing_user_not_eligible" -> Triple(
                "CHATGPT_PLAN_USER_NOT_ELIGIBLE",
                "当前 ChatGPT 用户、工作区或策略暂不允许共享套餐用量。",
                false,
            )
            "subscription_sharing_unsupported_capability" -> Triple(
                "CHATGPT_PLAN_UNSUPPORTED_CAPABILITY",
                buildString {
                    append("当前 ChatGPT 套餐共享请求包含暂不支持的能力")
                    if (!param.isNullOrBlank()) append("：").append(param)
                    append("。请调整模型请求后再试。")
                },
                false,
            )
            "subscription_sharing_route_not_supported" -> Triple(
                "CHATGPT_PLAN_ROUTE_NOT_SUPPORTED",
                "当前请求路由不支持 ChatGPT 套餐共享，请检查 Responses API 调用路径。",
                false,
            )
            "subscription_sharing_invalid_user" -> Triple(
                "CHATGPT_PLAN_INVALID_USER",
                "ChatGPT 订阅者上下文未通过验证。若该问题持续出现，请重新连接账户。",
                false,
            )
            "chatpass_v2_scope_not_authorized",
            "chatpass_v2_invalid_authorization_context" -> Triple(
                "CHATGPT_PLAN_PERMISSION_CONTEXT_INVALID",
                "当前 ChatGPT 授权上下文不允许这次套餐调用，请重新检查账户授权。",
                false,
            )
            else -> Triple(
                "RESPONSES_FAILED_$code",
                detail ?: "Responses API 请求失败",
                code in setOf("server_error", "server_overloaded", "rate_limit_exceeded"),
            )
        }
        return LocalModelException(
            code = mapped.first,
            message = mapped.second,
            retryable = mapped.third,
            status = knownStatus,
            providerRetryAfterMs = retryAfterMs,
            requestId = requestId,
            providerCode = code,
            providerParam = param,
            admissionState = admissionState,
        )
    }

    internal fun responsesEndpoint(baseUrl: String, planSharing: Boolean): String {
        if (planSharing) return CHATGPT_RESPONSES_URL
        val clean = normalizeModelBaseUrl(baseUrl).trimEnd('/')
        return if (clean.endsWith("/responses")) clean else "$clean/responses"
    }

    internal fun shouldIncludeEncryptedReasoning(baseUrl: String, planSharing: Boolean): Boolean =
        usesOpenAiResponsesContract(baseUrl, planSharing)

    internal fun usesOpenAiResponsesContract(baseUrl: String, planSharing: Boolean): Boolean {
        if (planSharing) return true
        return runCatching {
            java.net.URI(normalizeModelBaseUrl(baseUrl)).host.equals("api.openai.com", ignoreCase = true)
        }.getOrDefault(false)
    }

    internal fun networkFailure(error: IOException): LocalModelException {
        val detail = error.message.orEmpty().lowercase()
        val interrupted = listOf(
            "connection abort",
            "connection reset",
            "broken pipe",
            "socket closed",
            "stream was reset",
            "stream reset",
        ).any(detail::contains)
        return LocalModelException(
            code = "MODEL_NETWORK",
            message = if (interrupted) {
                "Responses API 流式连接中断，请检查当前网络后恢复请求。"
            } else {
                "Responses API 网络连接失败，请检查当前网络后重试。"
            },
            retryable = true,
            cause = error,
        )
    }

    internal fun parseRetryAfterMillis(value: String?, nowMillis: Long = System.currentTimeMillis()): Long? {
        val raw = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
        raw.toLongOrNull()?.let { seconds ->
            return seconds.coerceAtLeast(0L) * 1_000L
        }
        return runCatching {
            val atMillis = ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME)
                .toInstant()
                .toEpochMilli()
            (atMillis - nowMillis).coerceAtLeast(0L)
        }.getOrNull()
    }

    private fun responseProtocolError(detail: String) = LocalModelException(
        code = "RESPONSES_PROTOCOL_ERROR",
        message = "Responses API 协议错误：$detail",
        retryable = false,
    )

    companion object {
        const val RESPONSES_OUTPUT_KEY = "_dsh_responses_output"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private const val MAX_STREAM_BYTES = 32 * 1024 * 1024
        private const val ERROR_BODY_LIMIT = 8_000
        private const val MAX_PROMPT_CACHE_KEY_CHARS = 64
        private val SUPPORTED_PROMPT_CACHE_TTLS = setOf("30m")
    }
}

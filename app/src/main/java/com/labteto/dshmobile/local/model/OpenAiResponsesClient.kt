package com.labteto.dshmobile.local.model

import com.labteto.dshmobile.local.DeepSeekTokenUsage
import com.labteto.dshmobile.local.LocalModelDelta
import com.labteto.dshmobile.local.LocalModelException
import com.labteto.dshmobile.local.LocalModelReply
import com.labteto.dshmobile.local.LocalToolCall
import com.labteto.dshmobile.local.TokenPromptBreakdown
import com.labteto.dshmobile.local.estimatePromptBreakdown
import com.labteto.dshmobile.local.model.chatgpt.CHATGPT_RESPONSES_URL
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
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
        model: String,
        messages: List<JsonObject>,
        tools: JsonArray,
        temperature: Double? = null,
        onDelta: (LocalModelDelta) -> Unit = {},
    ): LocalModelReply = withContext(Dispatchers.IO) {
        val promptBreakdown = estimatePromptBreakdown(messages, tools)
        val payload = buildPayload(model, messages, tools, temperature)
        val request = Request.Builder()
            .url(CHATGPT_RESPONSES_URL)
            .header("Authorization", "Bearer $accessToken")
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .build()
        try {
            runInterruptible { modelHttp.newCall(request).execute() }.use { response ->
                if (!response.isSuccessful) {
                    val body = response.body?.string().orEmpty().take(ERROR_BODY_LIMIT)
                    throw httpError(response.code, body)
                }
                val responseBody = response.body ?: throw LocalModelException(
                    code = "RESPONSES_STREAM_INCOMPLETE",
                    message = "Responses API 返回了空响应",
                    retryable = true,
                )
                var completedResponse: JsonObject? = null
                var totalBytes = 0
                responseBody.charStream().buffered().use { reader ->
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
                                throw LocalModelException(
                                    code = "RESPONSES_PROTOCOL_ERROR",
                                    message = "Responses API 返回了无法解析的数据帧",
                                    retryable = true,
                                    cause = cause,
                                )
                            }
                        when (event["type"]?.jsonPrimitive?.contentOrNull) {
                            "response.output_text.delta" -> {
                                event["delta"]?.jsonPrimitive?.contentOrNull
                                    ?.takeIf(String::isNotEmpty)
                                    ?.let { onDelta(LocalModelDelta(content = it)) }
                            }
                            "response.reasoning_text.delta" -> {
                                event["delta"]?.jsonPrimitive?.contentOrNull
                                    ?.takeIf(String::isNotEmpty)
                                    ?.let { onDelta(LocalModelDelta(reasoning = it)) }
                            }
                            "response.failed" -> throw responseFailure(event)
                            "response.incomplete" -> throw LocalModelException(
                                code = "RESPONSES_INCOMPLETE",
                                message = incompleteReason(event),
                                retryable = false,
                            )
                            "response.completed" -> {
                                completedResponse = event["response"] as? JsonObject
                                    ?: error("Responses API completed 事件缺少 response")
                            }
                        }
                    }
                }
                val completed = completedResponse ?: throw LocalModelException(
                    code = "RESPONSES_STREAM_INCOMPLETE",
                    message = "Responses API 流在 response.completed 前结束",
                    retryable = true,
                )
                parseCompleted(completed, promptBreakdown)
            }
        } catch (error: LocalModelException) {
            throw error
        } catch (error: SocketTimeoutException) {
            throw LocalModelException(
                code = "MODEL_TIMEOUT",
                message = "Responses API 推理超时",
                retryable = true,
                cause = error,
            )
        } catch (error: IOException) {
            throw LocalModelException(
                code = "MODEL_NETWORK",
                message = "Responses API 网络请求失败：${error.message ?: "网络异常"}",
                retryable = true,
                cause = error,
            )
        }
    }

    internal fun buildPayload(
        model: String,
        messages: List<JsonObject>,
        tools: JsonArray,
        @Suppress("UNUSED_PARAMETER") temperature: Double?,
    ): JsonObject = buildJsonObject {
        put("model", model)
        responseInstructions(messages).takeIf(String::isNotBlank)?.let { put("instructions", it) }
        put("input", responseInput(messages))
        put("store", false)
        put("stream", true)
        put("include", buildJsonArray { add(JsonPrimitive("reasoning.encrypted_content")) })
        if (tools.isNotEmpty()) put("tools", responseTools(tools))
        // ChatGPT plan sharing currently rejects temperature/top_p and other sampling controls.
        // Keep the parameter on the gateway contract for API-key transports, but never forward it here.
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
            val raw = message[RESPONSES_OUTPUT_KEY] as? JsonArray
            if (raw != null) {
                raw.forEach(::add)
                return@forEach
            }
            val role = message["role"]?.jsonPrimitive?.contentOrNull.orEmpty()
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
            add(buildJsonObject {
                put("role", role)
                val content = message["content"]
                when (content) {
                    is JsonArray -> put("content", responseContent(content))
                    is JsonPrimitive -> put("content", content)
                    JsonNull, null -> put("content", "")
                    else -> put("content", content.toString())
                }
            })
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
                    })
                }
                "input_text", "input_image" -> add(obj)
            }
        }
    }

    private fun responseTools(tools: JsonArray): JsonArray = buildJsonArray {
        tools.forEach { element ->
            val source = element as? JsonObject ?: return@forEach
            val function = source["function"] as? JsonObject ?: return@forEach
            val name = function["name"]?.jsonPrimitive?.contentOrNull ?: return@forEach
            add(buildJsonObject {
                put("type", "function")
                put("name", name)
                function["description"]?.let { put("description", it) }
                function["parameters"]?.let { put("parameters", it) }
            })
        }
    }

    private fun parseCompleted(
        response: JsonObject,
        promptBreakdown: TokenPromptBreakdown,
    ): LocalModelReply {
        val output = response["output"] as? JsonArray ?: JsonArray(emptyList())
        val content = StringBuilder()
        val reasoning = StringBuilder()
        val toolCalls = mutableListOf<LocalToolCall>()
        output.forEach { item ->
            val obj = item as? JsonObject ?: return@forEach
            when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                "message" -> {
                    (obj["content"] as? JsonArray).orEmpty().forEach { part ->
                        val p = part as? JsonObject ?: return@forEach
                        if (p["type"]?.jsonPrimitive?.contentOrNull == "output_text") {
                            content.append(p["text"]?.jsonPrimitive?.contentOrNull.orEmpty())
                        }
                    }
                }
                "reasoning" -> {
                    (obj["summary"] as? JsonArray).orEmpty().forEach { part ->
                        val p = part as? JsonObject ?: return@forEach
                        p["text"]?.jsonPrimitive?.contentOrNull?.let(reasoning::append)
                    }
                }
                "function_call" -> {
                    val callId = obj["call_id"]?.jsonPrimitive?.contentOrNull
                        ?: obj["id"]?.jsonPrimitive?.contentOrNull
                        ?: UUID.randomUUID().toString()
                    val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                    val rawArguments = obj["arguments"]?.jsonPrimitive?.contentOrNull ?: "{}"
                    val arguments = runCatching {
                        json.parseToJsonElement(rawArguments) as? JsonObject
                    }.getOrNull() ?: JsonObject(emptyMap())
                    toolCalls += LocalToolCall(callId, name, arguments, rawArguments)
                }
            }
        }
        val message = buildJsonObject {
            put("role", "assistant")
            if (content.isNotEmpty()) put("content", content.toString()) else put("content", JsonNull)
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
            content = content.toString().takeIf(String::isNotBlank),
            reasoning = reasoning.toString().takeIf(String::isNotBlank),
            toolCalls = toolCalls,
            usage = parseUsage(response["usage"] as? JsonObject),
            requestId = response["id"]?.jsonPrimitive?.contentOrNull ?: UUID.randomUUID().toString(),
            promptBreakdown = promptBreakdown,
        )
    }

    private fun parseUsage(usage: JsonObject?): DeepSeekTokenUsage {
        if (usage == null) return DeepSeekTokenUsage(reported = false)
        val input = usage["input_tokens"]?.jsonPrimitive?.longOrNull ?: 0L
        val output = usage["output_tokens"]?.jsonPrimitive?.longOrNull ?: 0L
        val cached = (usage["input_tokens_details"] as? JsonObject)
            ?.get("cached_tokens")?.jsonPrimitive?.longOrNull ?: 0L
        val reasoning = (usage["output_tokens_details"] as? JsonObject)
            ?.get("reasoning_tokens")?.jsonPrimitive?.longOrNull ?: 0L
        return DeepSeekTokenUsage(
            promptTokens = input.coerceAtLeast(0L),
            cacheHitTokens = cached.coerceAtLeast(0L),
            cacheMissTokens = (input - cached).coerceAtLeast(0L),
            completionTokens = output.coerceAtLeast(0L),
            reasoningTokens = reasoning.coerceAtLeast(0L),
            reported = true,
        )
    }

    private fun responseFailure(event: JsonObject): LocalModelException {
        val response = event["response"] as? JsonObject
        val error = response?.get("error") as? JsonObject
        val code = error?.get("code")?.jsonPrimitive?.contentOrNull ?: "unknown_error"
        val message = error?.get("message")?.jsonPrimitive?.contentOrNull
            ?: "Responses API 请求失败"
        return when (code) {
            "subscription_sharing_usage_limit_exceeded" -> LocalModelException(
                "CHATGPT_PLAN_LIMIT_REACHED",
                "ChatGPT 套餐用量已达到当前上限，请在 ChatGPT 中管理应用用量。",
                false,
            )
            "subscription_sharing_usage_unavailable" -> LocalModelException(
                "CHATGPT_PLAN_USAGE_UNAVAILABLE",
                "ChatGPT 套餐用量当前不可用，请检查账户权限或稍后重试。",
                false,
            )
            else -> LocalModelException(
                code = "RESPONSES_FAILED_$code",
                message = message,
                retryable = code in setOf("server_error", "rate_limit_exceeded"),
            )
        }
    }

    private fun incompleteReason(event: JsonObject): String {
        val response = event["response"] as? JsonObject
        val details = response?.get("incomplete_details") as? JsonObject
        val reason = details?.get("reason")?.jsonPrimitive?.contentOrNull
        return if (reason.isNullOrBlank()) "Responses API 未完整完成本次请求" else "Responses API 未完整完成：$reason"
    }

    private fun httpError(status: Int, body: String): LocalModelException {
        val detail = runCatching {
            val root = json.parseToJsonElement(body).jsonObject
            val error = root["error"]
            when (error) {
                is JsonObject -> error["message"]?.jsonPrimitive?.contentOrNull
                is JsonPrimitive -> error.contentOrNull
                else -> null
            }
        }.getOrNull()
        val code = when (status) {
            401, 403 -> "CHATGPT_AUTH_REVOKED"
            429 -> "MODEL_HTTP_429"
            else -> "MODEL_HTTP_$status"
        }
        return LocalModelException(
            code = code,
            message = detail ?: "Responses API 请求失败（HTTP $status）",
            retryable = status == 408 || status == 429 || status >= 500,
        )
    }

    companion object {
        const val RESPONSES_OUTPUT_KEY = "_dsh_responses_output"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private const val MAX_STREAM_BYTES = 32 * 1024 * 1024
        private const val ERROR_BODY_LIMIT = 8_000
    }
}

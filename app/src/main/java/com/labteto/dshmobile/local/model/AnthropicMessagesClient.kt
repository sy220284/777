package com.labteto.dshmobile.local.model

import com.labteto.dshmobile.local.DeepSeekTokenUsage
import com.labteto.dshmobile.local.LocalModelDelta
import com.labteto.dshmobile.local.LocalModelException
import com.labteto.dshmobile.local.LocalModelReply
import com.labteto.dshmobile.local.LocalToolCall
import com.labteto.dshmobile.local.normalizeModelBaseUrl
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
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
        apiKey: String,
        baseUrl: String,
        model: String,
        messages: List<JsonObject>,
        tools: JsonArray,
        temperature: Double?,
        onDelta: (LocalModelDelta) -> Unit = {},
    ): LocalModelReply = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank()) { "当前模型 API Key 为空" }
        require(model.isNotBlank()) { "当前模型名称为空" }
        val payload = buildPayload(model, messages, tools, temperature)
        val request = Request.Builder()
            .url(endpoint(baseUrl))
            .header("x-api-key", apiKey)
            .header("anthropic-version", ANTHROPIC_VERSION)
            .header("content-type", "application/json")
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .build()
        try {
            runInterruptible { client.newCall(request).execute() }.use { response ->
                val body = response.readBounded()
                if (!response.isSuccessful) throw httpFailure(response.code, body)
                parse(body).also { reply ->
                    val content = reply.content.orEmpty()
                    val reasoning = reply.reasoning.orEmpty()
                    if (content.isNotEmpty() || reasoning.isNotEmpty()) {
                        onDelta(LocalModelDelta(content = content, reasoning = reasoning))
                    }
                }
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
        model: String,
        messages: List<JsonObject>,
        tools: JsonArray,
        temperature: Double?,
    ): JsonObject {
        val systemParts = mutableListOf<String>()
        val converted = buildJsonArray {
            messages.forEach { message ->
                val role = message["role"]?.jsonPrimitive?.contentOrNull.orEmpty()
                when (role) {
                    "system", "developer" -> extractText(message["content"])
                        .takeIf(String::isNotBlank)
                        ?.let(systemParts::add)
                    "user" -> add(buildJsonObject {
                        put("role", "user")
                        put("content", convertContent(message["content"], forAssistant = false))
                    })
                    "assistant" -> add(buildJsonObject {
                        put("role", "assistant")
                        put("content", buildJsonArray {
                            appendTextBlocks(message["content"])
                            val calls = message["tool_calls"] as? JsonArray
                            calls.orEmpty().forEach { raw ->
                                val call = raw as? JsonObject ?: return@forEach
                                val function = call["function"] as? JsonObject ?: return@forEach
                                val id = call["id"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                                val name = function["name"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                                val arguments = function["arguments"]?.jsonPrimitive?.contentOrNull.orEmpty()
                                val input = runCatching { json.parseToJsonElement(arguments).jsonObject }
                                    .getOrElse { JsonObject(emptyMap()) }
                                add(buildJsonObject {
                                    put("type", "tool_use")
                                    put("id", id)
                                    put("name", name)
                                    put("input", input)
                                })
                            }
                        })
                    })
                    "tool" -> {
                        val callId = message["tool_call_id"]?.jsonPrimitive?.contentOrNull
                            ?: return@forEach
                        add(buildJsonObject {
                            put("role", "user")
                            put("content", buildJsonArray {
                                add(buildJsonObject {
                                    put("type", "tool_result")
                                    put("tool_use_id", callId)
                                    put("content", extractText(message["content"]))
                                })
                            })
                        })
                    }
                }
            }
        }
        return buildJsonObject {
            put("model", model)
            put("max_tokens", DEFAULT_MAX_TOKENS)
            if (systemParts.isNotEmpty()) put("system", systemParts.joinToString("\n\n"))
            put("messages", converted)
            if (tools.isNotEmpty()) put("tools", convertTools(tools))
            temperature?.let { put("temperature", it) }
        }
    }

    internal fun parse(body: String): LocalModelReply {
        val root = json.parseToJsonElement(body).jsonObject
        val content = root["content"]?.jsonArray.orEmpty()
        val text = StringBuilder()
        val reasoning = StringBuilder()
        val toolCalls = mutableListOf<LocalToolCall>()
        content.forEach { element ->
            val block = element as? JsonObject ?: return@forEach
            when (block["type"]?.jsonPrimitive?.contentOrNull) {
                "text" -> block["text"]?.jsonPrimitive?.contentOrNull?.let(text::append)
                "thinking" -> block["thinking"]?.jsonPrimitive?.contentOrNull?.let(reasoning::append)
                "tool_use" -> {
                    val id = block["id"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                    val name = block["name"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                    val input = block["input"] as? JsonObject ?: JsonObject(emptyMap())
                    toolCalls += LocalToolCall(id, name, input, input.toString())
                }
            }
        }
        val message = buildJsonObject {
            put("role", "assistant")
            put("content", text.toString())
            if (reasoning.isNotEmpty()) put("reasoning_content", reasoning.toString())
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
        }
        val usageObject = root["usage"] as? JsonObject
        val inputTokens = usageObject?.get("input_tokens")?.jsonPrimitive?.longOrNull ?: 0L
        val outputTokens = usageObject?.get("output_tokens")?.jsonPrimitive?.longOrNull ?: 0L
        return LocalModelReply(
            message = message,
            content = text.toString().ifBlank { null },
            reasoning = reasoning.toString().ifBlank { null },
            toolCalls = toolCalls,
            usage = DeepSeekTokenUsage(
                promptTokens = inputTokens,
                cacheMissTokens = inputTokens,
                completionTokens = outputTokens,
                reported = usageObject != null,
            ),
            requestId = root["id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        )
    }

    private fun convertTools(tools: JsonArray): JsonArray = buildJsonArray {
        tools.forEach { raw ->
            val source = raw as? JsonObject ?: return@forEach
            val function = source["function"] as? JsonObject ?: source
            val name = function["name"]?.jsonPrimitive?.contentOrNull ?: return@forEach
            val description = function["description"]?.jsonPrimitive?.contentOrNull
                ?.takeIf(String::isNotBlank) ?: "本机工具 $name"
            val parameters = function["parameters"] as? JsonObject ?: buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {})
            }
            add(buildJsonObject {
                put("name", name)
                put("description", description)
                put("input_schema", parameters)
            })
        }
    }

    private fun convertContent(content: JsonElement?, forAssistant: Boolean): JsonArray = buildJsonArray {
        if (content == null || content is JsonNull) return@buildJsonArray
        when (content) {
            is JsonPrimitive -> if (content.isString && content.content.isNotBlank()) {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", content.content)
                })
            }
            is JsonArray -> content.forEach { part ->
                val obj = part as? JsonObject ?: return@forEach
                when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                    "text", "input_text", "output_text" -> {
                        val value = obj["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        if (value.isNotBlank()) add(buildJsonObject {
                            put("type", "text")
                            put("text", value)
                        })
                    }
                    "image_url", "input_image" -> if (!forAssistant) {
                        val url = when (val raw = obj["image_url"]) {
                            is JsonPrimitive -> raw.contentOrNull
                            is JsonObject -> raw["url"]?.jsonPrimitive?.contentOrNull
                            else -> null
                        }
                        url?.let(::parseDataImage)?.let { image ->
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
                }
            }
            else -> Unit
        }
    }

    private fun JsonArrayBuilder.appendTextBlocks(content: JsonElement?) {
        convertContent(content, forAssistant = true).forEach(::add)
    }

    private fun extractText(content: JsonElement?): String = when (content) {
        is JsonPrimitive -> content.contentOrNull.orEmpty()
        is JsonArray -> content.mapNotNull { part ->
            val obj = part as? JsonObject ?: return@mapNotNull null
            obj["text"]?.jsonPrimitive?.contentOrNull
        }.joinToString("\n")
        else -> ""
    }

    private fun parseDataImage(value: String): Pair<String, String>? {
        if (!value.startsWith("data:image/")) return null
        val marker = ";base64,"
        val split = value.indexOf(marker)
        if (split <= 5) return null
        val mediaType = value.substring(5, split)
        val data = value.substring(split + marker.length)
        return mediaType.takeIf(String::isNotBlank)?.let { it to data }
    }

    private fun endpoint(baseUrl: String): String {
        val clean = normalizeModelBaseUrl(baseUrl).trimEnd('/')
        return if (clean.endsWith("/messages")) clean else "$clean/messages"
    }

    private fun httpFailure(status: Int, body: String): LocalModelException {
        val detail = runCatching {
            val root = json.parseToJsonElement(body).jsonObject
            val error = root["error"] as? JsonObject
            error?.get("message")?.jsonPrimitive?.contentOrNull
        }.getOrNull()
        return LocalModelException(
            code = "MODEL_HTTP_$status",
            message = "Anthropic 模型请求失败（HTTP $status）：${detail ?: body.take(500)}",
            retryable = status == 408 || status == 429 || status >= 500,
        )
    }

    private fun Response.readBounded(maxBytes: Int = MAX_RESPONSE_BYTES): String {
        val responseBody = body ?: return ""
        val declared = responseBody.contentLength()
        if (declared > maxBytes) {
            throw LocalModelException(
                code = "MODEL_RESPONSE_TOO_LARGE",
                message = "Anthropic 模型响应超过 $maxBytes 字节上限",
                retryable = false,
            )
        }
        val bytes = responseBody.byteStream().use { input ->
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
                        message = "Anthropic 模型响应超过 $maxBytes 字节上限",
                        retryable = false,
                    )
                }
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        }
        return bytes.toString(Charsets.UTF_8)
    }

    private companion object {
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        const val ANTHROPIC_VERSION = "2023-06-01"
        const val DEFAULT_MAX_TOKENS = 8192
        const val CONNECT_TIMEOUT_SECONDS = 15L
        const val READ_TIMEOUT_SECONDS = 180L
        const val WRITE_TIMEOUT_SECONDS = 90L
        const val CALL_TIMEOUT_SECONDS = 210L
        const val MAX_RESPONSE_BYTES = 4 * 1024 * 1024
    }
}

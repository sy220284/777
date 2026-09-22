package com.labteto.dshmobile.local

import java.io.ByteArrayOutputStream
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * OpenAI-compatible multimodal transport used only by explicit vision tools.
 *
 * The primary text agent remains unchanged. Screenshots are sent here only after a model tool call,
 * keeping image bytes out of the text model's durable conversation history.
 */
@Singleton
class VisionClient @Inject constructor(
    private val http: OkHttpClient,
    private val json: Json,
) : LocalVisionAnalyzer {
    private val client = http.newBuilder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    override suspend fun analyze(
        apiKey: String,
        route: LocalVisionRoute,
        prompt: String,
        imageDataUrl: String,
    ): String = analyze(
        apiKey = apiKey,
        baseUrl = route.baseUrl,
        model = route.model,
        prompt = prompt,
        imageDataUrl = imageDataUrl,
    )

    suspend fun analyze(
        apiKey: String,
        baseUrl: String,
        model: String,
        prompt: String,
        imageDataUrl: String,
    ): String = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank()) { "视觉模型密钥为空" }
        require(model.isNotBlank()) { "视觉模型名称为空" }
        require(prompt.isNotBlank()) { "视觉分析要求不能为空" }
        validateImageDataUrl(imageDataUrl)

        val payload = buildPayload(model, prompt, imageDataUrl)
        val request = Request.Builder()
            .url(endpoint(baseUrl))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .build()
        try {
            runInterruptible { client.newCall(request).execute() }.use { response ->
                val body = response.readBounded()
                if (!response.isSuccessful) {
                    val detail = runCatching {
                        json.parseToJsonElement(body).jsonObject["error"]?.jsonObject
                            ?.get("message")?.jsonPrimitive?.content
                    }.getOrNull()
                    throw LocalModelException(
                        code = "VISION_HTTP_${response.code}",
                        message = "视觉模型请求失败（HTTP ${response.code}）：${detail ?: body.take(500)}",
                        retryable = response.code == 408 || response.code == 429 || response.code >= 500,
                    )
                }
                parse(body)
            }
        } catch (error: LocalModelException) {
            throw error
        } catch (error: SocketTimeoutException) {
            throw LocalModelException(
                code = "VISION_TIMEOUT",
                message = "视觉模型推理超时：${error.message ?: "请求未在时限内完成"}",
                retryable = true,
                cause = error,
            )
        } catch (error: java.io.IOException) {
            throw LocalModelException(
                code = "VISION_NETWORK",
                message = "视觉模型网络请求失败：${error.message ?: "网络异常"}",
                retryable = true,
                cause = error,
            )
        }
    }

    internal fun buildPayload(
        model: String,
        prompt: String,
        imageDataUrl: String,
    ): JsonObject = buildJsonObject {
        put("model", model)
        put("stream", false)
        put("messages", buildJsonArray {
            add(buildJsonObject {
                put("role", "system")
                put(
                    "content",
                    "你是 Android 界面视觉分析器。只描述画面中可见事实；需要坐标时按原图像素坐标给出。若无法确定，明确说明不确定。",
                )
            })
            add(buildJsonObject {
                put("role", "user")
                put("content", buildJsonArray {
                    add(buildJsonObject {
                        put("type", "text")
                        put("text", prompt)
                    })
                    add(buildJsonObject {
                        put("type", "image_url")
                        put("image_url", buildJsonObject {
                            put("url", imageDataUrl)
                            put("detail", "high")
                        })
                    })
                })
            })
        })
    }

    internal fun parse(body: String): String {
        val root = json.parseToJsonElement(body).jsonObject
        val content = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("message")?.jsonObject
            ?.get("content")
            ?: error("视觉模型响应缺少 choices[0].message.content")
        return when (content) {
            is JsonPrimitive -> content.contentOrNull.orEmpty()
            is JsonArray -> content.mapNotNull { part ->
                val obj = part as? JsonObject ?: return@mapNotNull null
                val type = obj["type"]?.jsonPrimitive?.contentOrNull
                if (type == "text" || type == "output_text") {
                    obj["text"]?.jsonPrimitive?.contentOrNull
                } else {
                    null
                }
            }.joinToString("\n")
            else -> ""
        }.trim().ifBlank { error("视觉模型返回了空文本") }
    }

    private fun endpoint(baseUrl: String): String {
        val clean = normalizeModelBaseUrl(baseUrl).trimEnd('/')
        return if (clean.endsWith("/chat/completions")) clean else "$clean/chat/completions"
    }

    private fun validateImageDataUrl(value: String) {
        require(value.startsWith("data:image/")) { "视觉输入必须是图片 data URL" }
        require(";base64," in value.take(128)) { "视觉输入必须使用 base64 data URL" }
        require(value.length <= MAX_IMAGE_DATA_URL_CHARS) { "视觉输入超过大小上限" }
    }

    private fun Response.readBounded(maxBytes: Int = MAX_RESPONSE_BYTES): String {
        val responseBody = body ?: return ""
        val declared = responseBody.contentLength()
        if (declared > maxBytes) {
            throw LocalModelException(
                code = "VISION_RESPONSE_TOO_LARGE",
                message = "视觉模型响应超过 ${maxBytes} 字节上限",
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
                        code = "VISION_RESPONSE_TOO_LARGE",
                        message = "视觉模型响应超过 ${maxBytes} 字节上限",
                        retryable = false,
                    )
                }
                output.write(buffer, 0, read)
            }
        }
        return output.toString(Charsets.UTF_8.name())
    }

    private companion object {
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        const val CONNECT_TIMEOUT_SECONDS = 15L
        const val READ_TIMEOUT_SECONDS = 180L
        const val WRITE_TIMEOUT_SECONDS = 90L
        const val CALL_TIMEOUT_SECONDS = 210L
        const val MAX_RESPONSE_BYTES = 4 * 1024 * 1024
        const val MAX_IMAGE_DATA_URL_CHARS = 24 * 1024 * 1024
    }
}

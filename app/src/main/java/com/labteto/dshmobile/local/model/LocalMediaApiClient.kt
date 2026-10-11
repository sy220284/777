package com.labteto.dshmobile.local.model

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.labteto.dshmobile.core.net.withCancellableHttpResponse
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody

internal data class LocalMediaImageResult(val url: String? = null, val file: File? = null) {
    init { require((url != null) != (file != null)) }
}

/** Provider-specific wire formats, behind one typed media operation contract. */
@Singleton
internal class LocalMediaApiClient @Inject constructor(
    private val credentials: LocalModelCredentialResolver,
    private val http: OkHttpClient,
    private val json: Json,
    @ApplicationContext private val context: Context,
) {
    suspend fun image(offer: LocalMediaOffer, prompt: String, ratio: String): LocalMediaImageResult {
        require(offer.kind == LocalMediaKind.IMAGE && prompt.isNotBlank())
        require(ratio in setOf("1:1", "16:9", "9:16", "4:3", "3:4"))
        return when (offer.provider) {
            LocalMediaProvider.OPENAI -> {
                val size = when (ratio) {
                    "1:1" -> "1024x1024"
                    "16:9" -> "1536x864"
                    "9:16" -> "864x1536"
                    "4:3" -> "1536x1152"
                    else -> "1152x1536"
                }
                val root = sendJson(offer, "https://api.openai.com/v1/images/generations", buildJsonObject {
                    put("model", offer.model)
                    put("prompt", prompt)
                    put("size", size)
                    put("n", 1)
                })
                val item = root["data"]?.jsonArray?.firstOrNull()?.jsonObject
                    ?: error("OpenAI 未返回生成图片")
                val encoded = item["b64_json"]?.jsonPrimitive?.contentOrNull
                    ?: error("OpenAI 未返回图片字节")
                LocalMediaImageResult(file = saveEncodedImage(encoded))
            }
            LocalMediaProvider.QWEN -> {
                val size = when (ratio) {
                    "1:1" -> if (offer.model == "qwen-image-plus") "1328x1328" else "1024x1024"
                    "16:9" -> if (offer.model == "qwen-image-plus") "1664x928" else "1536x864"
                    "9:16" -> if (offer.model == "qwen-image-plus") "928x1664" else "864x1536"
                    "4:3" -> if (offer.model == "qwen-image-plus") "1472x1104" else "1536x1152"
                    else -> if (offer.model == "qwen-image-plus") "1104x1472" else "1152x1536"
                }
                val base = java.net.URI(normalizeModelBaseUrl(offer.profile.baseUrl))
                val url = "https://${base.host}/compatible-mode/v1/images/generations"
                val root = sendJson(offer, url, buildJsonObject {
                    put("model", offer.model)
                    put("prompt", prompt)
                    put("size", size)
                    put("n", 1)
                })
                val item = root["data"]?.jsonArray?.firstOrNull()?.jsonObject
                    ?: error("Qwen 未返回生成图片")
                val link = item["url"]?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.startsWith("https://") }
                    ?: error("Qwen 未返回可访问的图片链接")
                LocalMediaImageResult(url = link)
            }
            LocalMediaProvider.GEMINI -> {
                val root = sendJson(
                    offer,
                    "https://generativelanguage.googleapis.com/v1beta/models/${offer.model}:generateContent",
                    buildJsonObject {
                        put("contents", buildJsonArray {
                            add(buildJsonObject {
                                put("parts", buildJsonArray {
                                    add(buildJsonObject { put("text", prompt) })
                                })
                            })
                        })
                        put("generationConfig", buildJsonObject {
                            put("responseModalities", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("IMAGE")) })
                            put("responseFormat", buildJsonObject {
                                put("image", buildJsonObject { put("aspectRatio", ratio) })
                            })
                        })
                    },
                )
                val parts = root["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
                    ?.get("content")?.jsonObject?.get("parts")?.jsonArray
                    ?: error("Gemini 未返回图片")
                val inline = parts.firstNotNullOfOrNull { part ->
                    (part as? JsonObject)?.get("inlineData")?.jsonObject
                        ?.get("data")?.jsonPrimitive?.contentOrNull
                } ?: error("Gemini 未返回图片数据")
                LocalMediaImageResult(file = saveEncodedImage(inline))
            }
            LocalMediaProvider.MINIMAX -> error("MiniMax 图片由官方原生适配器处理")
        }
    }

    suspend fun speech(offer: LocalMediaOffer, input: String, voice: String): File {
        require(offer.provider == LocalMediaProvider.OPENAI && offer.kind == LocalMediaKind.SPEECH)
        require(input.isNotBlank() && input.length <= 4096) { "OpenAI 单次配音输入最多 4096 字符" }
        require(voice.isNotBlank()) { "音色不能为空" }
        val body = buildJsonObject {
            put("model", offer.model)
            put("input", input)
            put("voice", voice)
            put("response_format", "mp3")
        }
        val request = authorizedRequest(offer, "https://api.openai.com/v1/audio/speech")
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        return withContext(Dispatchers.IO) {
            withCancellableHttpResponse(http.newCall(request)) { response ->
                if (!response.isSuccessful) error("OpenAI 配音请求失败（HTTP ${response.code}）")
                val file = outputFile("mp3")
                try {
                    response.body?.byteStream()?.use { inputStream ->
                        file.outputStream().use { output -> inputStream.copyTo(output) }
                    } ?: error("OpenAI 未返回配音文件")
                    check(file.length() > 0) { "OpenAI 返回空配音文件" }
                    file
                } catch (error: Throwable) {
                    file.delete()
                    throw error
                }
            }
        }
    }

    suspend fun transcription(offer: LocalMediaOffer, uri: Uri): String {
        require(offer.provider == LocalMediaProvider.OPENAI && offer.kind == LocalMediaKind.TRANSCRIBE)
        val source = withContext(Dispatchers.IO) {
            val target = File.createTempFile("media-asr-", ".upload", context.cacheDir)
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { output ->
                        val chunk = ByteArray(8192)
                        var totalBytes = 0L
                        while (true) {
                            val size = input.read(chunk)
                            if (size < 0) break
                            totalBytes += size
                            require(totalBytes <= 25_000_000L) {
                                "OpenAI 单次录音转写最多支持 25 MB，请先压缩或拆分音频"
                            }
                            output.write(chunk, 0, size)
                        }
                    }
                } ?: error("无法读取录音文件")
                require(target.length() > 0) { "录音文件为空" }
                target
            } catch (e: Throwable) {
                target.delete()
                throw e
            }
        }
        try {
            val mime = context.contentResolver.getType(uri)?.takeIf { it.startsWith("audio/") }
                ?: "audio/mpeg"
            val ext = when (mime.substringAfter('/').substringBefore(';')) {
                "wav", "x-wav" -> "wav"
                "mp4", "x-m4a" -> "m4a"
                "ogg" -> "ogg"
                "webm" -> "webm"
                "flac", "x-flac" -> "flac"
                else -> "mp3"
            }
            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("model", offer.model)
                .addFormDataPart("response_format", "json")
                .addFormDataPart("file", "recording.$ext", source.asRequestBody(mime.toMediaType()))
                .build()
            val root = sendJson(offer, "https://api.openai.com/v1/audio/transcriptions", body)
            return root["text"]?.jsonPrimitive?.contentOrNull
                ?: error("OpenAI 未返回转写文字")
        } finally {
            source.delete()
        }
    }

    private suspend fun sendJson(offer: LocalMediaOffer, url: String, body: JsonObject): JsonObject =
        sendJson(offer, url, body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))

    private suspend fun sendJson(offer: LocalMediaOffer, url: String, body: RequestBody): JsonObject {
        val request = authorizedRequest(offer, url).post(body).build()
        return withContext(Dispatchers.IO) {
            withCancellableHttpResponse(http.newCall(request)) { response ->
                if (!response.isSuccessful) error("创作服务请求失败（HTTP ${response.code}）")
                val payload = response.body?.string() ?: error("创作服务未返回数据")
                json.parseToJsonElement(payload).jsonObject
            }
        }
    }

    private suspend fun authorizedRequest(offer: LocalMediaOffer, url: String): Request.Builder {
        LocalMediaCatalog.requireValid(offer)
        val key = credentials.resolve(offer.profile.model, offer.profile.baseUrl, offer.profile).bearerToken
        return Request.Builder().url(url)
            .header(
                if (offer.provider == LocalMediaProvider.GEMINI) "x-goog-api-key" else "Authorization",
                if (offer.provider == LocalMediaProvider.GEMINI) key else "Bearer $key",
            )
    }

    private fun outputFile(extension: String): File =
        File(File(context.cacheDir, "minimax-media").apply { mkdirs() },
            "created-${UUID.randomUUID()}.$extension")

    private suspend fun saveEncodedImage(encoded: String): File = withContext(Dispatchers.IO) {
        val file = outputFile("png")
        try {
            file.writeBytes(Base64.decode(encoded, Base64.DEFAULT))
            check(file.length() > 0) { "生成图片为空" }
            file
        } catch (error: Throwable) {
            file.delete()
            throw error
        }
    }
}

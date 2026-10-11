package com.labteto.dshmobile.local.model

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import com.labteto.dshmobile.core.net.withCancellableHttpResponse
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
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

/** Official endpoints can return a business error body even when HTTP transport succeeded. */
internal object LocalMediaApiResponse {
    fun checked(root: JsonObject): JsonObject {
        val error = root["error"] as? JsonObject
        val nestedCode = error?.get("code")?.jsonPrimitive?.contentOrNull
        val code = root["code"]?.jsonPrimitive?.contentOrNull ?: nestedCode
        val reason = error?.get("message")?.jsonPrimitive?.contentOrNull
            ?: root["message"]?.jsonPrimitive?.contentOrNull
        if (error != null || code != null) {
            val label = code?.take(80)?.let { " ($it)" }.orEmpty()
            throw IllegalStateException("创作服务返回错误$label：${reason?.take(320) ?: "请检查模型和账户权限"}")
        }
        return root
    }

    data class AudioUploadFormat(val extension: String, val contentType: String)

    /**
     * SAF providers may expose the original audio as application/octet-stream; use its real
     * extension first, or a documented MIME mapping. Do not disguise unsupported audio as MP3.
     */
    fun audioUploadFormat(filename: String?, mime: String?): AudioUploadFormat {
        val suffix = filename?.substringAfterLast('.', "")?.lowercase()
        val fromMime = when (mime?.lowercase()?.substringBefore(';')) {
            "audio/mpeg", "audio/mp3", "audio/x-mp3", "audio/mpeg3" -> "mp3"
            "audio/mp4", "audio/x-m4a", "audio/m4a" -> "m4a"
            "audio/x-wav", "audio/wav", "audio/wave" -> "wav"
            "audio/ogg", "application/ogg" -> "ogg"
            "audio/webm", "video/webm" -> "webm"
            "audio/flac", "audio/x-flac" -> "flac"
            else -> null
        }
        val ext = suffix?.takeIf {
            it in setOf("flac", "mp3", "mp4", "mpeg", "mpga", "m4a", "ogg", "wav", "webm")
        } ?: fromMime ?: error("不支持这种录音格式。请选用 MP3、M4A、WAV、FLAC、OGG 或 WEBM 文件")
        val contentType = when (ext) {
            "mp3", "mpga", "mpeg" -> "audio/mpeg"
            "mp4", "m4a" -> "audio/mp4"
            "wav" -> "audio/wav"
            "ogg" -> "audio/ogg"
            "webm" -> "audio/webm"
            "flac" -> "audio/flac"
            else -> error("未识别的录音格式")
        }
        return AudioUploadFormat(ext, contentType)
    }

    fun imageExtension(mime: String): String = when (mime.lowercase()) {
        "image/png" -> "png"
        "image/jpeg", "image/jpg" -> "jpg"
        "image/webp" -> "webp"
        else -> error("生成服务返回了不支持的图片格式：${mime.take(80)}")
    }
}

internal data class LocalMediaVideoWireStatus(val state: String, val downloadUrl: String? = null)

internal object QwenWanTaskResult {
    fun parse(root: JsonObject): LocalMediaVideoWireStatus {
        val output = root["output"] as? JsonObject ?: error("Wan 查询响应缺少任务信息")
        return when (output["task_status"]?.jsonPrimitive?.contentOrNull) {
            "PENDING" -> LocalMediaVideoWireStatus("Queueing")
            "RUNNING" -> LocalMediaVideoWireStatus("Processing")
            "FAILED", "CANCELED" -> LocalMediaVideoWireStatus("Failed")
            "SUCCEEDED" -> {
                val url = output["video_url"]?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.startsWith("https://") }
                    ?: error("Wan 视频已完成但没有可用的下载链接")
                LocalMediaVideoWireStatus("Success", url)
            }
            else -> error("Wan 视频任务状态未知或任务已过查询有效期")
        }
    }
}


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
    // Media providers may spend minutes generating an image or transcoding audio.
    // An idle-read timeout remains, but never use the app-wide 30s short-request policy.
    private val mediaHttp = http.newBuilder()
        .readTimeout(10, TimeUnit.MINUTES)
        .writeTimeout(2, TimeUnit.MINUTES)
        .callTimeout(0, TimeUnit.SECONDS)
        .build()

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
                    "1:1" -> "1024x1024"
                    "16:9" -> "1536x864"
                    "9:16" -> "864x1536"
                    "4:3" -> "1536x1152"
                    else -> "1152x1536"
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
                    (part as? JsonObject)?.get("inlineData") as? JsonObject
                } ?: error("Gemini 未返回图片数据")
                val encoded = inline["data"]?.jsonPrimitive?.contentOrNull
                    ?: error("Gemini 未返回图片字节")
                val extension = LocalMediaApiResponse.imageExtension(
                    inline["mimeType"]?.jsonPrimitive?.contentOrNull
                        ?: error("Gemini 图片响应缺少 MIME 类型")
                )
                LocalMediaImageResult(file = saveEncodedImage(encoded, extension))
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
            withCancellableHttpResponse(mediaHttp.newCall(request)) { response ->
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
            val filename = context.contentResolver.query(
                uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
            val format = LocalMediaApiResponse.audioUploadFormat(
                filename, context.contentResolver.getType(uri)
            )
            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("model", offer.model)
                .addFormDataPart("response_format", "json")
                .addFormDataPart(
                    "file", "recording.${format.extension}",
                    source.asRequestBody(format.contentType.toMediaType())
                )
                .build()
            val root = sendJson(offer, "https://api.openai.com/v1/audio/transcriptions", body)
            return root["text"]?.jsonPrimitive?.contentOrNull
                ?: error("OpenAI 未返回转写文字")
        } finally {
            source.delete()
        }
    }

    /** DashScope Wan async tasks have their own state and timeout, never use MiniMax polling URLs. */
    suspend fun startVideo(offer: LocalMediaOffer, prompt: String): String {
        require(offer.provider == LocalMediaProvider.QWEN && offer.kind == LocalMediaKind.VIDEO)
        require(prompt.isNotBlank()) { "请填写视频描述" }
        val uri = java.net.URI(normalizeModelBaseUrl(offer.profile.baseUrl))
        val url = "https://${uri.host}/api/v1/services/aigc/video-generation/video-synthesis"
        val body = buildJsonObject {
            put("model", offer.model)
            put("input", buildJsonObject { put("prompt", prompt) })
            put("parameters", buildJsonObject {
                put("size", "1280*720")
                put("duration", 5)
            })
        }
        val request = authorizedRequest(offer, url)
            .header("X-DashScope-Async", "enable")
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        val root = readJson(request)
        return root["output"]?.jsonObject?.get("task_id")?.jsonPrimitive?.contentOrNull
            ?.takeIf(String::isNotBlank)
            ?: error("Wan 未返回视频任务编号")
    }

    suspend fun videoStatus(offer: LocalMediaOffer, taskId: String): LocalMediaVideoWireStatus {
        require(offer.provider == LocalMediaProvider.QWEN && offer.kind == LocalMediaKind.VIDEO)
        require(taskId.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "视频任务编号无效" }
        val uri = java.net.URI(normalizeModelBaseUrl(offer.profile.baseUrl))
        val request = authorizedRequest(offer, "https://${uri.host}/api/v1/tasks/$taskId").get().build()
        val root = readJson(request)
        return QwenWanTaskResult.parse(root)
    }

    private suspend fun sendJson(offer: LocalMediaOffer, url: String, body: JsonObject): JsonObject =
        sendJson(offer, url, body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))

    private suspend fun sendJson(offer: LocalMediaOffer, url: String, body: RequestBody): JsonObject =
        readJson(authorizedRequest(offer, url).post(body).build())

    private suspend fun readJson(request: Request): JsonObject = withContext(Dispatchers.IO) {
        withCancellableHttpResponse(mediaHttp.newCall(request)) { response ->
            if (!response.isSuccessful) error("创作服务请求失败（HTTP ${response.code}）")
            val payload = response.body?.string() ?: error("创作服务未返回数据")
            LocalMediaApiResponse.checked(json.parseToJsonElement(payload).jsonObject)
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
        File(File(context.cacheDir, "media-studio").apply { mkdirs() },
            "created-${UUID.randomUUID()}.$extension")

    private suspend fun saveEncodedImage(encoded: String, extension: String = "png"): File = withContext(Dispatchers.IO) {
        val file = outputFile(extension)
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

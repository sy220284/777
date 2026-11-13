package com.labteto.dshmobile.local.model

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import com.labteto.dshmobile.core.net.withCancellableHttpResponse
import com.labteto.dshmobile.local.io.readBoundedBody
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
            throw LocalMediaRequestRejected("创作服务返回错误$label：${reason?.take(320) ?: "请检查模型和账户权限"}")
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
    private val videoLeases = LocalVideoTaskLeaseManager(File(context.filesDir, "media-studio"))

    private val mediaHttp = http.newBuilder()
        .readTimeout(10, TimeUnit.MINUTES)
        .writeTimeout(2, TimeUnit.MINUTES)
        .callTimeout(0, TimeUnit.SECONDS)
        .build()

    // Provider JSON requests carry credentials and may have billable effects. Never follow
    // redirects or transparently replay them; CDN asset downloads retain their own client.
    private val mediaRequestHttp = mediaHttp.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build()

    /** Keep provider CDN images in durable app storage whenever the official result URL is accessible. */
    suspend fun downloadGeneratedImage(link: String): File = withContext(Dispatchers.IO) {
        val uri = java.net.URI(link)
        require(uri.scheme.equals("https", true) && uri.userInfo == null &&
            !uri.host.isNullOrBlank() && uri.port == -1) { "仅支持安全 HTTPS 图片地址" }
        val request = Request.Builder().url(link).get().build()
        withCancellableHttpResponse(mediaHttp.newCall(request)) { response ->
            check(response.isSuccessful && response.request.url.isHttps) { "无法下载生成图片" }
            val body = response.body ?: error("图片下载响应为空")
            val mime = body.contentType()?.toString()?.substringBefore(';')?.lowercase()
                ?: error("生成图片缺少媒体类型")
            val extension = LocalMediaApiResponse.imageExtension(mime)
            require(body.contentLength() <= 20_000_000L) { "生成图片超过保存上限" }
            val file = outputFile(extension)
            try {
                var count = 0L
                body.byteStream().use { input ->
                    file.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            count += n
                            require(count <= 20_000_000L) { "生成图片超过保存上限" }
                            output.write(buffer, 0, n)
                        }
                    }
                }
                require(file.length() > 0L) { "生成图片为空" }
                file
            } catch (error: Throwable) {
                file.delete()
                throw error
            }
        }
    }


    /**
     * Download an already billed, completed provider result. Reuses the exact immutable provider
     * task identity; the first request and every resumed request are GET only.
     * A strong ETag + byte Content-Range are mandatory for append. Changed versions start anew.
     */
    suspend fun downloadGeneratedVideo(link: String, taskId: String): File = withContext(Dispatchers.IO) {
        val uri = java.net.URI(link)
        require(uri.scheme.equals("https", true) && uri.userInfo == null &&
            !uri.host.isNullOrBlank() && uri.port == -1) { "仅支持安全 HTTPS 视频地址" }
        require(taskId.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "视频任务身份无效" }
        val directory = File(context.filesDir, "media-studio").apply { mkdirs() }
        videoLeases.withLease(taskId) {
        val partial = File(directory, ".video-$taskId.part")
        val checkpoint = File(directory, ".video-$taskId.checkpoint")
        val fingerprint = java.security.MessageDigest.getInstance("SHA-256")
            .digest(link.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val resume = localVideoResumeCheckpoint(partial, checkpoint, fingerprint)
        val request = Request.Builder().url(link).get().apply {
            if (resume != null) {
                header("Range", "bytes=${resume.offset}-")
                header("If-Range", resume.etag)
            }
        }.build()
        withCancellableHttpResponse(mediaHttp.newCall(request)) { response ->
            check(response.request.url.isHttps) { "视频下载重定向不安全" }
            val mime = response.body?.contentType()?.toString()?.substringBefore(';')?.lowercase()
            require(mime == null || mime in setOf("video/mp4", "application/octet-stream")) {
                "视频响应类型不匹配"
            }
            val body = response.body ?: error("视频下载响应为空")
            val transfer = localVideoTransferPlan(
                resume, response.code, response.header("ETag"),
                response.header("Content-Range"), body.contentLength(),
            )
            if (!transfer.append) {
                if (partial.exists()) check(partial.delete()) { "旧视频片段无法删除，已中止保存" }
                if (checkpoint.exists()) check(checkpoint.delete()) { "旧视频断点无法删除" }
            }
            if (transfer.etag == null && checkpoint.exists()) {
                check(checkpoint.delete()) { "不可验证的视频断点元数据无法清除" }
            }
            var downloaded = if (transfer.append) resume!!.offset else 0L
            var confirmedOffset = downloaded
            body.byteStream().use { input ->
                java.io.FileOutputStream(partial, transfer.append).use { output ->
                    val buffer = ByteArray(16_384)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        downloaded += n
                        require(downloaded <= 200_000_000L) { "视频超过本地保存上限" }
                        output.write(buffer, 0, n)
                        if (transfer.etag != null && downloaded - confirmedOffset >= 32L * 1024L * 1024L) {
                            // A crash may leave trailing bytes without a checkpoint; only
                            // this fsynced, SHA-256 checked prefix may ever be resumed.
                            output.fd.sync()
                            saveLocalVideoResumeCheckpoint(partial, checkpoint, fingerprint, transfer.etag)
                            confirmedOffset = downloaded
                        }
                    }
                    output.fd.sync()
                }
            }
            check(downloaded > 0L) { "视频下载为空" }
            if (transfer.etag != null && downloaded != transfer.expectedLength &&
                downloaded > confirmedOffset) {
                // EOF before the advertised total is not a successful download, but
                // these new bytes were fully persisted and can be verified on retry.
                saveLocalVideoResumeCheckpoint(partial, checkpoint, fingerprint, transfer.etag)
            }
            check(transfer.expectedLength == null || downloaded == transfer.expectedLength) {
                "视频字节长度不完整，原断点保留，未生成可见成果"
            }
            val target = outputFile("mp4")
            java.nio.file.Files.move(
                partial.toPath(), target.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            )
            if (checkpoint.exists()) check(checkpoint.delete()) { "已完成视频的断点元数据清理失败" }
            target
        }
        }
    }

    /** User deletion invalidates a persisted transfer; it never starts another provider call. */
    fun forgetGeneratedVideoProgress(taskId: String) {
        require(taskId.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        val directory = File(context.filesDir, "media-studio")
        videoLeases.withExclusiveDeletion(taskId) {
            listOf(".video-$taskId.part", ".video-$taskId.checkpoint").forEach { name ->
                val file = File(directory, name)
                if (file.exists()) check(file.delete()) { "视频临时成果无法清理：$name" }
            }
        }
    }

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

    /** Image edits send actual original pixels; revised text alone is not an image edit. */
    suspend fun editImage(
        offer: LocalMediaOffer, prompt: String, ratio: String, source: File,
    ): LocalMediaImageResult {
        require(offer.provider == LocalMediaProvider.OPENAI && offer.kind == LocalMediaKind.IMAGE) {
            "当前图像服务尚未接入原图编辑，请选择 OpenAI 图片模型或改用重画"
        }
        require(prompt.isNotBlank() && ratio in setOf("1:1", "16:9", "9:16", "4:3", "3:4"))
        val mediaFolder = File(context.filesDir, "media-studio").canonicalFile
        require(source.isFile && source.canonicalFile.parentFile == mediaFolder &&
            source.length() in 1L..20_000_000L) { "原图不存在或超过 20 MB，请改用重新画图" }
        val mime = when (source.extension.lowercase()) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            else -> error("原图格式暂不支持编辑，请改用 PNG、JPEG 或 WEBP")
        }
        val size = when (ratio) {
            "1:1" -> "1024x1024"
            "16:9" -> "1536x864"
            "9:16" -> "864x1536"
            "4:3" -> "1536x1152"
            else -> "1152x1536"
        }
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", offer.model)
            .addFormDataPart("prompt", prompt)
            .addFormDataPart("size", size)
            .addFormDataPart("image", "original.${source.extension.lowercase()}",
                source.asRequestBody(mime.toMediaType()))
            .build()
        val root = sendJson(offer, "https://api.openai.com/v1/images/edits", body)
        val encoded = root["data"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("b64_json")?.jsonPrimitive?.contentOrNull
            ?: error("图像编辑服务未返回图片数据")
        return LocalMediaImageResult(file = saveEncodedImage(encoded))
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
            .post(localMediaOneShotBody(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType())))
            .build()
        return withContext(Dispatchers.IO) {
            markMediaRequestStarted()
            withCancellableHttpResponse(mediaHttp.newCall(request)) { response ->
                if (!response.isSuccessful) throwMediaHttpFailure("OpenAI 配音", response.code)
                markMediaProviderSucceeded()
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
            .post(localMediaOneShotBody(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType())))
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
        val admitted = if (request.method == "POST") {
            markMediaRequestStarted()
            request.newBuilder().post(localMediaOneShotBody(requireNotNull(request.body))).build()
        } else request
        withCancellableHttpResponse(mediaRequestHttp.newCall(admitted)) { response ->
            if (!response.isSuccessful) throwMediaHttpFailure("创作服务", response.code)
            val payload = readBoundedBody(
                response.body ?: error("创作服务未返回数据"),
                MAX_MEDIA_JSON_RESPONSE_BYTES,
            )
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

    // Base64 image responses can be larger than their decoded image bytes.
    // Enforce the ceiling during reading, not after the entire JSON is allocated.
    private companion object {
        const val MAX_MEDIA_JSON_RESPONSE_BYTES = 32 * 1024 * 1024
    }

    private fun outputFile(extension: String): File =
        File(File(context.filesDir, "media-studio").apply { mkdirs() },
            "created-${UUID.randomUUID()}.$extension")

    private suspend fun saveEncodedImage(encoded: String, extension: String = "png"): File = withContext(Dispatchers.IO) {
        markMediaProviderSucceeded()
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

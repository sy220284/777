package com.labteto.dshmobile.local.model

import android.content.Context
import android.net.Uri
import com.labteto.dshmobile.core.net.withCancellableHttpResponse
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.net.URI
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.RequestBody.Companion.asRequestBody

internal data class MiniMaxVideoStatus(val state: String, val downloadUrl: String? = null)

/** Native Android transport for provider-owned media features. No CLI, Node, shell or secret duplication. */
@Singleton
internal class MiniMaxNativeMediaClient @Inject constructor(
    private val credentials: LocalModelCredentialResolver,
    private val http: OkHttpClient,
    private val json: Json,
    @ApplicationContext private val context: Context,
) {
    suspend fun generateImage(profile: LocalModelProfile, prompt: String, ratio: String): String {
        require(prompt.isNotBlank()) { "请填写图片内容" }
        require(ratio in setOf("1:1", "16:9", "9:16", "4:3", "3:4")) { "不支持的图片比例" }
        val root = post(profile, "/v1/image_generation", buildJsonObject {
            put("model", "image-01")
            put("prompt", prompt)
            put("aspect_ratio", ratio)
            put("n", 1)
            put("response_format", "url")
        })
        return root["data"]?.jsonObject?.get("image_urls")?.jsonArray
            ?.firstOrNull()?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.startsWith("https://") }
            ?: error("MiniMax 图片接口未返回有效的 HTTPS 图片地址")
    }

    suspend fun synthesizeSpeech(profile: LocalModelProfile, text: String, voice: String): File {
        require(text.isNotBlank()) { "请输入配音文本" }
        require(text.length <= 10_000) { "MiniMax 单次配音最多支持 10000 字符" }
        require(voice.isNotBlank()) { "请选择音色" }
        val root = post(profile, "/v1/t2a_v2", buildJsonObject {
            put("model", "speech-2.8-hd")
            put("text", text)
            put("voice_setting", buildJsonObject { put("voice_id", voice) })
            put("audio_setting", buildJsonObject {
                put("format", "mp3")
                put("sample_rate", 32000)
                put("bitrate", 128000)
                put("channel", 1)
            })
            put("output_format", "hex")
        })
        val hex = root["data"]?.jsonObject?.get("audio")?.jsonPrimitive?.contentOrNull
            ?: error("MiniMax 未返回语音数据")
        require(hex.length % 2 == 0 && hex.length in 2..MAX_AUDIO_HEX_CHARS) {
            "MiniMax 语音数据长度异常"
        }
        val audio = ByteArray(hex.length / 2)
        for (i in audio.indices) {
            val high = Character.digit(hex[i * 2], 16)
            val low = Character.digit(hex[i * 2 + 1], 16)
            check(high >= 0 && low >= 0) { "MiniMax 返回的音频不是有效的十六进制" }
            audio[i] = ((high shl 4) or low).toByte()
        }
        return withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "minimax-media").apply { mkdirs() }
            val file = File(dir, "voice-${UUID.randomUUID()}.mp3")
            file.writeBytes(audio)
            file
        }
    }

    suspend fun transcribe(profile: LocalModelProfile, uri: Uri): String {
        // SAF files may be 50 MB: stage to private cache instead of copying into the app heap.
        val stage = withContext(Dispatchers.IO) {
            val file = File.createTempFile("minimax-asr-", ".audio", context.cacheDir)
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    file.outputStream().use { output ->
                        val chunk = ByteArray(8192)
                        var total = 0L
                        while (true) {
                            val n = input.read(chunk)
                            if (n < 0) break
                            total += n
                            check(total <= MAX_ASR_FILE_BYTES) {
                                "录音文件超过 MiniMax 识别的 50 MB 上限"
                            }
                            output.write(chunk, 0, n)
                        }
                    }
                } ?: error("无法读取选中的录音")
                require(file.length() > 0) { "录音文件为空" }
                file
            } catch (failure: Throwable) {
                file.delete()
                throw failure
            }
        }
        return try {
            val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("model", "asr-1.0")
                .addFormDataPart("response_format", "json")
                .addFormDataPart("file", "recording", stage.asRequestBody("application/octet-stream".toMediaType()))
                .build()
            val root = requestJson(profile, "/v1/speech_to_text", multipart)
            root["text"]?.jsonPrimitive?.contentOrNull
                ?: error("MiniMax 未返回识别文字")
        } finally {
            stage.delete()
        }
    }

    suspend fun startVideo(profile: LocalModelProfile, prompt: String): String {
        require(prompt.isNotBlank()) { "请填写视频内容" }
        val root = post(profile, "/v1/video_generation", buildJsonObject {
            put("model", "MiniMax-Hailuo-2.3")
            put("prompt", prompt)
        })
        return root["task_id"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() }
            ?: error("MiniMax 未返回视频任务编号")
    }

    suspend fun videoStatus(profile: LocalModelProfile, taskId: String): MiniMaxVideoStatus {
        require(taskId.isNotBlank() && taskId.length <= 128 && taskId.all { it.isLetterOrDigit() || it in "-_" }) {
            "视频任务编号无效"
        }
        val root = requestJson(profile, "/v1/query/video_generation?task_id=$taskId")
        val state = root["status"]?.jsonPrimitive?.contentOrNull.orEmpty()
        when (state) {
            "Queueing", "Processing" -> return MiniMaxVideoStatus(state)
            "Failed" -> return MiniMaxVideoStatus("Failed")
            "Success" -> {
                val fileId = root["file_id"]?.jsonPrimitive?.contentOrNull
                    ?: error("视频已完成但未返回文件编号")
                require(fileId.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "视频文件编号异常" }
                val file = requestJson(profile, "/v1/files/retrieve?file_id=$fileId")
                val url = file["file"]?.jsonObject?.get("download_url")?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.startsWith("https://") }
                    ?: error("视频已完成，但暂无法获取下载链接")
                return MiniMaxVideoStatus("Success", url)
            }
            else -> error("MiniMax 返回未知视频任务状态：$state")
        }
    }

    private suspend fun post(profile: LocalModelProfile, path: String, body: JsonObject): JsonObject =
        requestJson(profile, path, body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))

    private suspend fun requestJson(
        profile: LocalModelProfile,
        path: String,
        body: RequestBody? = null,
    ): JsonObject = withContext(Dispatchers.IO) {
        require(profile.authKind == LocalModelAuthKind.API_KEY &&
            profile.model.startsWith("MiniMax-", ignoreCase = true) &&
            LocalMiniMaxPolicy.isOfficial(profile.baseUrl)
        ) { "请先选择 MiniMax 官方模型" }
        val route = URI(normalizeModelBaseUrl(profile.baseUrl))
        require(route.scheme.equals("https", true) && route.port == -1) {
            "MiniMax 多模态功能仅允许官方 HTTPS 接口"
        }
        val credential = credentials.resolve(profile.model, profile.baseUrl, profile).bearerToken
        val builder = Request.Builder()
            .url("https://${route.host}$path")
            .header("Authorization", "Bearer $credential")
            .header("Accept", "application/json")
        val request = (if (body == null) builder.get() else builder.post(body)).build()
        withCancellableHttpResponse(http.newCall(request)) { response ->
            if (!response.isSuccessful) error("MiniMax 多模态请求失败（HTTP ${response.code}）")
            val raw = response.body?.string() ?: error("MiniMax 未返回数据")
            check(raw.length <= MAX_JSON_RESPONSE_CHARS) { "MiniMax 响应体超过安全读取上限" }
            val result = json.parseToJsonElement(raw).jsonObject
            LocalMiniMaxPolicy.requireSuccessfulResponse(result)
            result
        }
    }

    private companion object {
        const val MAX_ASR_FILE_BYTES = 50 * 1024 * 1024
        const val MAX_AUDIO_HEX_CHARS = 40 * 1024 * 1024
        const val MAX_JSON_RESPONSE_CHARS = 45 * 1024 * 1024
    }
}

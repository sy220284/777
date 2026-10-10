package com.labteto.dshmobile.update

import com.labteto.dshmobile.core.net.withCancellableHttpResponse
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import okhttp3.OkHttpClient
import okhttp3.Request

/** Bounded update staging; cancelled attempts never commit or retry their payload. */
internal class UpdatePayloadTransfer(private val downloadClient: OkHttpClient) {
    suspend fun downloadFile(
        url: String,
        target: File,
        maxBytes: Long,
        expectedBytes: Long?,
        kind: String,
        accept: String,
    ) {
        validateUpdateExpectedSize(expectedBytes, maxBytes, kind)
        val temp = File.createTempFile("update-download-", ".part", target.absoluteFile.parentFile)
        var lastError: IOException? = null

        repeat(MAX_DOWNLOAD_ATTEMPTS) { attempt ->
            temp.delete()
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("Accept", accept)
                    .header("Cache-Control", "no-cache")
                    .get()
                    .build()
                withCancellableHttpResponse(downloadClient.newCall(request)) { response ->
                    if (!response.isSuccessful) {
                        throw IOException("下载 $kind 失败：HTTP ${response.code}")
                    }
                    val body = response.body ?: throw IOException("下载 $kind 返回空响应")
                    val declared = body.contentLength()
                    validateUpdateDeclaredSize(declared, expectedBytes, maxBytes, kind)

                    var total = 0L
                    temp.outputStream().use { output ->
                        body.byteStream().use { input ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val read = input.read(buffer)
                                currentCoroutineContext().ensureActive()
                                if (read < 0) break
                                total += read
                                if (total > maxBytes ||
                                    (expectedBytes != null && total > expectedBytes)
                                ) {
                                    throw IOException("$kind 下载超过预期大小")
                                }
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                    if (declared >= 0L && total != declared) {
                        throw EOFException("$kind 下载流提前结束：$total/$declared 字节")
                    }
                    if (expectedBytes != null && total != expectedBytes) {
                        throw EOFException("$kind 下载不完整：$total/$expectedBytes 字节")
                    }
                }

                currentCoroutineContext().ensureActive()
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                return
            } catch (error: IOException) {
                lastError = error
                temp.delete()
                if (attempt + 1 < MAX_DOWNLOAD_ATTEMPTS) {
                    delay(RETRY_BACKOFF_MS * (attempt + 1L))
                }
            } finally {
                temp.delete()
            }
        }

        throw IOException(
            "$kind 下载连接中断，已自动重试 $MAX_DOWNLOAD_ATTEMPTS 次：" +
                (lastError?.message ?: "未知网络错误"),
            lastError,
        )
    }

    suspend fun fetchText(url: String, maxBytes: Long): String {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "text/plain, application/octet-stream")
            .get()
            .build()
        return withCancellableHttpResponse(downloadClient.newCall(request)) { response ->
            require(response.isSuccessful) { "下载校验文件失败：HTTP ${response.code}" }
            val body = response.body ?: error("校验文件为空")
            require(body.contentLength() <= maxBytes) { "校验文件异常过大" }
            val bytes = body.byteStream().use { readChecksumBytes(it, maxBytes) }
            bytes.toString(Charsets.UTF_8)
        }
    }

    private companion object {
        const val MAX_DOWNLOAD_ATTEMPTS = 4
        const val RETRY_BACKOFF_MS = 500L
    }
}

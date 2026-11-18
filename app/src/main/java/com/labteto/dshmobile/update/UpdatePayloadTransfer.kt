package com.labteto.dshmobile.update

import com.labteto.dshmobile.core.net.withCancellableHttpResponse
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.FileOutputStream
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
        expectedSha256: String? = null,
        onProgress: (Long, Long?) -> Unit = { _, _ -> },
    ) {
        validateUpdateExpectedSize(expectedBytes, maxBytes, kind)
        val resumable = expectedSha256 != null && expectedSha256.matches(Regex("[0-9a-fA-F]{64}"))
        val parent = requireNotNull(target.absoluteFile.parentFile)
        check(parent.isDirectory || parent.mkdirs()) { "无法创建更新下载目录" }
        // Separate from the installer handoff directory so restarting an attempt cannot
        // discard a partially received payload. A lane holds at most one artifact.
        val temp = if (resumable) {
            val dir = File(parent.parentFile ?: parent, "update-resume")
            check(dir.isDirectory || dir.mkdirs()) { "无法保存下载进度" }
            val lane = if (kind == "APK") "apk" else "patch"
            val name = "$lane-${expectedSha256!!.lowercase()}.part"
            dir.listFiles()?.filter { it.name.startsWith("$lane-") && it.name !in setOf(name, "$name.etag") }
                ?.forEach { check(!it.exists() || it.delete()) { "无法清理旧版本下载进度" } }
            File(dir, name)
        } else File.createTempFile("update-download-", ".part", parent)
        val validator = File(temp.parentFile, temp.name + ".etag")
        var lastError: IOException? = null

        fun reset() {
            Files.deleteIfExists(validator.toPath())
            Files.deleteIfExists(temp.toPath())
        }

        try {
            repeat(MAX_DOWNLOAD_ATTEMPTS) { attempt ->
                val savedTag = if (resumable && validator.isFile && validator.length() in 1..512)
                    strongUpdateTag(validator.readText()) else null
                val offset = if (savedTag != null && temp.length() in 1L until (expectedBytes ?: maxBytes))
                    temp.length() else 0L
                if (offset == 0L) reset()
                try {
                    val request = Request.Builder()
                        .url(url)
                        .header("Accept", accept)
                        .header("Accept-Encoding", "identity")
                        .header("Cache-Control", "no-cache")
                        .apply {
                            if (offset > 0) {
                                header("Range", "bytes=$offset-")
                                header("If-Range", requireNotNull(savedTag))
                            }
                        }
                        .get()
                        .build()
                    withCancellableHttpResponse(downloadClient.newCall(request)) { response ->
                        if (response.code == 416 && offset > 0) {
                            reset()
                            throw IOException("$kind 的远端版本或大小已变化，将重新下载")
                        }
                        if (!response.isSuccessful) throw IOException("下载 $kind 失败：HTTP ${response.code}")
                        val body = response.body ?: throw IOException("下载 $kind 返回空响应")
                        val declared = body.contentLength()
                        val responseTag = strongUpdateTag(response.header("ETag"))
                        val append = response.code == 206
                        val range = if (append)
                            parseUpdateContentRange(response.header("Content-Range"), offset, expectedBytes, maxBytes)
                        else null
                        if (append && (range == null || savedTag != responseTag || offset == 0L ||
                                declared >= 0 && declared != range.last - range.first + 1)) {
                            reset()
                            throw IOException("$kind 断点校验失败，将重新下载")
                        }
                        if (response.code != 200 && !append) throw IOException("不支持的下载状态：${response.code}")
                        if (!append) {
                            reset()
                            validateUpdateDeclaredSize(declared, expectedBytes, maxBytes, kind)
                            if (resumable && responseTag != null) validator.writeText(responseTag)
                        }
                        var total = if (append) offset else 0L
                        val totalExpected = range?.total ?: expectedBytes ?: declared.takeIf { it >= 0 }
                        onProgress(total, totalExpected)
                        FileOutputStream(temp, append).use { output ->
                            body.byteStream().use { input ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val read = input.read(buffer)
                                    currentCoroutineContext().ensureActive()
                                    if (read < 0) break
                                    val next = total + read
                                    if (next > maxBytes || (totalExpected != null && next > totalExpected)) {
                                        reset()
                                        throw IOException("$kind 下载超过预期大小")
                                    }
                                    output.write(buffer, 0, read)
                                    total = next
                                    onProgress(total, totalExpected)
                                }
                            }
                        }
                        val start = if (append) offset else 0L
                        if (declared >= 0 && total - start != declared) throw EOFException("$kind 下载流提前结束")
                        if (totalExpected != null && total != totalExpected) throw EOFException("$kind 下载尚未完整")
                    }

                    currentCoroutineContext().ensureActive()
                    Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    Files.deleteIfExists(validator.toPath())
                    return
                } catch (error: IOException) {
                    currentCoroutineContext().ensureActive()
                    lastError = error
                    if (!resumable || !validator.isFile) reset()
                    if (attempt + 1 < MAX_DOWNLOAD_ATTEMPTS) delay(RETRY_BACKOFF_MS * (attempt + 1L))
                }
            }
            throw IOException("$kind 下载连接中断：${lastError?.message.orEmpty()}", lastError)
        } finally {
            if (!resumable || !validator.isFile) reset()
        }
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


private fun strongUpdateTag(value: String?): String? =
    value?.takeIf { it.length in 2..512 && it.first() == '"' && it.last() == '"' &&
        it.substring(1, it.length - 1).all { char -> char in '!'..'~' && char != '"' } }

internal data class UpdateContentRange(val first: Long, val last: Long, val total: Long)

internal fun parseUpdateContentRange(value: String?, offset: Long, expectedBytes: Long?, maxBytes: Long): UpdateContentRange? {
    val fields = value?.let { Regex("^bytes ([0-9]+)-([0-9]+)/([0-9]+)$").matchEntire(it) } ?: return null
    val first = fields.groupValues[1].toLongOrNull() ?: return null
    val last = fields.groupValues[2].toLongOrNull() ?: return null
    val total = fields.groupValues[3].toLongOrNull() ?: return null
    if (first != offset || last < first || last != total - 1 || total > maxBytes ||
        expectedBytes != null && expectedBytes != total) return null
    return UpdateContentRange(first, last, total)
}

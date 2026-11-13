package com.labteto.dshmobile.local.model

import java.io.File

/** Byte resume metadata is local-only: URL is represented by a SHA-256 fingerprint. */
internal data class LocalVideoResumeCheckpoint(val offset: Long, val etag: String)
internal data class LocalVideoTransferPlan(
    val append: Boolean, val etag: String?, val expectedLength: Long?,
)

internal fun localVideoStrongEtag(value: String?): String? =
    value?.takeIf { it.length in 3..200 && it.first() == '"' && it.last() == '"' &&
        '\r' !in it && '\n' !in it && it != "\"\"" }

private const val RESUME_FORMAT = "video-resume-v2"
private const val MAX_VIDEO_BYTES = 200_000_000L

/**
 * Hash the complete committed prefix of an app-private partial file. Never trust a
 * CDN ETag as proof that bytes retained on this device survived without corruption.
 */
private fun localVideoPrefixDigest(partial: File, size: Long): String {
    require(size in 1L..MAX_VIDEO_BYTES)
    require(java.nio.file.Files.isRegularFile(partial.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    var left = size
    java.nio.file.Files.newByteChannel(partial.toPath(), setOf<java.nio.file.OpenOption>(
        java.nio.file.StandardOpenOption.READ, java.nio.file.LinkOption.NOFOLLOW_LINKS,
    )).use { channel ->
        java.nio.channels.Channels.newInputStream(channel).use { input ->
            val buffer = ByteArray(32 * 1024)
            while (left > 0L) {
                val count = input.read(buffer, 0, minOf(left, buffer.size.toLong()).toInt())
                check(count > 0) { "视频断点文件提前结束" }
                digest.update(buffer, 0, count)
                left -= count
            }
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

/** A durable checkpoint is one exact, already-fsynced local byte prefix. */
internal fun saveLocalVideoResumeCheckpoint(
    partial: File, checkpoint: File, urlFingerprint: String, etag: String,
) {
    require(localVideoStrongEtag(etag) == etag)
    val size = partial.length()
    require(size in 1L..MAX_VIDEO_BYTES)
    val sha = localVideoPrefixDigest(partial, size)
    check(partial.length() == size) { "视频断点正在发生变化" }
    val candidate = File(checkpoint.parentFile,
        checkpoint.name + ".tmp-" + java.util.UUID.randomUUID())
    try {
        java.io.FileOutputStream(candidate).use { out ->
            out.write("$RESUME_FORMAT\n$urlFingerprint\n$etag\n$size\n$sha\n".toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        java.nio.file.Files.move(candidate.toPath(), checkpoint.toPath(),
            java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    } finally {
        candidate.delete()
    }
}

/**
 * Cold resume requires a pinned server ETag AND a locally verified SHA-256 prefix.
 * Legacy checkpoints without a digest cannot authenticate their .part contents.
 * Bytes beyond the last durable checkpoint are unconfirmed and are discarded.
 */
internal fun localVideoResumeCheckpoint(
    partial: File, checkpoint: File, urlFingerprint: String,
): LocalVideoResumeCheckpoint? = runCatching {
    if (!checkpoint.isFile ||
        !java.nio.file.Files.isRegularFile(partial.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
        return null
    val lines = checkpoint.readText().split('\n')
    if (lines.getOrNull(0) != RESUME_FORMAT || lines.getOrNull(1) != urlFingerprint)
        return null
    val etag = localVideoStrongEtag(lines.getOrNull(2)) ?: return null
    val size = lines.getOrNull(3)?.toLongOrNull()?.takeIf { it in 1L..MAX_VIDEO_BYTES }
        ?: return null
    val sha = lines.getOrNull(4)?.takeIf { it.matches(Regex("[a-f0-9]{64}")) }
        ?: return null
    if (partial.length() < size || localVideoPrefixDigest(partial, size) != sha) return null
    if (partial.length() > size) {
        java.io.RandomAccessFile(partial, "rw").use { file ->
            file.setLength(size)
            file.fd.sync()
        }
    }
    LocalVideoResumeCheckpoint(size, etag)
}.getOrNull()

/** HTTP resume is accepted only when the server proves the requested byte range and version. */
internal fun localVideoTransferPlan(
    resume: LocalVideoResumeCheckpoint?,
    status: Int,
    responseEtag: String?,
    contentRange: String?,
    bodyLength: Long,
): LocalVideoTransferPlan {
    require(status == 200 || (status == 206 && resume != null)) {
        "视频下载响应无效：HTTP $status"
    }
    val etag = localVideoStrongEtag(responseEtag)
    if (status == 200) {
        require(bodyLength <= 200_000_000L) { "视频超过本地保存上限" }
        return LocalVideoTransferPlan(false, etag, bodyLength.takeIf { it >= 0 })
    }
    val range = Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+)").matchEntire(contentRange.orEmpty())
        ?: error("视频续传没有有效 Content-Range")
    val (start, end, total) = range.destructured
    val first = start.toLong()
    val last = end.toLong()
    val size = total.toLong()
    require(first == resume!!.offset && last >= first && last < size &&
        size <= 200_000_000L && etag == resume.etag &&
        (bodyLength < 0 || bodyLength == last - first + 1)) {
        "视频续传字节段或服务端版本不一致，拒绝拼接"
    }
    return LocalVideoTransferPlan(true, etag, size)
}

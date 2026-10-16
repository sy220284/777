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

/** Cold resume requires a pinned strong validator. Missing/mismatched metadata starts fresh. */
internal fun localVideoResumeCheckpoint(
    partial: File, checkpoint: File, urlFingerprint: String,
): LocalVideoResumeCheckpoint? {
    if (!partial.isFile || !checkpoint.isFile || partial.length() !in 1L..200_000_000L) return null
    val lines = checkpoint.readText().split('\n')
    val validator = lines.getOrNull(1)?.let(::localVideoStrongEtag) ?: return null
    if (lines.firstOrNull() != urlFingerprint) return null
    return LocalVideoResumeCheckpoint(partial.length(), validator)
}

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

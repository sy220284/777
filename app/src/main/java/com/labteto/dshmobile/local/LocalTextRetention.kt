package com.labteto.dshmobile.local

/**
 * Conservative request estimator used only for pressure decisions. DeepSeek's exact tokenizer is
 * provider-owned; UTF-8 bytes / 3 deliberately errs toward earlier compaction for mixed Chinese,
 * JSON and code without adding a heavyweight tokenizer to the APK.
 */
internal fun estimateModelTokens(value: String): Int {
    if (value.isEmpty()) return 0
    val bytes = value.toByteArray(Charsets.UTF_8).size
    return (bytes + 2) / 3
}

/** Character-budget truncation that never leaves half of a UTF-16 surrogate pair at the cut. */
internal fun truncateWithoutSplittingSurrogatePair(value: String, maxChars: Int): String {
    require(maxChars >= 0) { "maxChars must be non-negative" }
    if (value.length <= maxChars) return value
    if (maxChars == 0) return ""
    var end = maxChars
    if (
        end < value.length &&
        end > 0 &&
        Character.isHighSurrogate(value[end - 1]) &&
        Character.isLowSurrogate(value[end])
    ) {
        end -= 1
    }
    return value.substring(0, end)
}

internal data class LocalRetainedText(
    val text: String,
    val truncated: Boolean,
    val omittedBytes: Int,
)

/**
 * Keeps a stable UTF-8 prefix and suffix for the model. Cuts are byte-bounded and aligned to UTF-8
 * code-point boundaries, so a long tool result cannot inject a replacement character merely because
 * the retention window ended inside an emoji or supplementary CJK character.
 */
internal fun retainTextForModel(
    value: String,
    maxTokens: Int,
    maxChars: Int,
    tailRatio: Double = 0.25,
): LocalRetainedText {
    require(maxTokens > 0) { "maxTokens must be positive" }
    require(maxChars > 0) { "maxChars must be positive" }
    require(tailRatio in 0.0..0.5) { "tailRatio must be between 0 and 0.5" }

    if (value.length <= maxChars && estimateModelTokens(value) <= maxTokens) {
        return LocalRetainedText(value, truncated = false, omittedBytes = 0)
    }

    val marker = "\n…工具结果过长，中间内容已省略…\n"
    val byteBudget = minOf(
        maxTokens.toLong() * 3L,
        (maxChars - marker.length).coerceAtLeast(16).toLong(),
    ).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    val bytes = value.toByteArray(Charsets.UTF_8)
    if (bytes.size <= byteBudget) {
        val capped = truncateWithoutSplittingSurrogatePair(value, maxChars)
        val omitted = bytes.size - capped.toByteArray(Charsets.UTF_8).size
        return LocalRetainedText(
            text = if (omitted == 0) capped else capped + marker,
            truncated = omitted > 0,
            omittedBytes = omitted.coerceAtLeast(0),
        )
    }

    val tailBudget = (byteBudget * tailRatio).toInt().coerceAtLeast(0)
    val headBudget = (byteBudget - tailBudget).coerceAtLeast(0)
    val headEnd = safeUtf8PrefixEnd(bytes, headBudget)
    val tailStart = safeUtf8SuffixStart(bytes, (bytes.size - tailBudget).coerceAtLeast(headEnd))
    val head = String(bytes, 0, headEnd, Charsets.UTF_8)
    val tail = String(bytes, tailStart, bytes.size - tailStart, Charsets.UTF_8)
    val omitted = (tailStart - headEnd).coerceAtLeast(0)
    return LocalRetainedText(
        text = head + marker + tail,
        truncated = omitted > 0,
        omittedBytes = omitted,
    )
}

private fun safeUtf8PrefixEnd(bytes: ByteArray, requested: Int): Int {
    var end = requested.coerceIn(0, bytes.size)
    if (end == bytes.size) return end
    while (end > 0 && isUtf8Continuation(bytes[end])) end -= 1
    return end
}

private fun safeUtf8SuffixStart(bytes: ByteArray, requested: Int): Int {
    var start = requested.coerceIn(0, bytes.size)
    while (start < bytes.size && isUtf8Continuation(bytes[start])) start += 1
    return start
}

private fun isUtf8Continuation(value: Byte): Boolean =
    (value.toInt() and 0xC0) == 0x80

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

/** Tail-budget truncation that never starts inside a UTF-16 surrogate pair. */
internal fun takeLastWithoutSplittingSurrogatePair(value: String, maxChars: Int): String {
    require(maxChars >= 0) { "maxChars must be non-negative" }
    if (value.length <= maxChars) return value
    if (maxChars == 0) return ""
    var start = value.length - maxChars
    if (
        start > 0 &&
        Character.isLowSurrogate(value[start]) &&
        Character.isHighSurrogate(value[start - 1])
    ) {
        start += 1
    }
    return value.substring(start)
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

/**
 * Work keeps small tool results inline, but a large result must never become the next request's hot
 * payload. The full durable result is spilled by the caller; this returns a bounded recoverable
 * preview so cache-friendly history can keep growing by append instead of compaction.
 *
 * The Work visible allowance below is shared by three consumers and must not drift: write-time
 * retention here, request-time projection (LocalWorkRequestContextProjection) and the recovery
 * page ceiling in LocalToolOutputStore. A page ceiling above this allowance would be re-truncated
 * downstream while next_byte still advanced by the full page, silently skipping bytes.
 */
internal fun retainToolResultForModel(
    value: String,
    usageMode: LocalUsageMode,
    budget: LocalHistoryBudget,
): LocalRetainedText =
    if (usageMode == LocalUsageMode.WORK) retainWorkToolResultForModel(value)
    else retainTextForModel(value, budget.maxToolResultTokens, budget.maxToolResultChars)

internal fun retainWorkToolResultForModel(value: String): LocalRetainedText {
    val bytes = value.toByteArray(Charsets.UTF_8)
    if (bytes.size <= WORK_TOOL_INLINE_BYTES) {
        return LocalRetainedText(value, truncated = false, omittedBytes = 0)
    }
    return retainTextForModel(
        value = value,
        maxTokens = WORK_TOOL_PREVIEW_TOKENS,
        maxChars = WORK_TOOL_PREVIEW_CHARS,
        tailRatio = 0.20,
    )
}

/**
 * Fresh Work tool results stay verbatim up to this size. It equals
 * LocalToolOutputStore.DEFAULT_READ_BYTES, so one default recovery page stays fully visible.
 */
internal const val WORK_TOOL_INLINE_BYTES = 4 * 1024

/**
 * Preview allowance for oversized fresh results. The char cap adds the omission marker length (18)
 * so the resulting byte window still equals WORK_TOOL_INLINE_BYTES and no size range is lost.
 */
internal const val WORK_TOOL_PREVIEW_CHARS = WORK_TOOL_INLINE_BYTES + 18
internal const val WORK_TOOL_PREVIEW_TOKENS = 1_600

/**
 * Recovery page ceiling in Work mode. One page plus its header must stay inside
 * WORK_TOOL_INLINE_BYTES, otherwise a delivered page would be trimmed after next_byte advanced.
 */
internal const val WORK_TOOL_RECOVERY_PAGE_BYTES = WORK_TOOL_INLINE_BYTES - 256

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

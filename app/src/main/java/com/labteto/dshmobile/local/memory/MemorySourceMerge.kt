package com.labteto.dshmobile.local.memory

internal fun memorySourceRef(
    sessionId: String?,
    messageId: String?,
): MemorySourceRef? =
    if (sessionId.isNullOrBlank() || messageId.isNullOrBlank()) null
    else MemorySourceRef(sessionId = sessionId, messageId = messageId)

internal data class MemorySourceMergeResult(
    val sources: List<MemorySourceRef>,
    val truncated: Boolean,
)

internal fun mergeMemorySourceMessages(
    current: List<MemorySourceRef>,
    incoming: MemorySourceRef?,
    limit: Int = 16,
): MemorySourceMergeResult {
    val all = (current + listOfNotNull(incoming)).distinct()
    return MemorySourceMergeResult(
        sources = all.takeLast(limit),
        truncated = all.size > limit,
    )
}

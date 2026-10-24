package com.labteto.dshmobile.local.session

data class LocalSessionStorageStatus(
    val totalBytes: Long,
    val freeBytes: Long,
    val budgetBytes: Long,
    val sessionCount: Int,
    val rawSegmentCount: Int,
    val compressedSegmentCount: Int,
    val pendingRestore: Boolean = false,
) {
    val overBudget: Boolean get() = totalBytes >= budgetBytes
    val lowFreeSpace: Boolean get() = freeBytes < LOW_FREE_SPACE_BYTES
    val warning: Boolean get() = overBudget || lowFreeSpace

    companion object {
        const val DEFAULT_BUDGET_BYTES = 256L * 1024L * 1024L
        const val LOW_FREE_SPACE_BYTES = 512L * 1024L * 1024L
    }
}

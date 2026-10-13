package com.labteto.dshmobile.local.work

/** Returns admission by the real interaction waiter, not a UI-inferred successful approval. */
internal fun interface LocalWorkApprovalUiPort {
    fun answerApproval(pendingId: String, approved: Boolean): Boolean
}

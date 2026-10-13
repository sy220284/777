package com.labteto.dshmobile.local.work

/** Returns admission by the real interaction waiter, not a UI-inferred successful approval. */
internal interface LocalWorkApprovalUiPort {
    fun pendingApproval(): com.labteto.dshmobile.local.interaction.LocalApproval?
    fun answerApproval(pendingId: String, approved: Boolean): Boolean
}

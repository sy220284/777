package com.labteto.dshmobile.local.work

/** Returns admission by the real interaction waiter, not a UI-inferred successful approval. */
internal interface LocalWorkApprovalUiPort {
    fun pendingQuestion(): com.labteto.dshmobile.local.interaction.LocalQuestion?
    fun answerQuestion(pendingId: String, answer: String?): Boolean
    fun pendingApproval(): com.labteto.dshmobile.local.interaction.LocalApproval?
    fun answerApproval(pendingId: String, approved: Boolean): Boolean
}

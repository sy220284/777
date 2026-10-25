package com.labteto.dshmobile.local.jobs

import com.labteto.dshmobile.harness.jobs.JobSnapshot



/** User-visible state for a background command. */
data class LocalJobInfo(
    val id: String,
    val label: String,
    val status: String,
    val ownerSessionId: String? = null,
    val isAgent: Boolean = false,
    val canMessage: Boolean = false,
    val continuable: Boolean = false,
    val pendingMessageCount: Int = 0,
    val updatedAt: Long = 0L,
)

/** Read-only pair from one native Job manager lock; never persisted separately. */
data class LocalJobRegistration(
    val info: LocalJobInfo,
    val snapshot: JobSnapshot,
    val executionActive: Boolean = false,
    val runtimeActivation: Long = 0L,
)

package com.labteto.dshmobile.local.work

import kotlinx.serialization.Serializable

/** One persisted implementation task, aligned with the official todo tool. */
@Serializable
data class LocalTodoItem(
    val content: String,
    val status: String,
)

/** The current durable session goal. */
@Serializable
data class LocalGoal(
    val description: String,
    val status: String = "active",
    val note: String? = null,
)

data class LocalWorkflowProgress(
    val sessionId: String,
    val stage: String,
    val task: String,
    val completed: Int,
    val total: Int,
    val blockedReason: String? = null,
    val needsUserAction: Boolean = false,
)

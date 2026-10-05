package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.interaction.LocalApproval
import com.labteto.dshmobile.local.interaction.LocalQuestion
import com.labteto.dshmobile.local.jobs.LocalJobInfo

/** Work-owned runtime state. Session persistence remains a separate projection boundary. */
data class LocalWorkState(
    val plan: List<String> = emptyList(),
    val todos: List<LocalTodoItem> = emptyList(),
    val goal: LocalGoal? = null,
    val planMode: Boolean = false,
    val jobs: List<LocalJobInfo> = emptyList(),
    val workflowProgress: LocalWorkflowProgress? = null,
    val pendingApproval: LocalApproval? = null,
    val pendingQuestion: LocalQuestion? = null,
    val deviceApprovalLease: Boolean = false,
)

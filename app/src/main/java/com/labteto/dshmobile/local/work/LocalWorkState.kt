package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.LocalApproval
import com.labteto.dshmobile.local.LocalGoal
import com.labteto.dshmobile.local.LocalJobInfo
import com.labteto.dshmobile.local.LocalQuestion
import com.labteto.dshmobile.local.LocalTodoItem
import com.labteto.dshmobile.local.LocalWorkflowProgress

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
)

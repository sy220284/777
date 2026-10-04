package com.labteto.dshmobile.local.runtime

import com.labteto.dshmobile.local.LocalApproval
import com.labteto.dshmobile.local.LocalHarnessResourceState
import com.labteto.dshmobile.local.LocalJobInfo
import com.labteto.dshmobile.local.LocalQuestion
import com.labteto.dshmobile.local.LocalWorkflowProgress

/** Runtime-kernel owned execution state; product-domain state stays in its Feature aggregate. */
data class LocalKernelState(
    val running: Boolean = false,
    val jobs: List<LocalJobInfo> = emptyList(),
    val workflowProgress: LocalWorkflowProgress? = null,
    val queuedInputCount: Int = 0,
    val resources: LocalHarnessResourceState = LocalHarnessResourceState(),
    val contextChars: Int = 0,
    val contextBudgetChars: Int = 0,
    val pendingApproval: LocalApproval? = null,
    val pendingQuestion: LocalQuestion? = null,
)

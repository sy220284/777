package com.labteto.dshmobile.local.runtime



/** Cross-feature runtime kernel state. Product-owned execution details stay in their Feature state. */
data class LocalKernelState(
    val running: Boolean = false,
    val queuedInputCount: Int = 0,
    val resources: LocalHarnessResourceState = LocalHarnessResourceState(),
    val contextChars: Int = 0,
    val contextBudgetChars: Int = 0,
)

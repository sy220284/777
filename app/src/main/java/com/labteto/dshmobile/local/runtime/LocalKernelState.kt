package com.labteto.dshmobile.local.runtime

import com.labteto.dshmobile.local.LocalHarnessResourceState

/** Cross-feature runtime kernel state. Product-owned execution details stay in their Feature state. */
data class LocalKernelState(
    val running: Boolean = false,
    val resources: LocalHarnessResourceState = LocalHarnessResourceState(),
    val contextChars: Int = 0,
    val contextBudgetChars: Int = 0,
)

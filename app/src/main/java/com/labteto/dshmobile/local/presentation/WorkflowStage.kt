package com.labteto.dshmobile.local.presentation

/** Stage labels provided by runtime and mapped to a stable UI presentation contract. */
internal object WorkflowStage {
    const val EXECUTING = "执行中"
    const val REASSIGNING = "重新指派"
    const val VERIFYING = "验收中"
    const val COMPLETED = "已完成"
    const val STALLED = "受阻"
}

package com.labteto.dshmobile.local.interaction

import com.labteto.dshmobile.local.runtime.canAutoApproveSafely

enum class LocalApprovalImpact {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL,
}

/** A tool call waiting for the operator because it crossed an approval boundary. */
data class LocalApproval(
    val callId: String,
    val toolName: String,
    val summary: String,
    val arguments: String,
    val access: String,
    val impact: LocalApprovalImpact = LocalApprovalImpact.HIGH,
    val canAutoApproveSafely: Boolean = false,
    val canApproveDeviceTurn: Boolean = false,
)

/** A model question that pauses the current turn until the user answers it. */
data class LocalQuestion(
    val callId: String,
    val question: String,
    val options: List<String> = emptyList(),
)

package com.labteto.dshmobile.uicontract

import com.labteto.dshmobile.local.interaction.LocalApprovalMode
import kotlinx.coroutines.flow.Flow

data class UiWorkPolicySnapshot(val identity: UiSessionIdentity?, val planMode: Boolean,
    val deviceApprovalLease: Boolean, val approvalMode: LocalApprovalMode)
sealed interface WorkPolicyUiCommand {
    data class SetPlanMode(val identity: UiSessionIdentity, val enabled: Boolean) : WorkPolicyUiCommand
    data class SetApprovalMode(val identity: UiSessionIdentity?, val mode: LocalApprovalMode) : WorkPolicyUiCommand
    data class ApproveWithAutoMode(val identity: UiSessionIdentity, val pendingId: String) : WorkPolicyUiCommand
    data class ApproveDeviceTurn(val identity: UiSessionIdentity, val pendingId: String) : WorkPolicyUiCommand
    data class RevokeDeviceTurn(val identity: UiSessionIdentity) : WorkPolicyUiCommand
}
/** Policy changes remain explicit native UI commands; documents cannot contain these intentions. */
interface WorkPolicyUiPort {
    val snapshots: Flow<UiWorkPolicySnapshot>
    fun snapshot(): UiWorkPolicySnapshot
    fun submit(command: WorkPolicyUiCommand): UiCommandReceipt
}

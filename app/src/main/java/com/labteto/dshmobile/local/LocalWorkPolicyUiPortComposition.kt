package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore
import com.labteto.dshmobile.local.interaction.LocalApprovalPreferences
import com.labteto.dshmobile.local.work.LocalWorkApprovalCoordinator
import com.labteto.dshmobile.local.work.LocalWorkPlanModeCoordinator
import com.labteto.dshmobile.uicontract.WorkPolicyUiPort
import com.labteto.dshmobile.uicontract.WorkPolicyUiCommand
import com.labteto.dshmobile.uicontract.UiWorkPolicySnapshot
import com.labteto.dshmobile.uicontract.UiCommandReceipt
import com.labteto.dshmobile.uicontract.UiCommandRejection
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/** Adapts identity and receipts only; Work owns policy admission, persistence and waiter resolution. */
@Singleton
class LocalWorkPolicyUiPortComposition @Inject internal constructor(
    private val state: LocalRuntimeStateStore,
    private val preferences: LocalApprovalPreferences,
    private val approvals: LocalWorkApprovalCoordinator,
    private val plans: LocalWorkPlanModeCoordinator,
) : WorkPolicyUiPort {
    override val snapshots = combine(state.state, preferences.approvalMode) { _, _ -> snapshot() }.distinctUntilChanged()
    override fun snapshot(): UiWorkPolicySnapshot = synchronized(state.foregroundRunHandle.lock) {
        val current = state.state.value
        UiWorkPolicySnapshot(state.uiBindingIdentity(current), current.work.planMode,
            current.work.deviceApprovalLease, preferences.approvalMode.value)
    }
    override fun submit(command: WorkPolicyUiCommand): UiCommandReceipt = try {
        synchronized(state.foregroundRunHandle.lock) {
            val expected = when (command) {
                is WorkPolicyUiCommand.SetPlanMode -> command.identity
                is WorkPolicyUiCommand.SetApprovalMode -> command.identity
                is WorkPolicyUiCommand.ApproveWithAutoMode -> command.identity
                is WorkPolicyUiCommand.ApproveDeviceTurn -> command.identity
                is WorkPolicyUiCommand.RevokeDeviceTurn -> command.identity
            }
            val current = state.state.value
            if (state.uiBindingIdentity(current) != expected) return@synchronized UiCommandReceipt.Rejected(UiCommandRejection.STALE_IDENTITY)
            if (command !is WorkPolicyUiCommand.SetApprovalMode && (state.sessionTransitioning || current.loading))
                return@synchronized UiCommandReceipt.Rejected(UiCommandRejection.SESSION_TRANSITION)
            if (command !is WorkPolicyUiCommand.SetApprovalMode && current.usageMode != LocalUsageMode.WORK)
                return@synchronized UiCommandReceipt.Rejected(UiCommandRejection.UNSUPPORTED)
            val admitted = when (command) {
                is WorkPolicyUiCommand.SetPlanMode -> plans.setEnabled(command.enabled)
                is WorkPolicyUiCommand.SetApprovalMode -> { approvals.setApprovalModeFromUi(command.mode); true }
                is WorkPolicyUiCommand.ApproveWithAutoMode -> approvals.enableAutoApprovalForPendingFromUi(command.pendingId)
                is WorkPolicyUiCommand.ApproveDeviceTurn -> approvals.enableDeviceApprovalLeaseFromUi(command.pendingId)
                is WorkPolicyUiCommand.RevokeDeviceTurn -> approvals.disableDeviceApprovalLease(command.identity.sessionId)
            }
            if (admitted) UiCommandReceipt.Accepted else UiCommandReceipt.NotAdmitted
        }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: Exception) { UiCommandReceipt.Failed(failure.message ?: "策略操作未完成") }
}

@dagger.Module
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
internal abstract class LocalWorkPolicyUiPortBindings {
    @dagger.Binds @Singleton
    abstract fun policy(implementation: LocalWorkPolicyUiPortComposition): WorkPolicyUiPort
}

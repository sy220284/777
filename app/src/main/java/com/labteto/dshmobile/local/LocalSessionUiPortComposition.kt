package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore
import com.labteto.dshmobile.local.session.LocalConversationMode
import com.labteto.dshmobile.local.session.LocalSessionLifecyclePort
import com.labteto.dshmobile.uicontract.SessionUiPort
import com.labteto.dshmobile.uicontract.SessionUiCommand
import com.labteto.dshmobile.uicontract.UiCommandReceipt
import com.labteto.dshmobile.uicontract.UiCommandRejection
import com.labteto.dshmobile.uicontract.UiSessionsSnapshot
import com.labteto.dshmobile.uicontract.UiSessionDeletionReceipt
import com.labteto.dshmobile.uicontract.UiSessionIdentity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged

/** Session identity and explicit navigation adaptation, with lifecycle writes owned by Session. */
@Singleton
class LocalSessionUiPortComposition @Inject internal constructor(
    private val state: LocalRuntimeStateStore,
    private val lifecycle: LocalSessionLifecyclePort,
) : SessionUiPort {
    override val snapshots = state.state.map { snapshot() }.distinctUntilChanged()
    override fun snapshot(): UiSessionsSnapshot = synchronized(state.foregroundRunHandle.lock) {
        val current = state.state.value
        UiSessionsSnapshot(state.uiBindingIdentity(current), current.usageMode, current.loading,
            current.sessions.toList(), current.error)
    }
    override fun submit(command: SessionUiCommand): UiCommandReceipt = try {
        synchronized(state.foregroundRunHandle.lock) {
            when (command) {
                is SessionUiCommand.Open -> if (lifecycle.switchSession(command.sessionId)) UiCommandReceipt.Queued else UiCommandReceipt.NotAdmitted
                is SessionUiCommand.SelectMode -> { lifecycle.switchUsageMode(command.mode); UiCommandReceipt.Queued }
                is SessionUiCommand.Create -> {
                    if (state.uiBindingIdentity(state.state.value) != command.identity)
                        UiCommandReceipt.Rejected(UiCommandRejection.STALE_IDENTITY)
                    else if (command.mode == LocalConversationMode.PROJECT)
                        UiCommandReceipt.Rejected(UiCommandRejection.UNSUPPORTED)
                    else if (lifecycle.createSession(command.mode, command.usageMode, handoffSummaryOverride = command.handoffSummary))
                        UiCommandReceipt.Accepted else UiCommandReceipt.NotAdmitted
                }
            }
        }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: Exception) {
        com.labteto.dshmobile.observability.AppLog.warn("SessionUi", "failure=true operation=session_ui_command command_type=${command.javaClass.simpleName} cause_type=${failure.javaClass.simpleName}")
        UiCommandReceipt.Failed(failure.message ?: "会话操作未完成，请刷新后确认状态")
    }
    override fun handoffSummary(identity: UiSessionIdentity): String? = synchronized(state.foregroundRunHandle.lock) {
        if (state.uiBindingIdentity(state.state.value) == identity && !state.sessionTransitioning)
            lifecycle.currentHandoffSummary() else null
    }
    override suspend fun delete(sessionIds: Set<String>): UiSessionDeletionReceipt = try {
        UiSessionDeletionReceipt.Deleted(lifecycle.deleteSessionIds(sessionIds.toSet()))
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: Exception) { UiSessionDeletionReceipt.Failed(failure.message) }
}

@dagger.Module
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
internal abstract class LocalSessionUiPortBindings {
    @dagger.Binds @Singleton
    abstract fun session(implementation: LocalSessionUiPortComposition): SessionUiPort
}

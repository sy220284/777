package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.LocalModelRequestCoordinator
import com.labteto.dshmobile.local.model.DeepSeekUsageTracker
import com.labteto.dshmobile.local.model.LocalForegroundModelHistoryRuntime
import com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore
import com.labteto.dshmobile.local.runtime.LocalSessionStorageRuntime
import javax.inject.Inject
import javax.inject.Singleton
import javax.inject.Provider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** ChatFeature composition surface for queue, branching and post-turn lifecycle. */
@Singleton
internal class LocalChatComposition @Inject constructor(
    internal val turnCoordinator: LocalChatTurnCoordinator,
    internal val queue: LocalChatQueueRuntime,
    internal val memory: LocalChatMemoryRuntime,
    private val groupExecutor: Provider<LocalGroupChatTurnExecutor>,
    private val runtimeStateStore: LocalRuntimeStateStore,
    persistence: LocalChatPersistence,
    modelRequests: LocalModelRequestCoordinator,
    usageTracker: DeepSeekUsageTracker,
    sessionStorage: LocalSessionStorageRuntime,
    private val chatState: LocalChatStatePort,
    private val styleGuardSettings: LocalChatStyleGuardSettingsPort,
    summaries: com.labteto.dshmobile.local.model.LocalHistorySummaryProvider,
) {
    private val modelHistory = LocalForegroundModelHistoryRuntime(
        runtimeStateStore = runtimeStateStore,
        sessionStorage = sessionStorage,
        summaries = summaries,
    )
    internal val branchCoordinator = LocalChatBranchCoordinator(
        runtimeStateStore = runtimeStateStore,
        chatState = chatState,
        sessionStorage = sessionStorage,
        modelHistoryRuntime = modelHistory,
    )
    internal val contextRefresh = LocalChatContextRefreshCoordinator(
        runtimeStateStore = runtimeStateStore,
        chatState = chatState,
        chatTurnCoordinator = turnCoordinator,
        persistence = persistence,
        modelRequests = modelRequests,
        usageTracker = usageTracker,
        sessionStorage = sessionStorage,
        branchCoordinator = branchCoordinator,
    )
    internal val replyCoordinator = LocalChatReplyCoordinator(
        chatTurnCoordinator = turnCoordinator,
        usageTracker = usageTracker,
        styleGuardSettings = styleGuardSettings,
    )

    internal fun cancelPostTurn() = contextRefresh.cancelScheduledRefresh()

    private val groupRecoveryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    internal fun resumePostTurn(
        sessionId: String,
        eventLog: com.labteto.dshmobile.local.session.LocalSessionEventLog,
    ): Boolean {
        val snapshot = runtimeStateStore.state.value
        if (snapshot.sessionId != sessionId || snapshot.usageMode != com.labteto.dshmobile.local.LocalUsageMode.CHAT) {
            return false
        }
        if (!snapshot.chat.groupChat.enabled) return contextRefresh.resumePendingIfAny(sessionId, eventLog)
        val context = snapshot.chat.groupChat.context
        if (!snapshot.modelState.configured ||
            context.pendingThroughSequence <= context.processedThroughSequence) return false

        val job = groupRecoveryScope.launch(start = CoroutineStart.LAZY) {
            var failedAttempts = 0
            while (isActive) {
                val current = runtimeStateStore.state.value
                if (current.sessionId != sessionId || !current.chat.groupChat.enabled) break
                if (current.chat.groupChat.context.pendingThroughSequence <=
                    current.chat.groupChat.context.processedThroughSequence) break

                val recovered = groupExecutor.get().reconcileDurableGroupPending(sessionId)
                if (recovered) {
                    failedAttempts = 0
                } else {
                    failedAttempts++
                    if (failedAttempts >= 3) break
                    delay(1_000L * failedAttempts)
                }
            }
        }
        LocalChatPostTurnJobOwner.submit(job)
        job.invokeOnCompletion { LocalChatPostTurnJobOwner.clearIf(job) }
        return true
    }
}

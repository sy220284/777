package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.LocalModelRequestCoordinator
import com.labteto.dshmobile.local.model.DeepSeekUsageTracker
import com.labteto.dshmobile.local.model.LocalForegroundModelHistoryRuntime
import com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore
import com.labteto.dshmobile.local.runtime.LocalSessionStorageRuntime
import javax.inject.Inject
import javax.inject.Singleton

/** ChatFeature composition surface for queue, branching and post-turn lifecycle. */
@Singleton
internal class LocalChatComposition @Inject constructor(
    internal val turnCoordinator: LocalChatTurnCoordinator,
    internal val queue: LocalChatQueueRuntime,
    internal val memory: LocalChatMemoryRuntime,
    runtimeStateStore: LocalRuntimeStateStore,
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
}

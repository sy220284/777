package com.labteto.dshmobile.local

import com.labteto.dshmobile.harness.resource.HarnessResourceScheduler
import com.labteto.dshmobile.harness.session.ConversationHandoffBuilder
import com.labteto.dshmobile.local.chat.ChatDiaryStore
import com.labteto.dshmobile.local.chat.LocalChatSessionLifecyclePlanner
import com.labteto.dshmobile.local.chat.LocalChatSessionModeRoute
import com.labteto.dshmobile.local.interaction.LocalApprovalPreferences
import com.labteto.dshmobile.local.jobs.LocalJobManager
import com.labteto.dshmobile.local.memory.MemoryStore
import com.labteto.dshmobile.local.runtime.LocalKernelState
import com.labteto.dshmobile.local.runtime.LocalSessionRuntimeKind
import com.labteto.dshmobile.local.runtime.LocalSessionRuntimeLease
import com.labteto.dshmobile.local.runtime.LocalSessionRuntimeRegistry
import com.labteto.dshmobile.local.runtime.toLocalHarnessResourceState
import com.labteto.dshmobile.local.session.LocalConversationFilesCoordinator
import com.labteto.dshmobile.local.session.LocalConversationMode
import com.labteto.dshmobile.local.session.LocalSessionDomainCreateSpec
import com.labteto.dshmobile.local.session.LocalSessionDomainModeCommand
import com.labteto.dshmobile.local.session.LocalSessionCoordinator
import com.labteto.dshmobile.local.session.LocalSessionSummary
import com.labteto.dshmobile.local.work.LocalWorkSessionLifecyclePlanner
import com.labteto.dshmobile.local.session.LocalTranscriptRuntimeIndex
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Session navigation only waits for the visible runtime or another session transition.
 *
 * Session-owned Work runs are deliberately excluded so they can keep running after the user moves
 * to Chat or another conversation.
 */
internal fun localSessionNavigationBusy(
    sessionTransitioning: Boolean,
    visibleRunActive: Boolean,
): Boolean = sessionTransitioning || visibleRunActive

/** A background deletion leaves the visible session untouched. */
internal fun localDeletedCurrentSessionMode(
    currentSessionId: String,
    deletedIds: Set<String>,
    usageMode: LocalUsageMode,
): LocalUsageMode? = usageMode.takeIf { currentSessionId in deletedIds }

internal class LocalSessionLifecycleCoordinator(
    private val scope: CoroutineScope,
    private val state: com.labteto.dshmobile.local.runtime.LocalAggregateProjectionPort,
    private val transitionMutex: Mutex,
    private val jobs: LocalJobManager,
    private val sessionCoordinator: LocalSessionCoordinator,
    private val chatSessionLifecycle: LocalChatSessionLifecyclePlanner,
    private val approvalPreferences: LocalApprovalPreferences,
    private val resourceScheduler: HarnessResourceScheduler,
    private val handoffBuilder: ConversationHandoffBuilder,
    private val toolOutputStore: LocalToolOutputStore,
    private val sessionsRoot: File,
    private val conversationFilesCoordinator: LocalConversationFilesCoordinator,
    private val memoryStore: MemoryStore,
    private val diaryStore: ChatDiaryStore,
    private val currentSessionId: () -> String,
    private val activateSession: (String, Long?) -> Unit,
    private val beginTransition: () -> Boolean,
    private val endTransition: () -> Unit,
    private val navigationBusy: () -> Boolean,
    private val cancelActiveRunAndJoin: suspend () -> Unit,
    private val cancelWorkRunsAndJoin: suspend (Set<String>) -> Unit,
    private val resetModelHistory: () -> Unit,
    private val persist: () -> Unit,
    private val loadSession: suspend (String) -> Unit,
    private val restartInterruptedSafeJobs: () -> Unit,
    private val startNextQueuedTurnIfIdle: () -> Job?,
    private val sessionSummaries: () -> List<LocalSessionSummary>,
    private val beforeEventLogsDeleted: (Set<String>) -> Unit = {},
    private val activeProjectId: () -> String,
) {
    // Mode and history taps share the same latest destination while a session is restored.
    private val navigationLock = Any()
    private val navigationInbox = LocalSessionNavigationInbox()

    private fun continueQueuedNavigation() {
        synchronized(navigationLock) {
            if (state.value.loading) return
            val request = navigationInbox.take() ?: return
            val snapshot = state.value
            if (request is LocalSessionNavigationRequest.Mode &&
                snapshot.kernel.running && snapshot.usageMode == LocalUsageMode.CHAT &&
                request.value != LocalUsageMode.CHAT
            ) {
                state.update { it.copy(error = "请先结束当前聊天回复再切换模式") }
                return
            }
            val started = when (request) {
                is LocalSessionNavigationRequest.Mode -> startUsageModeSwitch(request.value)
                is LocalSessionNavigationRequest.Session -> startSessionSwitch(request.id)
            }
            if (!started) {
                if (state.value.loading || navigationBusy()) {
                    navigationInbox.offerIfEmpty(request)
                } else {
                    state.update { it.copy(error = "切换失败，请重试") }
                }
            }
        }
    }

    fun createSession(mode: LocalConversationMode) =
        createSession(mode, state.value.usageMode)

    fun createSession(
        mode: LocalConversationMode,
        usageMode: LocalUsageMode,
        domainSpec: LocalSessionDomainCreateSpec? = null,
        handoffSummaryOverride: String? = null,
    ): Boolean {
        if (!chatSessionLifecycle.acceptsCreateSpec(usageMode, domainSpec)) return false
        if (!beginTransition()) return false
        val sourceId = currentSessionId()
        val sourceState = state.value
        state.update {
            it.copy(
                loading = true,
                work = it.work.copy(
                    pendingApproval = null,
                    pendingQuestion = null,
                ),
                kernel = it.kernel.copy(running = false),
            )
        }
        scope.launch {
            transitionMutex.withLock {
                try {
                    // A Work turn owns its own session-bound runtime. Creating another conversation
                    // must not tear it down; it simply becomes a background run. Chat still uses the
                    // visible single-session runtime and keeps the old cancellation boundary.
                    val preserveWorkRun =
                        sourceState.usageMode == LocalUsageMode.WORK && sourceState.kernel.running
                    if (!preserveWorkRun) {
                        cancelActiveRunAndJoin()
                        jobs.stopOwnedNonPersistentAndJoin(setOf(sourceId))
                    }
                    persist()

                    val nextSessionId = UUID.randomUUID().toString()
                    activateSession(nextSessionId, -1L)
                    resetModelHistory()

                    val lineageId = when (mode) {
                        LocalConversationMode.CONTINUATION ->
                            sourceState.lineageId.ifBlank { sourceId }
                        LocalConversationMode.INDEPENDENT,
                        LocalConversationMode.PROJECT -> UUID.randomUUID().toString()
                    }
                    val projectId = when (mode) {
                        LocalConversationMode.INDEPENDENT -> null
                        LocalConversationMode.PROJECT -> activeProjectId()
                        LocalConversationMode.CONTINUATION -> sourceState.projectId
                    }
                    val chatPlan = chatSessionLifecycle.prepareCreate(
                        mode = mode,
                        usageMode = usageMode,
                        domainSpec = domainSpec,
                        sourceSessionId = sourceId,
                        nextSessionId = nextSessionId,
                        sourceUsageMode = sourceState.usageMode,
                        sourceChat = sourceState.chat,
                        sessionsRoot = sessionsRoot,
                    )
                    val handoff = if (mode == LocalConversationMode.CONTINUATION && handoffSummaryOverride != null) {
                        handoffSummaryOverride.trim().takeIf(String::isNotBlank)
                    } else when (val override = chatPlan.handoffOverride) {
                        null -> if (mode == LocalConversationMode.CONTINUATION) {
                            buildHandoffSummary(sourceState)
                        } else {
                            null
                        }
                        else -> override.value
                    }
                    state.update {
                        it.copy(
                            loading = false,
                            sessionId = nextSessionId,
                            usageMode = usageMode,
                            chat = chatPlan.chat,
                            conversationMode = mode,
                            parentSessionId = sourceId.takeIf {
                                mode == LocalConversationMode.CONTINUATION
                            },
                            lineageId = lineageId,
                            projectId = projectId,
                            handoffSummary = handoff,
                            messages = emptyList(),
                            transcriptIndex = LocalTranscriptRuntimeIndex(),
                            work = LocalWorkSessionLifecyclePlanner.initialState(
                                usageMode = usageMode,
                                sessionId = nextSessionId,
                                jobs = jobs.snapshotInfos(),
                            ),
                            safeAutoApprovalEnabled = approvalPreferences.isSafeAutoApprovalEnabled(),
                            kernel = LocalKernelState(
                                resources = resourceScheduler.snapshot().toLocalHarnessResourceState(usageMode),
                            ),
                            error = null,
                        )
                    }
                    persist()
                    restartInterruptedSafeJobs()
                } finally {
                    endTransition()
                    state.update { it.copy(loading = false) }
                }
            }
            continueQueuedNavigation()
        }
        return true
    }

    fun switchDomainMode(command: LocalSessionDomainModeCommand) {
        val snapshot = state.value
        if (snapshot.loading || snapshot.kernel.running) return
        applyChatModeRoute(
            chatSessionLifecycle.resolveModeCommand(
                command = command,
                usageMode = snapshot.usageMode,
                chat = snapshot.chat,
                sessions = snapshot.sessions,
            ),
        )
    }

    private fun applyChatModeRoute(route: LocalChatSessionModeRoute?) {
        when (route) {
            null,
            LocalChatSessionModeRoute.Noop -> Unit
            is LocalChatSessionModeRoute.Switch -> switchSession(route.sessionId)
            is LocalChatSessionModeRoute.Create -> createSession(
                mode = LocalConversationMode.INDEPENDENT,
                usageMode = LocalUsageMode.CHAT,
                domainSpec = route.spec,
            )
        }
    }

    fun switchUsageMode(mode: LocalUsageMode) {
        navigationInbox.offer(LocalSessionNavigationRequest.Mode(mode))
        continueQueuedNavigation()
    }

    private fun startUsageModeSwitch(mode: LocalUsageMode): Boolean {
        val snapshot = state.value
        if (snapshot.loading) return false
        // Work execution is session-owned and survives a change of visible mode.
        if (snapshot.kernel.running && snapshot.usageMode != LocalUsageMode.WORK &&
            mode != snapshot.usageMode
        ) return false
        if (mode == LocalUsageMode.CHAT && snapshot.usageMode == LocalUsageMode.CHAT) {
            applyChatModeRoute(
                chatSessionLifecycle.resolveSingleMode(
                    usageMode = snapshot.usageMode,
                    chat = snapshot.chat,
                    sessions = snapshot.sessions,
                ),
            )
            return true
        }
        if (snapshot.usageMode == mode) return true
        val target = if (mode == LocalUsageMode.CHAT) {
            chatSessionLifecycle.preferredSingleSession(snapshot.sessions)
        } else {
            snapshot.sessions.firstOrNull { it.usageMode == mode && !it.blank }
                ?: snapshot.sessions.firstOrNull { it.usageMode == mode }
        }
        return if (target != null) {
            startSessionSwitch(target.id)
        } else {
            createSession(
                mode = LocalConversationMode.INDEPENDENT,
                usageMode = mode,
                domainSpec = if (mode == LocalUsageMode.CHAT) {
                    chatSessionLifecycle.singleCreateSpec()
                } else null,
            )
        }
    }

    fun switchSession(sessionId: String): Boolean {
        if (sessionId != currentSessionId() &&
            state.value.sessions.none { it.id == sessionId }
        ) return false
        navigationInbox.offer(LocalSessionNavigationRequest.Session(sessionId))
        continueQueuedNavigation()
        return true
    }

    private fun startSessionSwitch(sessionId: String): Boolean {
        if (state.value.loading) return false
        if (sessionId == currentSessionId()) return true
        if (state.value.sessions.none { it.id == sessionId }) return false
        // The visible Chat run is cancelled; detached Work runs keep their ownership.
        val cancelVisibleChatRun = navigationBusy()
        if (!beginTransition()) return false
        state.update { it.copy(loading = true) }
        scope.launch {
            try {
                transitionMutex.withLock {
                    try {
                        if (sessionCoordinator.summaries().none { it.id == sessionId }) {
                        state.update { it.copy(error = "目标会话已不存在，请从历史记录重新选择") }
                        return@withLock
                    }
                    if (cancelVisibleChatRun) cancelActiveRunAndJoin()
                    persist()
                    activateSession(sessionId, null)
                    loadSession(sessionId)
                    restartInterruptedSafeJobs()
                    } finally {
                        endTransition()
                        state.update { it.copy(loading = false) }
                    }
                }
                startNextQueuedTurnIfIdle()?.start()
            } finally {
                // Even a failed restore must not strand a newer navigation tap.
                continueQueuedNavigation()
            }
        }
        return true
    }

    suspend fun deleteSessions(requestedIds: Set<String>): Int {
        if (requestedIds.isEmpty() || !beginTransition()) return 0
        state.update { it.copy(loading = true) }
        return try {
            transitionMutex.withLock {
                persist()
                val available = sessionCoordinator.summaries()
                val ids = available.map { it.id }.filterTo(linkedSetOf()) { it in requestedIds }
                if (ids.isEmpty()) return@withLock 0
                if (currentSessionId() in ids) {
                    cancelActiveRunAndJoin()
                }
                cancelWorkRunsAndJoin(ids)
                jobs.removeOwnedAndJoin(ids)
                // Hold the same per-session ownership used by foreground and Automation runs.
                // This waits for an in-flight Automation owner and prevents a queued one from
                // entering until durable deletion and the repository tombstone handoff finish.
                val deletionLeases = LocalSessionRuntimeRegistry.acquireAll(
                    ids,
                    LocalSessionRuntimeKind.SESSION_DELETE,
                )
                try {
                    val previous = state.value
                    val freshUsageMode = localDeletedCurrentSessionMode(
                        currentSessionId(), ids, previous.usageMode,
                    )
                    if (freshUsageMode != null) {
                        // Deletion must not auto-select previous history or switch Chat/Work.
                        val sourceId = currentSessionId()
                        val nextSessionId = UUID.randomUUID().toString()
                        val chatPlan = chatSessionLifecycle.prepareCreate(
                            mode = LocalConversationMode.INDEPENDENT,
                            usageMode = freshUsageMode,
                            domainSpec = if (freshUsageMode == LocalUsageMode.CHAT) {
                                chatSessionLifecycle.singleCreateSpec()
                            } else null,
                            sourceSessionId = sourceId,
                            nextSessionId = nextSessionId,
                            sourceUsageMode = previous.usageMode,
                            sourceChat = previous.chat,
                            sessionsRoot = sessionsRoot,
                        )
                        activateSession(nextSessionId, -1L)
                        resetModelHistory()
                        state.update {
                            it.copy(
                                sessionId = nextSessionId,
                                usageMode = freshUsageMode,
                                chat = chatPlan.chat,
                                conversationMode = LocalConversationMode.INDEPENDENT,
                                parentSessionId = null,
                                lineageId = UUID.randomUUID().toString(),
                                projectId = null,
                                handoffSummary = null,
                                messages = emptyList(),
                                transcriptIndex = LocalTranscriptRuntimeIndex(),
                                work = LocalWorkSessionLifecyclePlanner.initialState(
                                    usageMode = freshUsageMode,
                                    sessionId = nextSessionId,
                                    jobs = jobs.snapshotInfos(),
                                ),
                                safeAutoApprovalEnabled = approvalPreferences.isSafeAutoApprovalEnabled(),
                                kernel = LocalKernelState(
                                    resources = resourceScheduler.snapshot()
                                        .toLocalHarnessResourceState(freshUsageMode),
                                ),
                                error = null,
                            )
                        }
                    }
                    beforeEventLogsDeleted(ids)
                    withContext(Dispatchers.IO) {
                        ids.forEach { id ->
                            sessionCoordinator.delete(id)
                            toolOutputStore.deleteSession(id)
                            sessionsRoot.listFiles().orEmpty()
                                .filter {
                                    it.name == "$id.events.jsonl" ||
                                        it.name.startsWith("$id.events.jsonl.part-") ||
                                        it.name.startsWith("$id.events.jsonl.projection-")
                                }
                                .forEach(File::delete)
                        }
                    }
                    conversationFilesCoordinator.invalidate(ids)
                    memoryStore.detachSourceSessions(ids)
                    diaryStore.detachSourceSessions(ids)
                    // Every old producer is now outside the deletion barrier; once the tombstone is
                    // released, future Automation runs must re-resolve the session from storage.
                    sessionCoordinator.releaseDeletionBarrier(ids)
                    state.update { it.copy(sessions = sessionSummaries()) }
                    persist()
                    ids.size
                } finally {
                    deletionLeases.asReversed().forEach(LocalSessionRuntimeLease::close)
                }
            }
        } finally {
            // A mode tap queued during deletion must not navigate to an old session later.
            navigationInbox.clear()
            endTransition()
            state.update { it.copy(loading = false) }
        }
    }

    fun currentHandoffSummary(): String = buildHandoffSummary(state.value)

    private fun buildHandoffSummary(snapshot: LocalHarnessState): String =
        handoffBuilder.build(
            LocalWorkSessionLifecyclePlanner.handoffState(
                work = snapshot.work,
                messages = chatSessionLifecycle.handoffMessages(
                    chat = snapshot.chat,
                    messages = snapshot.messages,
                ),
            ),
        )

    companion object {
        const val SESSION_ID_PREFERENCE = "session_id"
    }
}

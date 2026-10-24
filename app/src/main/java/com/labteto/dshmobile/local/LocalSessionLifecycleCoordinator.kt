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
import com.labteto.dshmobile.local.session.LocalSessionDeletionCleanup
import com.labteto.dshmobile.local.session.LocalSessionSummary
import com.labteto.dshmobile.local.session.LocalPendingSessionRegistry
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

/** Delete background identities first: a partial failure must not delete the visible session. */
internal fun localSessionDeletionOrder(ids: Set<String>, currentSessionId: String): List<String> =
    ids.sortedBy { it == currentSessionId }

/** The log cleanup callback must never fire for an identity whose disk deletion failed. */
internal fun commitOrderedSessionDeletions(
    ids: Set<String>,
    currentSessionId: String,
    deleteDurably: (String) -> Unit,
    cleanupCommitted: (String) -> Unit,
) {
    localSessionDeletionOrder(ids, currentSessionId).forEach { id ->
        deleteDurably(id)
        cleanupCommitted(id)
    }
}

internal class LocalSessionLifecycleCoordinator(
    private val scope: CoroutineScope,
    private val state: com.labteto.dshmobile.local.runtime.LocalAggregateProjectionPort,
    private val transitionMutex: Mutex,
    private val jobs: LocalJobManager,
    private val sessionCoordinator: LocalSessionCoordinator,
    private val pendingSessions: LocalPendingSessionRegistry,
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
    private val recordInitialModelSelection: (String, String) -> Unit = { _, _ -> },
    private val beforeEventLogsDeleted: (Set<String>) -> Unit = {},
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
                is LocalSessionNavigationRequest.ChatDomain -> startChatDomainModeSwitch(request.command)
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
            try {
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
                    pendingSessions.remember(nextSessionId, usageMode)
                    sourceState.modelState.modelSelection.activeProfileId?.let { id ->
                        recordInitialModelSelection(nextSessionId, id)
                    }
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
                        LocalConversationMode.PROJECT -> null // 兼容历史模式，不再创建项目归属。
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
            } finally {
                // Keep the last user-selected target even if session creation fails.
                continueQueuedNavigation()
            }
        }
        return true
    }

    fun switchDomainMode(command: LocalSessionDomainModeCommand) {
        // Group/single switches also supersede any older mode or session tap.
        if (state.value.kernel.running && !state.value.loading) {
            state.update { it.copy(error = "请先结束当前回复，再切换单聊或群聊") }
            return
        }
        navigationInbox.offer(LocalSessionNavigationRequest.ChatDomain(command))
        // Admission may read an older session snapshot; never do that disk work on the tap thread.
        scope.launch { continueQueuedNavigation() }
    }

    private fun startChatDomainModeSwitch(command: LocalSessionDomainModeCommand): Boolean {
        val snapshot = state.value
        if (snapshot.loading || snapshot.kernel.running) return false
        return when (val route = chatSessionLifecycle.resolveModeCommand(
            command = command,
            usageMode = snapshot.usageMode,
            chat = snapshot.chat,
            sessions = snapshot.sessions,
        )) {
            null, LocalChatSessionModeRoute.Noop -> true
            is LocalChatSessionModeRoute.Switch -> startSessionSwitch(route.sessionId)
            is LocalChatSessionModeRoute.Create -> createSession(
                mode = LocalConversationMode.INDEPENDENT,
                usageMode = LocalUsageMode.CHAT,
                domainSpec = route.spec,
            )
        }
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
        // Resolve the latest destination on Session's IO owner, not Compose's main thread.
        scope.launch { continueQueuedNavigation() }
    }

    private fun startUsageModeSwitch(mode: LocalUsageMode): Boolean {
        val snapshot = state.value
        if (snapshot.loading) return false
        // Work execution is session-owned and survives a change of visible mode.
        if (snapshot.kernel.running && snapshot.usageMode != LocalUsageMode.WORK &&
            mode != snapshot.usageMode
        ) return false
        if (snapshot.usageMode == mode &&
            !(mode == LocalUsageMode.CHAT && snapshot.chat.groupChat.enabled)
        ) return true
        // Prefer the most recent pending editor, not an old completed conversation.
        val pendingId = pendingSessions.latestFor(mode)
            ?.takeIf { candidate ->
                val stored = sessionCoordinator.read(candidate)
                stored == null || (stored.usageMode == mode &&
                    stored.transcriptIndex.totalMessageCount == 0L &&
                    stored.transcriptWindow.isEmpty())
            }
        return if (pendingId != null) {
            startSessionSwitch(pendingId)
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

    private fun isKnownSession(sessionId: String): Boolean =
        pendingSessions.modeFor(sessionId) != null ||
            state.value.sessions.any { it.id == sessionId } ||
            sessionCoordinator.summaries().any { it.id == sessionId }

    fun switchSession(sessionId: String): Boolean {
        if (sessionId.isBlank()) return false
        // This synchronous UI/SessionUiPort entrypoint only queues the desired identity.
        // Validating a cold deep link may scan session JSON and must run on the Session IO
        // owner with the rest of navigation, never while the UI command lock is held.
        // A queued receipt means "will be resolved", not that the session exists.
        navigationInbox.offer(LocalSessionNavigationRequest.Session(sessionId))
        scope.launch { continueQueuedNavigation() }
        return true
    }

    private fun startSessionSwitch(sessionId: String): Boolean {
        if (state.value.loading) return false
        if (sessionId == currentSessionId()) return true
        if (!isKnownSession(sessionId)) return false
        // The visible Chat run is cancelled; detached Work runs keep their ownership.
        val cancelVisibleChatRun = navigationBusy()
        if (!beginTransition()) return false
        state.update { it.copy(loading = true) }
        scope.launch {
            try {
                transitionMutex.withLock {
                    try {
                        if (sessionCoordinator.summaries().none { it.id == sessionId } &&
                            pendingSessions.modeFor(sessionId) == null) {
                            state.update { it.copy(error = "目标会话已不存在，请从历史记录重新选择") }
                            return@withLock
                        }
                        // A superseding tap may arrive while waiting for the
                        // transition mutex or catalog check. Do not start an
                        // expensive restore the user will never see.
                        if (navigationInbox.hasPending()) return@withLock
                        if (cancelVisibleChatRun) cancelActiveRunAndJoin()
                        persist()
                        // Persisting the outgoing session may perform IO. Prefer the
                        // last requested destination before changing runtime identity.
                        if (navigationInbox.hasPending()) return@withLock
                        activateSession(sessionId, null)
                        loadSession(sessionId)
                        restartInterruptedSafeJobs()
                    } finally {
                        endTransition()
                        state.update { it.copy(loading = false) }
                    }
                }
                // Do not admit work for a now-unwanted foreground session when
                // a newer navigation is already queued.
                if (!navigationInbox.hasPending()) startNextQueuedTurnIfIdle()?.start()
            } finally {
                // Even a failed restore must not strand a newer navigation tap.
                continueQueuedNavigation()
            }
        }
        return true
    }

    suspend fun deleteSessions(requestedIds: Set<String>): Int = deleteSessionIds(requestedIds).size

    suspend fun deleteSessionIds(requestedIds: Set<String>): Set<String> {
        if (requestedIds.isEmpty() || !beginTransition()) return emptySet()
        state.update { it.copy(loading = true) }
        return try {
            transitionMutex.withLock {
                persist()
                val available = sessionCoordinator.summaries()
                val ids = available.map { it.id }.filterTo(linkedSetOf()) { it in requestedIds }
                if (ids.isEmpty()) return@withLock emptySet<String>()
                if (currentSessionId() in ids) {
                    cancelActiveRunAndJoin()
                }
                cancelWorkRunsAndJoin(ids)
                // First stop execution, but retain its receipts until a session is durably deleted.
                // A failed unlink must not irreversibly erase the surviving session's job history.
                jobs.stopOwnedAndJoin(ids)
                // Hold the same per-session ownership used by foreground and Automation runs.
                // This waits for an in-flight Automation owner and prevents a queued one from
                // entering until durable deletion and the repository tombstone handoff finish.
                val deletionLeases = LocalSessionRuntimeRegistry.acquireAll(
                    ids,
                    LocalSessionRuntimeKind.SESSION_DELETE,
                )
                try {
                    val previous = state.value
                    val sourceId = currentSessionId()
                    val freshUsageMode = localDeletedCurrentSessionMode(
                        sourceId, ids, previous.usageMode,
                    )
                    // A replacement may be prepared, but must not become visible
                    // before the durable deletion of its predecessor has completed.
                    val preparedReplacement = freshUsageMode?.let { usageMode ->
                        val nextSessionId = UUID.randomUUID().toString()
                        val chatPlan = chatSessionLifecycle.prepareCreate(
                            mode = LocalConversationMode.INDEPENDENT,
                            usageMode = usageMode,
                            domainSpec = if (usageMode == LocalUsageMode.CHAT) {
                                chatSessionLifecycle.singleCreateSpec()
                            } else null,
                            sourceSessionId = sourceId,
                            nextSessionId = nextSessionId,
                            sourceUsageMode = previous.usageMode,
                            sourceChat = previous.chat,
                            sessionsRoot = sessionsRoot,
                        )
                        nextSessionId to chatPlan
                    }
                    fun activateReplacementIfNeeded() {
                        val (nextSessionId, chatPlan) = preparedReplacement ?: return
                        if (currentSessionId() != sourceId) return
                        val mode = requireNotNull(freshUsageMode)
                        pendingSessions.remember(nextSessionId, mode)
                        previous.modelState.modelSelection.activeProfileId?.let { id ->
                            recordInitialModelSelection(nextSessionId, id)
                        }
                        activateSession(nextSessionId, -1L)
                        resetModelHistory()
                        state.update {
                            it.copy(
                                sessionId = nextSessionId,
                                usageMode = mode,
                                chat = chatPlan.chat,
                                conversationMode = LocalConversationMode.INDEPENDENT,
                                parentSessionId = null,
                                lineageId = UUID.randomUUID().toString(),
                                projectId = null,
                                handoffSummary = null,
                                messages = emptyList(),
                                transcriptIndex = LocalTranscriptRuntimeIndex(),
                                work = LocalWorkSessionLifecyclePlanner.initialState(
                                    usageMode = mode,
                                    sessionId = nextSessionId,
                                    jobs = jobs.snapshotInfos(),
                                ),
                                safeAutoApprovalEnabled = approvalPreferences.isSafeAutoApprovalEnabled(),
                                kernel = LocalKernelState(
                                    resources = resourceScheduler.snapshot()
                                        .toLocalHarnessResourceState(mode),
                                ),
                                error = null,
                            )
                        }
                    }
                    val deletionCleanup = LocalSessionDeletionCleanup(
                        sessionsRoot = sessionsRoot,
                        clearEvents = beforeEventLogsDeleted,
                        clearToolOutputs = toolOutputStore::deleteSession,
                    )
                    val durableDeletedIds = linkedSetOf<String>()
                    try {
                        withContext(Dispatchers.IO) {
                            // Delete background sessions first; current session last.
                            // On a partial deletion failure the visible conversation remains intact.
                            commitOrderedSessionDeletions(
                                ids = ids,
                                currentSessionId = sourceId,
                                deleteDurably = { id ->
                                    deletionCleanup.markIntent(id)
                                    sessionCoordinator.delete(id)
                                    durableDeletedIds += id
                                },
                                cleanupCommitted = deletionCleanup::cleanupCommitted,
                            )
                        }
                        // Forget jobs only for identities confirmed deleted on disk.
                        jobs.removeOwnedAndJoin(durableDeletedIds)
                        activateReplacementIfNeeded()
                        conversationFilesCoordinator.invalidate(ids)
                        memoryStore.detachSourceSessions(ids)
                        diaryStore.detachSourceSessions(ids)
                        pendingSessions.forget(ids)
                        state.update { it.copy(sessions = sessionSummaries()) }
                        persist()
                        ids.toSet()
                    } catch (failure: Exception) {
                        // Some background sessions may already be deleted. Reconcile ONLY these
                        // committed identities; otherwise stale caches/pending objects may still
                        // expose a deleted conversation in this process.
                        if (durableDeletedIds.isNotEmpty()) {
                            try {
                                jobs.removeOwnedAndJoin(durableDeletedIds)
                            } catch (cleanupFailure: Exception) {
                                failure.addSuppressed(cleanupFailure)
                            }
                            try {
                                conversationFilesCoordinator.invalidate(durableDeletedIds)
                                pendingSessions.forget(durableDeletedIds)
                                memoryStore.detachSourceSessions(durableDeletedIds)
                                diaryStore.detachSourceSessions(durableDeletedIds)
                            } catch (cleanupFailure: Exception) {
                                failure.addSuppressed(cleanupFailure)
                            }
                        }
                        if (sourceId in durableDeletedIds) activateReplacementIfNeeded()
                        state.update {
                            it.copy(
                                sessions = sessionSummaries(),
                                error = "会话删除未完成；部分后台任务可能已停止，请核实后重试",
                            )
                        }
                        throw failure
                    } finally {
                        // Release fences only for identities whose disk deletion succeeded.
                        sessionCoordinator.releaseDeletionBarrier(durableDeletedIds)
                    }
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

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
import com.labteto.dshmobile.local.session.LocalSessionSummary
import com.labteto.dshmobile.local.work.LocalWorkSessionLifecyclePlanner
import com.labteto.dshmobile.local.session.LocalTranscriptRuntimeIndex
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
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

internal class LocalSessionLifecycleCoordinator(
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<LocalHarnessState>,
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
    private val localProjectId: String,
) {
    // Keep only the user's latest tap while a session is being persisted and restored.
    private val queuedUsageMode = AtomicReference<LocalUsageMode?>(null)

    private fun continueQueuedModeSwitch() {
        val requested = queuedUsageMode.getAndSet(null) ?: return
        if (requested != state.value.usageMode) switchUsageMode(requested)
    }

    fun createSession(mode: LocalConversationMode) =
        createSession(mode, state.value.usageMode)

    fun createSession(
        mode: LocalConversationMode,
        usageMode: LocalUsageMode,
        domainSpec: LocalSessionDomainCreateSpec? = null,
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
                        LocalConversationMode.PROJECT -> sourceState.projectId ?: localProjectId
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
                    val handoff = when (val override = chatPlan.handoffOverride) {
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
            continueQueuedModeSwitch()
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
        val snapshot = state.value
        val busy = navigationBusy()
        if (snapshot.loading && !busy) return
        if (snapshot.loading || busy) {
            queuedUsageMode.set(mode)
            return
        }
        // Work can continue in its session-bound runtime while the user moves to Chat. A running
        // Chat turn still owns the visible runtime and therefore keeps the existing guard.
        if (snapshot.kernel.running && snapshot.usageMode != LocalUsageMode.WORK) return
        if (mode == LocalUsageMode.CHAT && snapshot.usageMode == LocalUsageMode.CHAT) {
            applyChatModeRoute(
                chatSessionLifecycle.resolveSingleMode(
                    usageMode = snapshot.usageMode,
                    chat = snapshot.chat,
                    sessions = snapshot.sessions,
                ),
            )
            return
        }
        if (snapshot.usageMode == mode) return
        val target = if (mode == LocalUsageMode.CHAT) {
            chatSessionLifecycle.preferredSingleSession(snapshot.sessions)
        } else {
            snapshot.sessions.firstOrNull {
                it.usageMode == mode && !it.blank
            } ?: snapshot.sessions.firstOrNull {
                it.usageMode == mode
            }
        }
        val accepted = if (target != null) {
            switchSession(target.id)
        } else {
            createSession(
                mode = LocalConversationMode.INDEPENDENT,
                usageMode = mode,
                domainSpec = if (mode == LocalUsageMode.CHAT) {
                    chatSessionLifecycle.singleCreateSpec()
                } else {
                    null
                },
            )
        }
        if (!accepted) {
            queuedUsageMode.set(mode)
            if (!state.value.loading && !navigationBusy()) continueQueuedModeSwitch()
        }
    }

    fun switchSession(sessionId: String): Boolean {
        if (sessionId == currentSessionId()) return true
        // Work turns are session-owned: changing the visible conversation only changes the UI
        // projection. Never stop the Work turn, its subagents, terminals or non-persistent jobs here.
        // Chat still uses the legacy visible turn slot; if one is active, close it safely before the
        // shared Chat runtime is replaced.
        val cancelVisibleChatRun = navigationBusy()
        if (!beginTransition()) return false
        state.update { it.copy(loading = true) }
        scope.launch {
            transitionMutex.withLock {
                try {
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
            continueQueuedModeSwitch()
            startNextQueuedTurnIfIdle()?.start()
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
                    if (currentSessionId() in ids) {
                        val previous = state.value
                        val replacement = available.firstOrNull {
                            it.id !in ids && it.usageMode == previous.usageMode
                        } ?: available.firstOrNull { it.id !in ids }
                        val nextSessionId = replacement?.id ?: UUID.randomUUID().toString()
                        activateSession(nextSessionId, null)
                        loadSession(nextSessionId)
                        if (replacement == null) {
                            state.update {
                                it.copy(
                                    usageMode = previous.usageMode,
                                    chat = it.chat.copy(
                                        personaId = previous.chat.personaId,
                                        chatPersona = previous.chat.chatPersona,
                                    ),
                                )
                            }
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
                                        it.name.startsWith("$id.events.jsonl.part-")
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
            endTransition()
            state.update { it.copy(loading = false) }
        }
    }

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

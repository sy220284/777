package com.labteto.dshmobile.local

import com.labteto.dshmobile.harness.resource.HarnessResourceScheduler
import com.labteto.dshmobile.harness.session.ConversationHandoffBuilder
import com.labteto.dshmobile.harness.session.HandoffGoal
import com.labteto.dshmobile.harness.session.HandoffMessage
import com.labteto.dshmobile.harness.session.HandoffState
import com.labteto.dshmobile.harness.session.HandoffTodo
import com.labteto.dshmobile.local.chat.ChatCharacterState
import com.labteto.dshmobile.local.chat.ChatContextState
import com.labteto.dshmobile.local.chat.withLegacyFallback
import com.labteto.dshmobile.local.chat.ChatPersonaStore
import com.labteto.dshmobile.local.chat.PersonaGalleryEntry
import com.labteto.dshmobile.local.chat.PersonaProfile
import com.labteto.dshmobile.local.memory.MemoryStore
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

internal class LocalSessionLifecycleCoordinator(
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<LocalHarnessState>,
    private val transitionMutex: Mutex,
    private val jobs: LocalJobManager,
    private val sessionCoordinator: LocalSessionCoordinator,
    private val chatPersonaStore: ChatPersonaStore,
    private val approvalPreferences: LocalApprovalPreferences,
    private val resourceScheduler: HarnessResourceScheduler,
    private val handoffBuilder: ConversationHandoffBuilder,
    private val toolOutputStore: LocalToolOutputStore,
    private val sessionsRoot: File,
    private val conversationFilesCoordinator: LocalConversationFilesCoordinator,
    private val memoryStore: MemoryStore,
    private val currentSessionId: () -> String,
    private val activateSession: (String, Long?) -> Unit,
    private val beginTransition: () -> Boolean,
    private val endTransition: () -> Unit,
    private val runBusy: () -> Boolean,
    private val cancelActiveRunAndJoin: suspend () -> Unit,
    private val resetModelHistory: () -> Unit,
    private val persist: () -> Unit,
    private val loadSession: suspend (String) -> Unit,
    private val restartInterruptedSafeJobs: () -> Unit,
    private val startNextQueuedTurnIfIdle: () -> Job?,
    private val sessionSummaries: () -> List<LocalSessionSummary>,
    private val localProjectId: String,
) {
    fun createSession(mode: LocalConversationMode) =
        createSession(mode, state.value.usageMode)

    fun createSession(
        mode: LocalConversationMode,
        usageMode: LocalUsageMode,
        galleryEntry: PersonaGalleryEntry? = null,
        galleryStoryId: String? = null,
        freshGalleryStory: Boolean = false,
        chatMode: LocalChatMode? = null,
    ) {
        if (!beginTransition()) return
        val sourceId = currentSessionId()
        val sourceState = state.value
        val resolvedChatMode = when {
            usageMode != LocalUsageMode.CHAT -> LocalChatMode.SINGLE
            galleryEntry != null -> LocalChatMode.SINGLE
            chatMode != null -> chatMode
            sourceState.usageMode == LocalUsageMode.CHAT -> sourceState.groupChat.mode
            else -> LocalChatMode.SINGLE
        }
        state.update {
            it.copy(
                loading = true,
                running = false,
                pendingApproval = null,
                pendingQuestion = null,
            )
        }
        scope.launch {
            transitionMutex.withLock {
                try {
                    cancelActiveRunAndJoin()
                    jobs.stopNonPersistentAndJoin()
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
                    val selectedGalleryStory = galleryEntry?.story(galleryStoryId)
                    val handoff = if (galleryEntry != null && !freshGalleryStory) {
                        selectedGalleryStory?.context(galleryEntry.persona.name).orEmpty()
                    } else if (mode == LocalConversationMode.CONTINUATION) {
                        if (
                            usageMode == LocalUsageMode.CHAT &&
                            sourceState.usageMode == LocalUsageMode.CHAT
                        ) {
                            buildChatContinuationHandoff(
                                state = sourceState.chatState,
                                messages = sourceState.messages,
                                context = sourceState.chatContext,
                            )
                        } else {
                            buildHandoffSummary(sourceState)
                        }
                    } else {
                        null
                    }
                    val personaId = if (resolvedChatMode == LocalChatMode.GROUP) {
                        PersonaProfile.DEFAULT_PERSONA_ID
                    } else if (galleryEntry != null) {
                        chatPersonaStore.upsert(
                            galleryEntry.persona.copy(id = "persona-${UUID.randomUUID()}"),
                        ).id
                    } else if (
                        usageMode == LocalUsageMode.CHAT &&
                        sourceState.usageMode == LocalUsageMode.CHAT &&
                        !sourceState.groupChat.enabled
                    ) {
                        sourceState.personaId
                    } else {
                        PersonaProfile.DEFAULT_PERSONA_ID
                    }
                    val chatPersona = chatPersonaStore.get(personaId)
                    val chatState = if (resolvedChatMode == LocalChatMode.GROUP) {
                        ChatCharacterState()
                    } else if (
                        galleryEntry != null &&
                        usageMode == LocalUsageMode.CHAT &&
                        !freshGalleryStory
                    ) {
                        selectedGalleryStory?.chatState ?: ChatCharacterState()
                    } else if (
                        usageMode == LocalUsageMode.CHAT &&
                        mode == LocalConversationMode.CONTINUATION &&
                        sourceState.usageMode == LocalUsageMode.CHAT
                    ) {
                        sourceState.chatState
                    } else {
                        ChatCharacterState()
                    }

                    val chatContext = when {
                        resolvedChatMode == LocalChatMode.GROUP -> ChatContextState()
                        galleryEntry != null && usageMode == LocalUsageMode.CHAT && !freshGalleryStory ->
                            ChatContextState().withLegacyFallback(chatState)
                        usageMode == LocalUsageMode.CHAT &&
                            mode == LocalConversationMode.CONTINUATION &&
                            sourceState.usageMode == LocalUsageMode.CHAT &&
                            !sourceState.groupChat.enabled -> sourceState.chatContext
                        else -> ChatContextState()
                    }

                    state.update {
                        it.copy(
                            loading = false,
                            sessionId = nextSessionId,
                            usageMode = usageMode,
                            personaId = personaId,
                            galleryId = if (resolvedChatMode == LocalChatMode.GROUP) {
                                null
                            } else {
                                galleryEntry?.id ?: sourceState.galleryId.takeIf {
                                    usageMode == LocalUsageMode.CHAT &&
                                        sourceState.usageMode == LocalUsageMode.CHAT &&
                                        !sourceState.groupChat.enabled
                                }
                            },
                            galleryStoryId = when {
                                resolvedChatMode == LocalChatMode.GROUP -> null
                                galleryEntry != null && !freshGalleryStory -> selectedGalleryStory?.id
                                galleryEntry != null -> null
                                usageMode == LocalUsageMode.CHAT &&
                                    sourceState.usageMode == LocalUsageMode.CHAT &&
                                    mode == LocalConversationMode.CONTINUATION -> sourceState.galleryStoryId
                                else -> null
                            },
                            gallerySaveSuppressedThrough = 0L,
                            chatPersona = chatPersona,
                            chatState = chatState,
                            chatContext = chatContext,
                            replySuggestions = emptyList(),
                            chatBranches = LocalChatBranchState(),
                            groupChat = if (resolvedChatMode == LocalChatMode.GROUP) {
                                if (
                                    mode == LocalConversationMode.CONTINUATION &&
                                    sourceState.usageMode == LocalUsageMode.CHAT &&
                                    sourceState.groupChat.enabled
                                ) {
                                    sourceState.groupChat
                                } else {
                                    LocalGroupChatState(mode = LocalChatMode.GROUP)
                                }
                            } else {
                                LocalGroupChatState()
                            },
                            groupActiveSpeakerName = null,
                            personaCorrectionNotice = null,
                            conversationMode = mode,
                            parentSessionId = sourceId.takeIf {
                                mode == LocalConversationMode.CONTINUATION
                            },
                            lineageId = lineageId,
                            projectId = projectId,
                            handoffSummary = handoff,
                            messages = emptyList(),
                            transcriptIndex = LocalTranscriptRuntimeIndex(),
                            plan = emptyList(),
                            todos = emptyList(),
                            goal = null,
                            planMode = false,
                            safeAutoApprovalEnabled = approvalPreferences.isSafeAutoApprovalEnabled(),
                            deviceApprovalLease = false,
                            jobs = projectExecutionJobs(usageMode, jobs.snapshotInfos()),
                            activeAgents = projectWorkResourceCount(
                                usageMode,
                                resourceScheduler.snapshot().activeAgents,
                            ),
                            activeTerminals = projectWorkResourceCount(
                                usageMode,
                                resourceScheduler.snapshot().activeTerminals,
                            ),
                            activeVirtualDisplays = projectWorkResourceCount(
                                usageMode,
                                resourceScheduler.snapshot().activeVirtualDisplays,
                            ),
                            activeLanguageServers = projectWorkResourceCount(
                                usageMode,
                                resourceScheduler.snapshot().activeLanguageServers,
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
        }
    }

    fun switchChatMode(mode: LocalChatMode) {
        val snapshot = state.value
        if (snapshot.loading || snapshot.running) return
        if (
            snapshot.usageMode == LocalUsageMode.CHAT &&
            snapshot.groupChat.mode == mode
        ) return

        val target = snapshot.sessions.firstOrNull {
            it.usageMode == LocalUsageMode.CHAT && it.chatMode == mode && !it.blank
        } ?: snapshot.sessions.firstOrNull {
            it.usageMode == LocalUsageMode.CHAT && it.chatMode == mode
        }
        if (target != null) {
            switchSession(target.id)
        } else {
            createSession(
                mode = LocalConversationMode.INDEPENDENT,
                usageMode = LocalUsageMode.CHAT,
                chatMode = mode,
            )
        }
    }

    fun switchUsageMode(mode: LocalUsageMode) {
        val snapshot = state.value
        if (snapshot.loading || snapshot.running) return
        if (mode == LocalUsageMode.CHAT && snapshot.usageMode == LocalUsageMode.CHAT) {
            if (snapshot.groupChat.enabled) switchChatMode(LocalChatMode.SINGLE)
            return
        }
        if (snapshot.usageMode == mode) return
        val target = snapshot.sessions.firstOrNull {
            it.usageMode == mode &&
                (mode != LocalUsageMode.CHAT || it.chatMode == LocalChatMode.SINGLE) &&
                !it.blank
        } ?: snapshot.sessions.firstOrNull {
            it.usageMode == mode &&
                (mode != LocalUsageMode.CHAT || it.chatMode == LocalChatMode.SINGLE)
        }
        if (target != null) {
            switchSession(target.id)
        } else {
            createSession(
                mode = LocalConversationMode.INDEPENDENT,
                usageMode = mode,
                chatMode = if (mode == LocalUsageMode.CHAT) LocalChatMode.SINGLE else null,
            )
        }
    }

    fun switchSession(sessionId: String) {
        if (sessionId == currentSessionId() || runBusy()) return
        if (!beginTransition()) return
        state.update { it.copy(loading = true) }
        scope.launch {
            transitionMutex.withLock {
                try {
                    persist()
                    jobs.stopNonPersistentAndJoin()
                    activateSession(sessionId, null)
                    loadSession(sessionId)
                    restartInterruptedSafeJobs()
                } finally {
                    endTransition()
                    state.update { it.copy(loading = false) }
                }
            }
            startNextQueuedTurnIfIdle()?.start()
        }
    }

    suspend fun deleteSessions(requestedIds: Set<String>): Int {
        if (requestedIds.isEmpty() || !beginTransition()) return 0
        state.update { it.copy(loading = true) }
        return try {
            transitionMutex.withLock {
                cancelActiveRunAndJoin()
                jobs.stopNonPersistentAndJoin()
                persist()
                val available = sessionCoordinator.summaries()
                val ids = available.map { it.id }.filterTo(linkedSetOf()) { it in requestedIds }
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
                                personaId = previous.personaId,
                                chatPersona = previous.chatPersona,
                            )
                        }
                    }
                }
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
                state.update { it.copy(sessions = sessionSummaries()) }
                persist()
                ids.size
            }
        } finally {
            endTransition()
            state.update { it.copy(loading = false) }
        }
    }

    private fun buildHandoffSummary(snapshot: LocalHarnessState): String =
        handoffBuilder.build(
            HandoffState(
                goal = snapshot.goal?.let { goal ->
                    HandoffGoal(goal.status, goal.description)
                },
                plan = snapshot.plan,
                todos = snapshot.todos.map { todo ->
                    HandoffTodo(todo.status, todo.content)
                },
                messages = snapshot.messages.map { message ->
                    HandoffMessage(
                        message.role,
                        if (
                            snapshot.groupChat.enabled &&
                            message.role == "assistant"
                        ) {
                            groupTranscriptLine(message)
                        } else {
                            message.content
                        },
                    )
                },
            ),
        )

    companion object {
        const val SESSION_ID_PREFERENCE = "session_id"
    }
}

package com.labteto.dshmobile.data

import android.util.Base64
import com.labteto.dshmobile.observability.AppLog
import com.labteto.dshmobile.connection.ConnectionManager
import com.labteto.dshmobile.connection.ConnectionPhase
import com.labteto.dshmobile.connection.HostsStore
import com.labteto.dshmobile.core.session.ChunkRows
import com.labteto.dshmobile.core.session.ConversationSnapshot
import com.labteto.dshmobile.core.session.EventFold
import com.labteto.dshmobile.core.session.QueueItem
import com.labteto.dshmobile.core.session.SessionEventEnvelope
import com.labteto.dshmobile.core.wire.DshApiClient
import com.labteto.dshmobile.core.wire.RpcResult
import com.labteto.dshmobile.core.wire.decodeFromJsonElement
import com.labteto.dshmobile.core.wire.dto.APPROVAL_REQUEST_EVENT
import com.labteto.dshmobile.core.wire.dto.AgentPresetListValue
import com.labteto.dshmobile.core.wire.dto.ApprovalOutcome
import com.labteto.dshmobile.core.wire.dto.ApprovalRequestEvent
import com.labteto.dshmobile.core.wire.dto.AskUserQuestionAnswer
import com.labteto.dshmobile.core.wire.dto.AskUserQuestionIntent
import com.labteto.dshmobile.core.wire.dto.AskUserQuestionItem
import com.labteto.dshmobile.core.wire.dto.AskUserQuestionRequestEvent
import com.labteto.dshmobile.core.wire.dto.CommandDescriptor
import com.labteto.dshmobile.core.wire.dto.ContextBreakdownView
import com.labteto.dshmobile.core.wire.dto.ContextPressureView
import com.labteto.dshmobile.core.wire.dto.EncodedFileUploadRequest
import com.labteto.dshmobile.core.wire.dto.EncodedImageAttachment
import com.labteto.dshmobile.core.wire.dto.FileUploadValue
import com.labteto.dshmobile.core.wire.dto.GoalRef
import com.labteto.dshmobile.core.wire.dto.GoalSnapshot
import com.labteto.dshmobile.core.wire.dto.HostDescription
import com.labteto.dshmobile.core.wire.dto.ImageLimitsView
import com.labteto.dshmobile.core.wire.dto.ImageRejection
import com.labteto.dshmobile.core.wire.dto.JobView
import com.labteto.dshmobile.core.wire.dto.PermissionSelect
import com.labteto.dshmobile.core.wire.dto.PlanStateView
import com.labteto.dshmobile.core.wire.dto.PluginInventorySnapshot
import com.labteto.dshmobile.core.wire.dto.QUESTION_CANCELLED
import com.labteto.dshmobile.core.wire.dto.ModelSelectionProjection
import kotlinx.coroutines.flow.combine
import com.labteto.dshmobile.core.wire.dto.ModelCatalog
import com.labteto.dshmobile.core.wire.dto.QueuedInboxItem
import com.labteto.dshmobile.core.wire.dto.RemoteEventFrame
import com.labteto.dshmobile.core.wire.dto.RemoteEventOutcome
import com.labteto.dshmobile.core.wire.dto.RemoteEventRejection
import com.labteto.dshmobile.core.wire.dto.SessionAddress
import com.labteto.dshmobile.core.wire.dto.SessionAttachmentRequest
import com.labteto.dshmobile.core.wire.dto.SessionControlFrame
import com.labteto.dshmobile.core.wire.dto.SessionEvent
import com.labteto.dshmobile.core.wire.dto.SessionFollowFrame
import com.labteto.dshmobile.core.wire.dto.SessionHistoryRecord
import com.labteto.dshmobile.core.wire.dto.SessionModelsValue
import com.labteto.dshmobile.core.wire.dto.SessionPageRequest
import com.labteto.dshmobile.core.wire.dto.SessionSelectModelRequest
import com.labteto.dshmobile.core.wire.dto.SessionStatsView
import com.labteto.dshmobile.core.wire.dto.SessionSummary
import com.labteto.dshmobile.core.wire.dto.SkillEntry
import com.labteto.dshmobile.core.wire.dto.SkillListRequest
import com.labteto.dshmobile.core.wire.dto.SubagentListEntry
import com.labteto.dshmobile.core.wire.dto.TokenUsageView
import com.labteto.dshmobile.core.wire.dto.USER_QUESTIONS_REQUEST_EVENT
import com.labteto.dshmobile.core.wire.dto.WorkspaceFollowFrame
import com.labteto.dshmobile.core.wire.dto.WorkspaceView
import com.labteto.dshmobile.core.wire.RpcError
import com.labteto.dshmobile.core.wire.TransportFailures
import com.labteto.dshmobile.core.wire.encodeToJsonElement
import java.io.InputStream
import java.io.OutputStream
import java.util.TimeZone
import com.labteto.dshmobile.core.wire.dto.PermissionCatalog
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Single source of truth for the connected harness's live state. All public surface is
 * [StateFlow]; every RPC error becomes [connectionError] and never throws. The store survives
 * reconnects by re-baselining on the connection state transition and on `session/subscribed`.
 */
@Singleton
class SessionStore @Inject constructor(
    private val connectionManager: ConnectionManager,
    private val hostsStore: HostsStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()
    private val baselineRefreshGate = ConflatedRefreshGate()
    val connectionState = connectionManager.state
    val activeHostKey: String? get() = connectionManager.state.value.host?.let { "${it.baseUrl}|${it.id}" }
    internal val panels = com.labteto.dshmobile.ui.screens.main.PanelRepository()
    internal val composers = com.labteto.dshmobile.ui.screens.main.ComposerRepository(CoroutineScope(scope.coroutineContext + Dispatchers.Main.immediate))
    fun apiForHost(key: String?): DshApiClient? = if (key != null && key == activeHostKey) connectionManager.connectedApi else null
    fun muxForHost(key: String?) = if (key != null && key == activeHostKey) connectionManager.generation?.mux else null
    fun retryConnection() = connectionManager.reconnectIfNeeded()
    private val attachmentTransfer = SessionAttachmentTransfer(
        apiForHost = ::apiForHost,
        onConnectionError = ::setConnectionError,
        logger = ::log,
    )
    private val queuesBySession = MutableStateFlow<Map<String, List<QueueItem>>>(emptyMap())
    val sessionQueues: StateFlow<Map<String, List<QueueItem>>> = queuesBySession.asStateFlow()


    /** Coalesces transcript rebuilds during a stream; see [observeRebuildTicks]. */
    private val rebuildTicks = Channel<Unit>(Channel.CONFLATED)

    // ------------------------------------------------------------------ public StateFlows
    private val _sessions = MutableStateFlow<List<SessionRow>>(emptyList())
    val sessions: StateFlow<List<SessionRow>> = _sessions.asStateFlow()

    private val _workspaces = MutableStateFlow<List<WorkspaceRow>>(emptyList())
    val workspaces: StateFlow<List<WorkspaceRow>> = _workspaces.asStateFlow()

    private val _archivedSessionIds = MutableStateFlow<Set<String>>(emptySet())
    val archivedSessionIds: StateFlow<Set<String>> = _archivedSessionIds.asStateFlow()

    private val _currentSessionId = MutableStateFlow<String?>(null)
    val currentSessionId: StateFlow<String?> = _currentSessionId.asStateFlow()

    private val landingRuntime = SessionLandingRuntime(
        activeHostKey = { activeHostKey },
        currentSessionId = { _currentSessionId.value },
        persist = hostsStore::setLastSessionId,
        logger = ::log,
    )

    private val catalogs = SessionCatalogRuntime(
        apiForHost = ::apiForHost,
        activeHostKey = { activeHostKey },
        currentSessionId = { _currentSessionId.value },
        onConnectionError = ::setConnectionError,
        logger = ::log,
    )

    private val workspaceRuntime = SessionWorkspaceRuntime(
        apiProvider = ::apiOrNull,
        apiForHost = ::apiForHost,
        activeHostKey = { activeHostKey },
        onWorkspaceUpsert = ::upsertWorkspace,
        onWorkspaceRemove = ::removeWorkspace,
        onArchivedChanged = ::setArchived,
        refreshSessions = ::refreshSessions,
        onConnectionError = ::setConnectionError,
    )

    private val goalRuntime = SessionGoalRuntime(
        apiProvider = ::apiOrNull,
        currentSessionId = { currentSessionId.value },
        currentGoalRef = { synchronized(lock) { goalRefFromProjectionLocked() } },
        onConnectionError = ::setConnectionError,
        logger = ::log,
    )

    private val sessionLifecycleRuntime = SessionLifecycleRuntime(
        apiProvider = ::apiOrNull,
        reusableBlankSession = { workspaceId ->
            synchronized(lock) { indexState.reusableBlankSession(workspaceId) }
        },
        refreshSessions = ::refreshSessions,
        openSession = ::openSession,
        onTitleChanged = ::setTitle,
        onConnectionError = ::setConnectionError,
    )

    private val searchRuntime = SessionSearchRuntime(apiProvider = ::apiOrNull)
    val searchResults: StateFlow<List<Pair<String, String>>> get() = searchRuntime.results
    val contentSearchAvailable: StateFlow<Boolean> get() = searchRuntime.available

    private val turnCommandRuntime = SessionTurnCommandRuntime(
        apiForHost = ::apiForHost,
        apiProvider = ::apiOrNull,
        currentSessionId = { _currentSessionId.value },
        activeHostKey = { activeHostKey },
        onConnectionError = ::setConnectionError,
    )

    private val _currentConversation = MutableStateFlow<ConversationSnapshot?>(null)
    val currentConversation: StateFlow<ConversationSnapshot?> = _currentConversation.asStateFlow()

    private val _jobs = MutableStateFlow<List<JobView>>(emptyList())
    val jobs: StateFlow<List<JobView>> = _jobs.asStateFlow()

    val skills: StateFlow<List<SkillEntry>> = catalogs.skills
    val skillsLoading: StateFlow<Boolean> = catalogs.skillsLoading
    val modelsLoading: StateFlow<Boolean> = catalogs.modelsLoading

    /** The host generation's routable model catalog, before the session's own selection is joined in. */
    val modelCatalog: StateFlow<ModelCatalog?> = catalogs.modelCatalog

    private val _hostInfo = MutableStateFlow<HostDescription?>(null)
    val hostInfo: StateFlow<HostDescription?> = _hostInfo.asStateFlow()

    private val _connectionError = MutableStateFlow<String?>(null)
    val connectionError: StateFlow<String?> = _connectionError.asStateFlow()


    /** A backwards page is in flight; the transcript shows a spinner and suppresses re-entry. */
    private val _loadingOlder = MutableStateFlow(false)
    val loadingOlder: StateFlow<Boolean> = _loadingOlder.asStateFlow()

    /**
     * The last backwards page failed.
     *
     * Paging is driven by scroll position, and `snapshotFlow` only emits distinct values — with the
     * index unchanged after a failure nothing re-fires until the reader scrolls again. So the retry
     * has to be an affordance rather than an automatic repeat.
     */
    private val _loadOlderFailed = MutableStateFlow(false)
    val loadOlderFailed: StateFlow<Boolean> = _loadOlderFailed.asStateFlow()

    val commands: StateFlow<List<CommandDescriptor>> = catalogs.commands

    /** False once the harness has told us it has no command registry; the menu degrades, not errors. */
    val commandsAvailable: StateFlow<Boolean> = catalogs.commandsAvailable

    val agentPresets: StateFlow<AgentPresetListValue?> = catalogs.agentPresets

    /** The host's plugin inventory, or null when this deployment does not expose one. */
    val plugins: StateFlow<PluginInventorySnapshot?> = catalogs.plugins

    // ------------------------------------------------------------------ projection views
    // These are folds of `currentConversation.projections`, not separate fetches: the harness
    // already pushes every one of them on `session/projection` frames and in the history tail, so
    // deriving keeps them in lockstep with the transcript and adds no round trips. A null value
    // means the key is absent — the harness composes no such service — and callers hide the UI.

    val permissions: StateFlow<PermissionSelect?> = combine(
        projectionOf(PermissionSelect.serializer(), "permissions"), catalogs.permissionCatalog,
    ) { selection, catalog -> selection?.copy(options = catalog?.options ?: emptyList()) }
        .stateIn(scope, SharingStarted.Eagerly, null)
    val sessionStats: StateFlow<SessionStatsView?> = projectionOf(SessionStatsView.serializer(), "sessionStats")
    val tokenUsage: StateFlow<TokenUsageView?> = projectionOf(TokenUsageView.serializer(), "tokenUsage")
    val contextPressure: StateFlow<ContextPressureView?> =
        projectionOf(ContextPressureView.serializer(), "contextPressure")
    val contextBreakdown: StateFlow<ContextBreakdownView?> =
        projectionOf(ContextBreakdownView.serializer(), "contextBreakdown")
    val imageLimits: StateFlow<ImageLimitsView?> = projectionOf(ImageLimitsView.serializer(), "imageLimits")
    val planState: StateFlow<PlanStateView?> = projectionOf(PlanStateView.serializer(), "plan")

    /** This session's durable model choice; the catalog alone no longer carries one. */
    val modelSelection: StateFlow<ModelSelectionProjection?> =
        projectionOf(ModelSelectionProjection.serializer(), "modelSelection")

    /**
     * The model surface every screen renders: the session's effective selection over the host's
     * catalog.
     *
     * A join rather than a wire value, because 0.1.2 answers the two halves separately — the
     * catalog belongs to the host generation and the selection to the session. `next` wins over
     * `lastUsed` (it is the choice that has not been spent yet), and the deployment default
     * stands in before a session has either.
     */
    val models: StateFlow<SessionModelsValue?> =
        combine(catalogs.modelCatalog, modelSelection) { catalog, selection ->
            if (catalog == null) return@combine null
            val current = selection?.next ?: selection?.lastUsed ?: catalog.default
            SessionModelsValue(
                current = current,
                // `routableProviders` lists what can serve a request at all; whether *this*
                // session can start a turn is whether its own provider is in that list.
                routable = current.provider in catalog.routableProviders,
                groups = catalog.groups,
                failures = catalog.failures,
            )
        }.stateIn(scope, SharingStarted.Eagerly, null)

    /** One projection key, decoded leniently: unknown or malformed payloads read as absent. */
    private fun <T> projectionOf(serializer: KSerializer<T>, key: String): StateFlow<T?> =
        currentConversation
            .map { conversation ->
                conversation?.projections?.get(key)?.let { element ->
                    runCatching { decodeFromJsonElement(serializer, element) }.getOrNull()
                }
            }
            .stateIn(scope, SharingStarted.Eagerly, null)

    // ------------------------------------------------------------------ internal state (guarded by `lock`)
    private val indexState = SessionIndexState()

    // Open-session fold state.
    private var currentId: String? = null
    private val openSessionState = OpenSessionFoldState()

    private val remoteStreams = SessionRemoteStreamCoordinator(
        scope = scope,
        streamProvider = { endpoint, args ->
            connectionManager.generation?.mux?.openStream(endpoint, args)
        },
        onControlFrame = ::handleControlFrame,
        onWorkspaceFrame = ::handleWorkspaceFrame,
        onFollowFrame = { sessionId, frame ->
            when (frame) {
                is SessionFollowFrame.Snapshot -> applyFollowSnapshot(sessionId, frame)
                is SessionFollowFrame.Entry -> applyFollowEntry(sessionId, frame.record)
                is SessionFollowFrame.AssistantStream -> applyAssistantFrame(sessionId, frame)
            }
        },
        onFailure = { failure ->
            when {
                failure.endpoint == "session/follow" && failure.undecodable ->
                    log("undecodable session/follow frame")
                failure.endpoint == "session/follow" -> {
                    log("session/follow ended for ${failure.sessionId}", failure.error)
                    setConnectionError(failure.error?.message)
                }
                else -> log("${failure.endpoint} ended", failure.error)
            }
        },
    )

    private val subagentRuntime = SessionSubagentRuntime(
        apiForHost = ::apiForHost,
        currentSessionId = { _currentSessionId.value },
        activeHostKey = { activeHostKey },
        remoteStreams = remoteStreams,
        onConnectionError = ::setConnectionError,
        logger = ::log,
    )
    private val interactionRuntime = SessionInteractionRuntime(
        lock = lock,
        apiProvider = ::apiOrNull,
        clientIdProvider = { connectionManager.generation?.clientId },
        currentSessionId = { currentId },
        addPending = ::addPendingLocked,
        removePending = ::removePendingLocked,
        emitSessions = ::emitSessionsLocked,
        logger = { message -> log(message) },
    )
    private val slashCommandRuntime = SessionSlashCommandRuntime(
        apiForHost = ::apiForHost,
        currentSessionId = { currentSessionId.value },
        activeHostKey = { activeHostKey },
        markCommandsUnavailable = catalogs::markCommandsUnavailable,
        installPermission = interactionRuntime::installPermission,
        clearPermission = interactionRuntime::clearPermission,
        onConnectionError = ::setConnectionError,
    )

    val pendingApproval: StateFlow<PendingApproval?> get() = interactionRuntime.pendingApproval
    val pendingQuestions: StateFlow<PendingQuestions?> get() = interactionRuntime.pendingQuestions
    val pendingPermission: StateFlow<String?> get() = interactionRuntime.pendingPermission

    val subagents: StateFlow<List<SubagentListEntry>> get() = subagentRuntime.subagents
    val subagentConversation: StateFlow<ConversationSnapshot?> get() = subagentRuntime.conversation
    val subagentMode: StateFlow<String?> get() = subagentRuntime.mode

    init {
        observeConnection()
        observeEvents()
        observePermissionSettlement()
        observeRebuildTicks()
    }

    /**
     * Clear the optimistic permission value once the harness's own projection agrees with it. The
     * chip shows the target immediately and stops pretending as soon as the truth arrives.
     */
    private fun observePermissionSettlement() {
        scope.launch {
            permissions.collect { select ->
                interactionRuntime.settlePermission(select?.currentValue)
            }
        }
    }

    /**
     * Drives [rebuildCurrentLocked] for the live event stream, at most once per
     * [REBUILD_INTERVAL_MS].
     *
     * A rebuild re-folds the whole transcript, so its cost is proportional to the length of the
     * session. Running one per event made streaming quadratic: a turn arrives as a long run of
     * `assistant/chunk` deltas, and each delta was re-folding every event before it and publishing
     * a fresh snapshot for the transcript to recompose against. On a session of any size that
     * allocated hundreds of megabytes a second and eventually exhausted the heap.
     *
     * The channel is conflated because a rebuild is idempotent and reads whatever state exists when
     * it runs: a burst of deltas collapses into one rebuild, and no delta can be lost by it — the
     * event is already in the open-session fold before the tick is sent. The interval is a display frame
     * rather than a debounce, so the tail of a stream still lands promptly.
     */
    private fun observeRebuildTicks() {
        scope.launch {
            for (tick in rebuildTicks) {
                synchronized(lock) { rebuildCurrentLocked() }
                delay(REBUILD_INTERVAL_MS)
            }
        }
    }

    // ------------------------------------------------------------------ connection lifecycle
    private fun observeConnection() {
        scope.launch {
            var prev = connectionManager.state.value
            connectionManager.state.collect { state ->
                val initialConnect = !prev.hasConnected && state.hasConnected
                val reconnect = prev.hasConnected &&
                    prev.phase == ConnectionPhase.RECONNECTING &&
                    state.phase == ConnectionPhase.CONNECTED
                val retiredGeneration =
                    prev.phase == ConnectionPhase.CONNECTED &&
                        state.phase != ConnectionPhase.CONNECTED
                if (retiredGeneration) interactionRuntime.clearRetiredGeneration()
                prev = state
                if (initialConnect || reconnect) triggerBaseline()
            }
        }
    }

    private fun observeEvents() {
        scope.launch {
            connectionManager.eventFrames.collect { handleEventFrame(it) }
        }
    }

    /** Decode one stream item, or null when it does not match the expected frame union. */
    private fun <T> decodeOrNull(serializer: kotlinx.serialization.KSerializer<T>, item: JsonElement): T? =
        runCatching { decodeFromJsonElement(serializer, item) }.getOrNull()

    private fun triggerBaseline() {
        scope.launch {
            baselineRefreshGate.request {
                try {
                    baseline()
                } catch (e: Exception) {
                    log("baseline failed", e)
                }
            }
        }
    }

    private suspend fun baseline() {
        // Whether content search works is a fact about the harness we just reached, so a fresh
        // connection re-earns the answer rather than inheriting the previous host's.
        searchRuntime.resetCapability()
        // Before the list read: the workspace and control streams each open with their own
        // complete baseline, and the list is what their increments are applied on top of.
        remoteStreams.restartHostStreams()
        _hostInfo.value = connectionManager.generation?.description
        coroutineScope {
            // Host-scoped and needed before anything is tapped, but needed by nothing on the way to
            // the transcript: the chat bar names the session's preset as soon as it renders, and
            // the permission chip wants its catalog, and both can land while the session opens.
            // Awaiting them here put two full phone→relay→host round trips in front of the first
            // thing the reader actually looks at. Neither throws — each reports its own failure —
            // so nothing downstream has to know whether they have landed yet.
            launch { refreshAgentPresets() }
            launch { refreshPermissionCatalog() }
            // The list is the one read the landing session is chosen from, so it alone is awaited.
            refreshSessions()
            // On a reconnect `currentSessionId` is already set, so the resolver only ever runs on
            // the first connect of a process — no double-open, and reconnect keeps reopening what
            // was open.
            val sid = currentSessionId.value ?: resolveInitialSession() ?: return@coroutineScope
            openSession(sid)
        }
    }

    /**
     * Which session to land on when the app has just connected and nothing is open.
     *
     * Mirrors the harness's own startup policy: the session you were last in, else the most
     * recently active workspace's newest session, else simply the newest session. Ranking is by
     * session `updatedAt` — `workspace.updatedAt` stamps the registration record (a rename, a
     * session being added), and `workspace.list` order is the manual display order, so neither
     * tracks conversation activity.
     *
     * Returns null when there is nothing worth opening, which leaves the empty hero on screen.
     */
    private suspend fun resolveInitialSession(): String? {
        val remembered = hostKey()?.let { hostsStore.lastSessionId(it) }
        val (rows, workspaces, archivedNow) = synchronized(lock) {
            indexState.initialSnapshot()
        }
        return pickInitialSession(rows, workspaces, archivedNow, remembered)
    }

    /** `"host:port"` for the connected harness — session ids are only meaningful within one host. */
    private fun hostKey(): String? =
        connectionManager.state.value.host?.let { "${it.host}:${it.port}" }

    // ------------------------------------------------------------------ host event frames
    /**
     * One frame of the host's `$events` stream.
     *
     * This is the whole of what arrives unbidden in 0.1.2. Session events are not here — they
     * belong to a per-session `session/follow` stream — and neither is queue, job or projection
     * state, which belongs to `session/control`. What is left is notifications and the two
     * agent-scoped waterfalls.
     */
    private val eventReduce: SessionEventReduceRuntime by lazy {
        SessionEventReduceRuntime(
            scope = scope,
            lock = lock,
            currentIdOf = { currentId },
            indexState = indexState,
            openSessionState = openSessionState,
            queuesBySession = queuesBySession,
            rebuildTicks = rebuildTicks,
            interactionRuntime = interactionRuntime,
            sessions = _sessions,
            workspaces = _workspaces,
            archivedSessionIds = _archivedSessionIds,
            currentConversation = _currentConversation,
            jobsState = _jobs,
            notificationSinkOf = { notificationSink },
            setConnectionErrorOf = ::setConnectionError,
            logOf = ::log,
            refreshPermissionCatalog = { refreshPermissionCatalog() },
            refreshCommands = { refreshCommands() },
            refreshAgentPresets = { refreshAgentPresets() },
        )
    }

    private fun handleEventFrame(frame: RemoteEventFrame) = eventReduce.handleEventFrame(frame)

    private fun handleControlFrame(frame: SessionControlFrame) = eventReduce.handleControlFrame(frame)

    private fun handleWorkspaceFrame(frame: WorkspaceFollowFrame) = eventReduce.handleWorkspaceFrame(frame)

    private fun handleSessionEvent(sessionId: String, envelope: SessionEventEnvelope) =
        eventReduce.handleSessionEvent(sessionId, envelope)

    private fun applyQueue(sessionId: String, items: List<QueuedInboxItem>) =
        eventReduce.applyQueue(sessionId, items)

    private fun applyJobs(sessionId: String, jobs: List<JobView>) =
        eventReduce.applyJobs(sessionId, jobs)

    private fun applyProjectionBaseline(sessionId: String, block: JsonObject) =
        eventReduce.applyProjectionBaseline(sessionId, block)

    private fun onSessionAdded(summary: JsonElement) = eventReduce.onSessionAdded(summary)

    private fun onSessionAdded(item: SessionSummary) = eventReduce.onSessionAdded(item)

    private fun onSessionRemoved(sessionId: String) = eventReduce.onSessionRemoved(sessionId)

    private fun setRunning(sessionId: String, running: Boolean) = eventReduce.setRunning(sessionId, running)

    private fun setUpdatedAt(sessionId: String, updatedAt: Long) = eventReduce.setUpdatedAt(sessionId, updatedAt)

    private fun setBlank(sessionId: String, blank: Boolean) = eventReduce.setBlank(sessionId, blank)

    private fun setTitle(sessionId: String, title: String) = eventReduce.setTitle(sessionId, title)

    private fun upsertWorkspace(workspace: WorkspaceView) = eventReduce.upsertWorkspace(workspace)

    private fun removeWorkspace(workspaceId: String) = eventReduce.removeWorkspace(workspaceId)

    private fun setWorkspaceOrder(ids: List<String>) = eventReduce.setWorkspaceOrder(ids)

    private fun setArchived(ids: List<String>) = eventReduce.setArchived(ids)

    private fun rebuildCurrentLocked() = eventReduce.rebuildCurrentLocked()

    private fun emitSessionsLocked() = eventReduce.emitSessionsLocked()

    private fun emitWorkspacesLocked() = eventReduce.emitWorkspacesLocked()

    @Volatile
    var notificationSink: ((String, SessionEventEnvelope) -> Unit)? = null
    private fun setConnectionError(message: String?) {
        _connectionError.value = message
    }

    /**
     * Drop a stale failure banner once something works again.
     *
     * Errors used to be set and never cleared, so one transient failure — a session that was still
     * cold when the app opened it, say — left a red banner across the whole session for the rest of
     * the run, long after the thing it described had resolved.
     */
    private fun clearConnectionError() {
        if (_connectionError.value != null) _connectionError.value = null
    }


    private fun addPendingLocked(sessionId: String, kind: String) {
        indexState.addPending(sessionId, kind)
    }

    private fun removePendingLocked(sessionId: String, kind: String) {
        indexState.removePending(sessionId, kind)
    }

    // ------------------------------------------------------------------ public RPC surface
    suspend fun refreshSessions() {
        val api = apiOrNull() ?: return
        when (val r = api.sessionList(null)) {
            is RpcResult.Ok -> {
                clearConnectionError()
                synchronized(lock) {
                    indexState.replaceSessions(r.value.items)
                    emitSessionsLocked()
                }
            }
            is RpcResult.Err -> setConnectionError(r.error.message)
        }
    }

    suspend fun openSession(sessionId: String) = withContext(Dispatchers.Default) {
        val api = apiOrNull() ?: return@withContext
        _loadOlderFailed.value = false
        synchronized(lock) {
            val same = currentId == sessionId
            currentId = sessionId
            _currentSessionId.value = sessionId
            interactionRuntime.syncVisible()
            openSessionState.reset(indexState.session(sessionId)?.blank ?: true)
            if (!same) {
                _currentConversation.value = null
                _jobs.value = emptyList()
                catalogs.resetSession()
                subagentRuntime.resetSession()
            }
        }
        startFollow(sessionId)
        // Everything past the follow stream furnishes the chrome around the transcript — the skill
        // and model pickers, the subagent list, the command catalog — and none of it is needed to
        // paint a single message. Run in series they stacked four round trips onto every session
        // tap, which is what made switching sessions feel like loading them. The follow stream is
        // already open by this point, so the transcript arrives while these are still in flight.
        //
        // Each background result is scoped to the host/session captured when it started. Old
        // responses are ignored after a rapid switch instead of repainting the newly opened session.
        coroutineScope {
            launch { loadSkills(sessionId) }
            launch { loadModels(sessionId) }
            launch { refreshSubagents() }
            launch { refreshCommands() }
            launch { landingRuntime.remember(sessionId) }
        }
    }

    /**
     * Open the live journal for one session, replacing whatever was open.
     *
     * There is no separate history read any more. `session/follow` opens with a complete snapshot
     * carrying the first page, its projections, and the log cut the generation opened at; every
     * later item is one live event. A reconnect re-opens the stream and sends another complete
     * snapshot, so the window is replaced wholesale rather than patched — which is why the
     * snapshot handler clears the buffer instead of merging into it.
     *
     * The stream is opened with `assistantStream`, because since harness 0.1.3 that is the only
     * way to see a reply while it is written: the durable log holds one settlement per model
     * attempt and no deltas. The frames it adds are process-local presentation — never replayed,
     * never paged — and are folded after the durable window as a provisional message.
     *
     * Following does not resume a stopped agent: the host publishes a cold session's prepared
     * snapshot immediately and promotes it in the background, so opening a transcript is an
     * observation rather than an execution.
     */
    private fun startFollow(sessionId: String) {
        openSessionState.clearFollowCursor()
        if (!remoteStreams.followSession(sessionId, HISTORY_PAGE_SIZE)) {
            log("cannot follow $sessionId: no connection generation")
        }
    }

    /** Install one complete opening window, replacing any previous one for this session. */
    private fun applyFollowSnapshot(sessionId: String, frame: SessionFollowFrame.Snapshot) {
        clearConnectionError()
        val envelopes = expandRecords(frame.records)
        val page = historyTail(envelopes)
        val overDelivered = envelopes.size > page.size
        synchronized(lock) {
            if (currentId != sessionId) return@synchronized
            openSessionState.installSnapshot(frame, page, overDelivered)
            rebuildCurrentLocked()
        }
    }

    /** One live event. */
    private fun applyFollowEntry(sessionId: String, record: SessionHistoryRecord) {
        for (envelope in expandRecords(listOf(record))) {
            handleSessionEvent(sessionId, envelope)
        }
    }

    /** One process-local assistant frame: the reply being written, a chunk at a time. */
    private fun applyAssistantFrame(sessionId: String, frame: SessionFollowFrame.AssistantStream) {
        val changed = synchronized(lock) {
            if (currentId != sessionId) return
            openSessionState.acceptAssistant(frame)
        }
        if (changed) rebuildTicks.trySend(Unit)
    }

    /**
     * History records are plain events since harness 0.1.3, but a 0.1.2 host still packs runs of
     * consecutive assistant deltas into one record. Expanding those back into scalar events is
     * what keeps the journal's sequence numbers contiguous; see
     * [com.labteto.dshmobile.core.session.ChunkRows].
     */
    private fun expandRecords(records: List<SessionHistoryRecord>): List<SessionEventEnvelope> =
        ChunkRows.expandAll(records).map { wireEventToEnvelope(it) }

    /**
     * Page one screen further back.
     *
     * Called from the transcript's scroll position, so it has to be safe to call repeatedly: the
     * in-flight flag collapses a burst of scroll emissions into one request, and a page that adds
     * nothing new ends the paging rather than leaving `hasMore` set for the trigger to fire on
     * again.
     */
    suspend fun loadOlder() = withContext(Dispatchers.Default) {
        val sid = currentSessionId.value ?: return@withContext
        val api = apiOrNull() ?: return@withContext
        if (!_loadingOlder.compareAndSet(expect = false, update = true)) return@withContext
        try {
            val (oldestSeq, cursor) = synchronized(lock) {
                openSessionState.pageAnchor()
            }
            // A page is pinned to the follow generation's log cut, and there is no page without
            // one. Before the opening snapshot lands there is nothing to pin to, so this waits
            // for the next scroll rather than guessing a cut the host would reject.
            if (cursor == null) {
                log("cannot page $sid: no follow cursor yet")
                return@withContext
            }
            val request = SessionPageRequest(
                address = SessionAddress.Session(sessionId = sid),
                throughSeq = cursor,
                beforeSeq = oldestSeq?.toInt(),
                maxMessages = HISTORY_PAGE_SIZE,
            )
            when (val r = api.sessionPage(request)) {
                is RpcResult.Ok -> {
                    clearConnectionError()
                    _loadOlderFailed.value = false
                    // Same guard as the opening window, so paging backwards stays bounded instead
                    // of pulling the whole log at once.
                    val envelopes = expandRecords(r.value.records)
                    val page = historyTail(envelopes)
                    val overDelivered = envelopes.size > page.size
                    synchronized(lock) {
                        if (currentId != sid) return@synchronized
                        openSessionState.prependPage(page, r.value.hasMore, overDelivered)
                        rebuildCurrentLocked()
                    }
                }
                // Not a connection fault: the session is healthy and the tail still streams, so this
                // offers a retry in the transcript rather than raising a connection banner over it.
                is RpcResult.Err -> _loadOlderFailed.value = true
            }
        } finally {
            _loadingOlder.value = false
        }
    }

    suspend fun createSession(cwd: String? = null, workspaceId: String? = null) =
        sessionLifecycleRuntime.create(cwd, workspaceId)

    suspend fun renameSession(sessionId: String, title: String) =
        sessionLifecycleRuntime.rename(sessionId, title)

    suspend fun forkSession(sessionId: String, atSeq: Long? = null) =
        sessionLifecycleRuntime.fork(sessionId, atSeq)

    suspend fun archiveSession(sessionId: String) = workspaceRuntime.archiveSession(sessionId)

    suspend fun prompt(
        text: String,
        mode: String,
        targetSessionId: String? = currentSessionId.value,
        targetHost: String? = activeHostKey,
    ) = turnCommandRuntime.prompt(text, mode, targetSessionId, targetHost)

    suspend fun promptWithAttachments(
        text: String,
        mode: String,
        images: List<EncodedImageAttachment>,
        fileReceipts: List<String> = emptyList(),
        targetSessionId: String? = currentSessionId.value,
        targetHost: String? = activeHostKey,
    ): PromptOutcome = turnCommandRuntime.promptWithAttachments(
        text = text,
        mode = mode,
        images = images,
        fileReceipts = fileReceipts,
        targetSessionId = targetSessionId,
        targetHost = targetHost,
    )

    /**
     * Stage one file for the open session and answer with its receipt.
     *
     * The bytes are streamed to the raw-byte route the way the web client does it, so a large
     * file never sits in memory as base64. A deployment or relay that does not serve that route
     * answers 404, which is a missing capability rather than a broken link; a file that fits
     * comfortably in an RPC body is then retried through the `fileUploads/upload` Remote, which
     * every 0.1.3 host composes beside the route. [open] is called once per attempt and must
     * answer a fresh stream positioned at the first byte.
     *
     * A refusal is the composer's problem, not the connection's, so nothing here raises the
     * connection banner: the chip that owns the file shows the failure and offers a retry.
     */
    suspend fun uploadFile(
        name: String,
        size: Long,
        open: () -> InputStream?,
        onProgress: (sent: Long) -> Unit = {},
        targetSessionId: String? = currentSessionId.value,
        targetHost: String? = activeHostKey,
    ): RpcResult<FileUploadValue> =
        attachmentTransfer.uploadFile(
            name = name,
            size = size,
            open = open,
            onProgress = onProgress,
            targetSessionId = targetSessionId,
            targetHost = targetHost,
        )

    suspend fun cancelTurn() = turnCommandRuntime.cancelTurn()

    suspend fun updateQueue(
        itemId: String,
        action: String,
        contentText: String? = null,
        sessionId: String? = currentSessionId.value,
    ): Boolean = turnCommandRuntime.updateQueue(itemId, action, contentText, sessionId)

    suspend fun respondApproval(
        sessionId: String,
        approvalId: String,
        allow: Boolean,
    ): QuestionOutcome = interactionRuntime.respondApproval(sessionId, approvalId, allow)

    suspend fun answerQuestions(
        sessionId: String,
        answer: AskUserQuestionAnswer,
    ): QuestionOutcome = interactionRuntime.answerQuestions(sessionId, answer)

    suspend fun dismissQuestions(sessionId: String): QuestionOutcome =
        interactionRuntime.dismissQuestions(sessionId)

    suspend fun selectModel(provider: String, model: String, reasoningEffort: String? = null) {
        val sid = currentSessionId.value ?: return
        val api = apiOrNull() ?: return
        val request = SessionSelectModelRequest(sid, provider, model, reasoningEffort)
        when (val r = api.sessionSelectModel(request)) {
            is RpcResult.Ok -> loadModels(sid)
            is RpcResult.Err -> setConnectionError(r.error.message)
        }
    }

    /**
     * Full-text search across message content.
     *
     * This is the *optional* half of search, and most deployments do not have it: the shipped
     * `session-query-sqlite` row is configured `openAt: never`, which keeps exact reads, titles and
     * lineage traces working while `session.search` fails outright. So a failure here is a normal
     * condition, not a fault — it is latched into [contentSearchAvailable], never raised as a
     * connection error, and never retried for the life of the connection. The drawer's own title
     * and workspace filtering is unaffected and remains the primary way to find a session, exactly
     * as it is in the harness's web sidebar under the same configuration.
     */
    suspend fun search(query: String) = searchRuntime.search(query)

    suspend fun fetchAttachment(attachmentId: String, sessionId: String? = currentSessionId.value, host: String? = activeHostKey): ByteArray? =
        attachmentTransfer.fetchAttachment(
            attachmentId = attachmentId,
            sessionId = sessionId,
            host = host,
        )

    suspend fun listSkills() {
        val sid = currentSessionId.value ?: return
        loadSkills(sid)
    }

    suspend fun refreshSubagents() = subagentRuntime.refresh()

    suspend fun interruptSubagent(childSessionId: String) =
        subagentRuntime.interrupt(childSessionId)

    suspend fun promptSubagent(
        childSessionId: String,
        text: String,
        delivery: String = "queue",
    ): Boolean = subagentRuntime.prompt(childSessionId, text, delivery)

    suspend fun openSubagentTranscript(childSessionId: String) =
        subagentRuntime.openTranscript(childSessionId)

    fun closeSubagentTranscript() = subagentRuntime.closeTranscript()

    suspend fun createWorkspace(path: String) = workspaceRuntime.create(path)

    suspend fun renameWorkspace(id: String, title: String) = workspaceRuntime.rename(id, title)

    suspend fun deleteWorkspace(id: String) = workspaceRuntime.delete(id)

    suspend fun goalAction(action: String, objective: String? = null) =
        goalRuntime.act(action, objective)

    /**
     * Reload the session's slash-command catalog.
     *
     * A harness with no command registry answers 404 and a LAN-refused method answers 403; neither
     * is a connection fault, so this degrades the menu to its static fallback rather than raising a
     * failure banner on an otherwise healthy session.
     */
    suspend fun refreshCommands() =
        catalogs.refreshCommands(currentSessionId.value)

    /**
     * Run one complete slash-command line, optionally carrying the composer's images.
     *
     * The typert remote is the *only* command write path: `session.prompt` does not inspect its
     * content, so a leading-slash prompt reaches the model as ordinary user text (this store used
     * to send commands that way, which is why picking a permission preset made the agent shell out
     * to figure out what `/permission` meant). See `docs/PROTOCOL.md`.
     *
     * The remote answers `undefined` when the line parses to no registered command, and the wire
     * codec folds an absent `value` slot into an empty object — so the discriminator is the
     * presence of `commandId`, not the emptiness of the value.
     *
     * [attachments] must be empty unless the command's descriptor declares it takes them — see
     * `CommandDescriptor.acceptsAttachments`. A host that admits them but whose handler will not
     * use them (`/plan off`, `/goal pause`) answers with an ordinary error result, which is the
     * harness's own division of labour and not worth mirroring here.
     */
    suspend fun runCommand(
        line: String,
        attachments: List<com.labteto.dshmobile.core.wire.dto.CommandSubmitAttachment> = emptyList(),
        targetSessionId: String? = currentSessionId.value,
        targetHost: String? = activeHostKey,
    ): CommandOutcome = slashCommandRuntime.run(line, attachments, targetSessionId, targetHost)

    suspend fun setPermissionPreset(value: String): CommandOutcome =
        slashCommandRuntime.setPermissionPreset(value)

    /**
     * Reload the host's plugin inventory.
     *
     * Host-scoped and read-only — the harness offers no way to change it from here. A deployment
     * that does not compose `@deepseek-ai/dsh-host-plugin-inventory` answers 404, which leaves the
     * flow null and takes the settings section off the screen: absence of the capability, not a
     * failure to report.
     */
    suspend fun refreshPlugins() = catalogs.refreshPlugins()

    /** Reload the agent-preset roster (host-scoped, so it survives session switches). */
    suspend fun refreshAgentPresets() = catalogs.refreshAgentPresets()

    /**
     * Pin an agent preset onto the open session. The harness only allows this while the session is
     * blank; on a started session it answers `agent-preset-locked`, which surfaces as a normal error.
     */
    suspend fun selectAgentPreset(agentPreset: String): Boolean {
        val sid = currentSessionId.value ?: return false
        val api = apiOrNull() ?: return false
        return when (val r = api.agentPresetSelect(sid, agentPreset)) {
            is RpcResult.Ok -> {
                refreshSessions()
                true
            }
            is RpcResult.Err -> {
                setConnectionError(r.error.message)
                false
            }
        }
    }

    /**
     * Stream the open session's log ZIP into [sink]. The caller owns [sink] and should close it;
     * the harness answers this as a plain attachment download, not an RPC.
     */
    suspend fun exportSessionTo(sink: OutputStream, includeDescendants: Boolean = false): Boolean {
        val sid = currentSessionId.value ?: return false
        val api = apiOrNull() ?: return false
        val result = api.sessionExport(sid, includeDescendants) { _, _, body -> body.copyTo(sink) }
        return when (result) {
            is RpcResult.Ok -> true
            is RpcResult.Err -> {
                setConnectionError(result.error.message)
                false
            }
        }
    }

    suspend fun exportSessionUrl(): String? {
        val sid = currentSessionId.value ?: return null
        val host = connectionManager.state.value.host ?: return null
        return "${host.baseUrl}/api/session.export?sessionId=$sid"
    }

    /** True while [sessionId] is the session currently open in the foreground. */
    fun isSessionOpen(sessionId: String): Boolean = currentSessionId.value == sessionId

    // ------------------------------------------------------------------ internal helpers
    private fun goalRefFromProjectionLocked(): GoalRef? {
        val value = openSessionState.projection("goal") ?: return null
        return runCatching {
            val snapshot = decodeFromJsonElement(GoalSnapshot.serializer(), value)
            GoalRef(snapshot.id, snapshot.revision)
        }.getOrElse {
            runCatching { decodeFromJsonElement(GoalRef.serializer(), value) }.getOrNull()
        }
    }

    private suspend fun loadSkills(sessionId: String) = catalogs.loadSkills(sessionId)

    private suspend fun loadModels(sessionId: String) = catalogs.loadModels(sessionId)

    /**
     * The tail slice of a history page the host over-delivered.
     *
     * `maxMessages` is a bound on *messages*, and not every harness build honours it — one was
     * observed answering a 60-message request with ~29k events (several MB), which folds slowly
     * enough to stall the first paint. Trimming is not as simple as keeping the last N events
     * though: a single assistant message can be hundreds of `assistant/chunk` deltas, so a fixed
     * event count yields a page with almost nothing readable in it. This walks back until it has
     * [HISTORY_PAGE_SIZE] actual messages, with a hard event ceiling so a pathological log still
     * cannot stall the fold. Anything trimmed is reported as `hasMore`, which is what
     * "Load older" is for.
     */
    private fun historyTail(entries: List<SessionEventEnvelope>): List<SessionEventEnvelope> {
        if (entries.size <= MAX_PAGE_EVENTS) return entries
        var messages = 0
        var index = entries.lastIndex
        while (index > 0 && entries.size - index < MAX_PAGE_EVENTS) {
            if (entries[index].type in SURFACE_EVENT_TYPES) {
                messages++
                if (messages >= HISTORY_PAGE_SIZE) break
            }
            index--
        }
        return entries.subList(index.coerceAtLeast(0), entries.size)
    }

    /**
     * Whether this connection's harness carries attachments on a slash command.
     *
     * Always true from harness 0.1.2: `commands/execute` declares the parameter unconditionally,
     * and the shape-derived capability check this used to perform depended on `host.describe`,
     * which no longer exists. Kept as a property so the composer's adjudication has one place to
     * consult if a future release makes it conditional again.
     */
    val commandAttachmentsSupported: Boolean get() = connectionManager.connectedApi != null

    suspend fun refreshPermissionCatalog() = catalogs.refreshPermissionCatalog()

    suspend fun unarchiveSession(sessionId: String): Boolean =
        workspaceRuntime.unarchiveSession(sessionId)

    private fun apiOrNull(): DshApiClient? {
        val api = connectionManager.connectedApi
        if (api == null) AppLog.debug(TAG, "not connected — ignoring request")
        return api
    }

    private fun log(message: String, throwable: Throwable? = null) {
        AppLog.warn(TAG, message, throwable)
    }

    private companion object {
        const val TAG = "SessionStore"

        const val HISTORY_PAGE_SIZE = 60

        /** Ceiling on events folded per page, whatever the host sends. */
        const val MAX_PAGE_EVENTS = 4_000

        /** The event types that produce a visible message; everything else frames them. */
        val SURFACE_EVENT_TYPES = setOf("user/message", "assistant/message", "tool/result")

        /**
         * Floor on the gap between transcript rebuilds while a turn streams.
         *
         * One display frame. Nothing is gained by republishing a transcript faster than it can be
         * drawn, and the deltas of a single turn arrive far faster than that.
         */
        const val REBUILD_INTERVAL_MS = 50L
    }
}

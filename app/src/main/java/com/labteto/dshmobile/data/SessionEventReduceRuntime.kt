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
 * Remote event-stream ingestion and session/workspace list-state reducers.
 *
 * Owns the "frame -> state" fold previously inlined in SessionStore: host notifications,
 * waterfall approvals, control-stream queue/job/projection merges, workspace registry frames
 * and per-session events, plus the locked emit/rebuild helpers they share.
 * SessionStore keeps the connection wiring, RPC surface and public flows; this runtime
 * owns the mutation path that turns frames into list and fold state.
 */
internal class SessionEventReduceRuntime(
    private val scope: CoroutineScope,
    private val lock: Any,
    private val currentIdOf: () -> String?,
    private val indexState: SessionIndexState,
    private val openSessionState: OpenSessionFoldState,
    private val queuesBySession: MutableStateFlow<Map<String, List<QueueItem>>>,
    private val rebuildTicks: Channel<Unit>,
    private val interactionRuntime: SessionInteractionRuntime,
    private val sessions: MutableStateFlow<List<SessionRow>>,
    private val workspaces: MutableStateFlow<List<WorkspaceRow>>,
    private val archivedSessionIds: MutableStateFlow<Set<String>>,
    private val currentConversation: MutableStateFlow<ConversationSnapshot?>,
    private val jobsState: MutableStateFlow<List<JobView>>,
    private val notificationSinkOf: () -> ((String, SessionEventEnvelope) -> Unit)?,
    private val setConnectionErrorOf: (String?) -> Unit,
    private val logOf: (String, Throwable?) -> Unit,
    private val refreshPermissionCatalog: suspend () -> Unit,
    private val refreshCommands: suspend () -> Unit,
    private val refreshAgentPresets: suspend () -> Unit,
) {
    private fun log(message: String, throwable: Throwable? = null) = logOf(message, throwable)

    private fun setConnectionError(message: String?) = setConnectionErrorOf(message)

    private val notificationSink: ((String, SessionEventEnvelope) -> Unit)?
        get() = notificationSinkOf()

    fun handleEventFrame(frame: RemoteEventFrame) {
        when (frame) {
            is RemoteEventFrame.Emit -> handleNotification(frame.event, frame.args)
            is RemoteEventFrame.Waterfall -> handleWaterfall(frame)
            is RemoteEventFrame.Cancel -> handleWaterfallCancelled(frame.eventId)
            // Consumed by the connection loop's handshake; it never forwards one.
            is RemoteEventFrame.Ready -> Unit
            is RemoteEventFrame.Unknown -> log("unknown host event frame ${frame.type}")
        }
    }

    /**
     * One ordinary host notification.
     *
     * Arguments are positional — the host forwards the Cordis listener's own argument list — so
     * these read by index rather than by key. None of them is replayed after a reconnect, which
     * is why every one of them is either repairable from the session list baseline or purely
     * advisory.
     */
    fun handleNotification(event: String, args: List<JsonElement>) {
        fun str(i: Int) = args.getOrNull(i)?.jsonPrimitive?.contentOrNull
        when (event) {
            "api-session/added" -> args.firstOrNull()?.let { onSessionAdded(it) }
            "api-session/removed" -> str(0)?.let { onSessionRemoved(it) }
            "api-session/status" -> {
                val sid = str(0) ?: return
                val running = args.getOrNull(1)?.jsonPrimitive?.booleanOrNull ?: false
                setRunning(sid, running)
            }
            "permission-presets/catalog-changed" -> scope.launch { refreshPermissionCatalog() }
            "api-session/activity" -> {
                // Only reorders the list; the durable value is the session's own projection, so a
                // missed one is corrected by the next list read rather than lost.
                val sid = str(0) ?: return
                val updatedAt = args.getOrNull(1)?.jsonPrimitive?.longOrNull ?: return
                setUpdatedAt(sid, updatedAt)
            }
            "api-session/error" -> setConnectionError(str(1))
            "commands/change" -> scope.launch { refreshCommands() }
            "agent-preset/selected" -> scope.launch {
                refreshAgentPresets()
                refreshCommands()
            }
            else -> Unit
        }
    }

    /** One pending agent-scoped request awaiting this client's answer. */
    fun handleWaterfall(frame: RemoteEventFrame.Waterfall) {
        when (frame.event) {
            APPROVAL_REQUEST_EVENT -> {
                val request = runCatching {
                    decodeFromJsonElement(ApprovalRequestEvent.serializer(), frame.request)
                }.getOrNull() ?: return
                interactionRuntime.installApproval(frame.eventId, frame.agentId, request)
            }
            USER_QUESTIONS_REQUEST_EVENT -> {
                val request = runCatching {
                    decodeFromJsonElement(AskUserQuestionRequestEvent.serializer(), frame.request)
                }.getOrNull() ?: return
                interactionRuntime.installQuestions(frame.eventId, frame.agentId, request.questions)
            }
            else -> log("unhandled waterfall ${frame.event}")
        }
    }

    /**
     * A pending request was withdrawn: another client answered it, or the host's caller cancelled.
     *
     * Replaces the `approval/resolved` and `question/resolved` frames, and covers both — an
     * `eventId` identifies the request without saying which kind it was, so both registries are
     * checked.
     *
     * It is *not* the only way a request leaves: the host drops the answering client's delivery
     * before it cancels the rest, so this frame reaches every client except the one that acted.
     * That client settles its own card in [answerOutcome].
     */
    fun handleWaterfallCancelled(eventId: String) = interactionRuntime.forgetEvent(eventId)

    // ------------------------------------------------------------------ control stream
    /**
     * One frame of the host-wide live-control stream.
     *
     * Queue and job values are complete replacements applied last-wins, never deltas, so an
     * empty value is a real "nothing pending" rather than an absent update.
     */
    fun handleControlFrame(frame: SessionControlFrame) {
        when (frame) {
            is SessionControlFrame.Baseline -> {
                queuesBySession.value = frame.value.queues.mapValues { (_, items) -> items.map(::queuedInboxItemToQueueItem) }
                val sid = synchronized(lock) { currentIdOf() } ?: return
                frame.value.queues[sid]?.let { items -> applyQueue(sid, items) }
                frame.value.jobs[sid]?.let { jobs -> applyJobs(sid, jobs) }
                frame.value.projections[sid]?.let { block -> applyProjectionBaseline(sid, block) }
            }
            is SessionControlFrame.Queue -> applyQueue(frame.sessionId, frame.items)
            is SessionControlFrame.Jobs -> applyJobs(frame.sessionId, frame.jobs)
            is SessionControlFrame.Projection -> synchronized(lock) {
                if (frame.sessionId == currentIdOf()) {
                    openSessionState.mergeProjection(frame.key, frame.seq, frame.value)
                    rebuildCurrentLocked()
                }
            }
            is SessionControlFrame.Unknown -> log("unknown control frame ${frame.type}")
        }
    }

    fun applyQueue(sessionId: String, items: List<QueuedInboxItem>) {
        queuesBySession.value = queuesBySession.value + (sessionId to items.map(::queuedInboxItemToQueueItem))
        synchronized(lock) {
            if (sessionId == currentIdOf()) {
                openSessionState.setQueue(items.map { queuedInboxItemToQueueItem(it) })
                rebuildCurrentLocked()
            }
        }
    }

    fun applyJobs(sessionId: String, jobs: List<JobView>) {
        synchronized(lock) {
            if (sessionId == currentIdOf()) jobsState.value = jobs
        }
    }

    /**
     * Merge a projection baseline for one session.
     *
     * The tail page's baseline and the control stream's are produced independently, so neither is
     * authoritative on its own; [OpenSessionFoldState.mergeProjection] keeps whichever carries the higher
     * watermark.
     */
    fun applyProjectionBaseline(sessionId: String, block: JsonObject) {
        synchronized(lock) {
            if (sessionId != currentIdOf()) return@synchronized
            val asOf = block["asOfSeq"]?.jsonPrimitive?.intOrNull ?: 0
            (block["values"] as? JsonObject)?.forEach { (key, value) ->
                openSessionState.mergeProjection(key, asOf, value)
            }
            rebuildCurrentLocked()
        }
    }

    // ------------------------------------------------------------------ workspace stream
    /**
     * One frame of the workspace registry stream.
     *
     * The `order` frame is complete and authoritative; display order is never inferred from the
     * arrival order of upserts, which is what makes the list converge after a reconnect baseline.
     */
    fun handleWorkspaceFrame(frame: WorkspaceFollowFrame) {
        when (frame) {
            is WorkspaceFollowFrame.Baseline -> synchronized(lock) {
                indexState.replaceWorkspaceBaseline(
                    frame.workspaces,
                    frame.workspaceIds,
                    frame.archivedSessionIds,
                )
                archivedSessionIds.value = indexState.archivedIds()
                emitWorkspacesLocked()
            }
            is WorkspaceFollowFrame.Upsert -> upsertWorkspace(frame.workspace)
            is WorkspaceFollowFrame.Remove -> removeWorkspace(frame.workspaceId)
            is WorkspaceFollowFrame.Order -> setWorkspaceOrder(frame.workspaceIds)
            is WorkspaceFollowFrame.Archived -> setArchived(frame.archivedSessionIds)
            is WorkspaceFollowFrame.Unknown -> log("unknown workspace frame ${frame.type}")
        }
    }

    /**
     * One event from the open session's follow stream.
     *
     * Through 0.1.1 this arrived for every session at once on the mux, which is how the store
     * kept list state for sessions nobody had opened. 0.1.2 has no such stream: an event is only
     * seen for the session actually being followed, and everything else about the list comes from
     * a notification or a list read.
     */
    fun handleSessionEvent(sessionId: String, envelope: SessionEventEnvelope) {
        when (envelope.type) {
            "turn/start" -> {
                setRunning(sessionId, true)
                setBlank(sessionId, false)
            }
            "turn/end" -> setRunning(sessionId, false)
            "user/message" -> setBlank(sessionId, false)
            "session/title" -> {
                val title = envelope.data.jsonObject["title"]?.jsonPrimitive?.contentOrNull
                if (title != null) setTitle(sessionId, title)
            }
        }
        // Completion notifications used to be classified from the all-session mux. That stream is
        // gone, so the session that owns the event forwards it to whoever is watching for one.
        notificationSink?.invoke(sessionId, envelope)
        synchronized(lock) {
            if (sessionId == currentIdOf()) {
                // The durable settlement and the transient rows say the same thing; the moment
                // the settlement lands the preview is redundant, and a fold that saw both would
                // show the reply twice.
                openSessionState.acceptDurable(envelope)
                rebuildTicks.trySend(Unit)
            }
        }
    }

    /**
     * Where session events go for completion notifications.
     *
     * A hook rather than a direct dependency: the notification observer already depends on this
     * store, and 0.1.2 leaves no all-session stream for it to read instead.
     */

    // ------------------------------------------------------------------ session list state updates
    /**
     * One session became visible to list consumers.
     *
     * The notification carries the whole list row rather than the loose fields the old
     * `host/session-added` frame did, so this decodes a summary and folds it in.
     */
    fun onSessionAdded(summary: JsonElement) {
        val item = runCatching {
            decodeFromJsonElement(SessionSummary.serializer(), summary)
        }.getOrNull() ?: return
        onSessionAdded(item)
    }

    fun onSessionAdded(item: SessionSummary) {
        synchronized(lock) {
            indexState.addSession(item)
            emitSessionsLocked()
        }
    }

    fun onSessionRemoved(sessionId: String) {
        synchronized(lock) {
            indexState.removeSession(sessionId)
            interactionRuntime.discardSession(sessionId)
            emitSessionsLocked()
        }
    }

    fun setRunning(sessionId: String, running: Boolean) {
        synchronized(lock) {
            indexState.setRunning(sessionId, running)
            if (sessionId == currentIdOf()) rebuildCurrentLocked()
            emitSessionsLocked()
        }
    }

    /** Reorder one session on a durable user message, without touching anything else about it. */
    fun setUpdatedAt(sessionId: String, updatedAt: Long) {
        synchronized(lock) {
            indexState.setUpdatedAt(sessionId, updatedAt)
            emitSessionsLocked()
        }
    }

    fun setBlank(sessionId: String, blank: Boolean) {
        synchronized(lock) {
            indexState.setBlank(sessionId, blank)
            if (sessionId == currentIdOf()) openSessionState.setBlank(blank)
            emitSessionsLocked()
        }
    }

    fun setTitle(sessionId: String, title: String) {
        synchronized(lock) {
            indexState.setTitle(sessionId, title)
            emitSessionsLocked()
        }
    }

    fun upsertWorkspace(workspace: WorkspaceView) {
        synchronized(lock) {
            indexState.upsertWorkspace(workspace)
            emitWorkspacesLocked()
        }
    }

    fun removeWorkspace(workspaceId: String) {
        synchronized(lock) {
            indexState.removeWorkspace(workspaceId)
            emitWorkspacesLocked()
        }
    }

    fun setWorkspaceOrder(ids: List<String>) {
        synchronized(lock) {
            indexState.setWorkspaceOrder(ids)
            emitWorkspacesLocked()
        }
    }

    fun setArchived(ids: List<String>) {
        synchronized(lock) {
            archivedSessionIds.value = indexState.setArchived(ids)
        }
    }

    // ------------------------------------------------------------------ open-session fold
    fun rebuildCurrentLocked() {
        val sid = currentIdOf() ?: return
        currentConversation.value = openSessionState.rebuild(sid, indexState.running(sid))
    }

    fun emitSessionsLocked() {
        sessions.value = indexState.renderSessions()
    }

    fun emitWorkspacesLocked() {
        workspaces.value = indexState.orderedWorkspaces()
    }
}

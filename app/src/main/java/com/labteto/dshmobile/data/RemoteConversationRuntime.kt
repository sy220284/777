package com.labteto.dshmobile.data

import com.labteto.dshmobile.core.session.ChunkRows
import com.labteto.dshmobile.core.session.ConversationSnapshot
import com.labteto.dshmobile.core.session.QueueItem
import com.labteto.dshmobile.core.session.SessionEventEnvelope
import com.labteto.dshmobile.core.wire.DshApiClient
import com.labteto.dshmobile.core.wire.RpcResult
import com.labteto.dshmobile.core.wire.dto.SessionAddress
import com.labteto.dshmobile.core.wire.dto.SessionFollowFrame
import com.labteto.dshmobile.core.wire.dto.SessionHistoryRecord
import com.labteto.dshmobile.core.wire.dto.SessionPageRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Owns the currently open Remote Session's read path.
 *
 * The durable fold remains [OpenSessionFoldState]. This runtime owns follow snapshots/live frames,
 * backwards paging and bounded rebuild scheduling; SessionStore supplies the shared lock, the
 * current session identity and list-level metadata callbacks.
 */
internal class RemoteConversationRuntime(
    private val scope: CoroutineScope,
    private val lock: Any,
    private val currentSessionId: () -> String?,
    private val runningForSession: (String) -> Boolean?,
    private val apiProvider: () -> DshApiClient?,
    private val followSession: (sessionId: String, maxMessages: Int) -> Boolean,
    private val conversation: MutableStateFlow<ConversationSnapshot?>,
    private val loadingOlder: MutableStateFlow<Boolean>,
    private val loadOlderFailed: MutableStateFlow<Boolean>,
    private val onDurableEvent: (sessionId: String, event: SessionEventEnvelope) -> Unit,
    private val onConnectionRecovered: () -> Unit,
    private val logger: (String) -> Unit,
    private val connectionIdentity: () -> Any? = { apiProvider() },
) {
    private var openEpoch = 0L
    private val state = OpenSessionFoldState()
    private val rebuildTicks = Channel<Unit>(Channel.CONFLATED)

    init {
        observeRebuildTicks()
    }

    fun reset(blank: Boolean, clearPublished: Boolean) {
        synchronized(lock) {
            openEpoch += 1L
            state.reset(blank)
            if (clearPublished) conversation.value = null
            loadingOlder.value = false
            loadOlderFailed.value = false
        }
    }

    fun projection(key: String): JsonElement? = synchronized(lock) { state.projection(key) }

    fun setBlank(blank: Boolean) {
        synchronized(lock) { state.setBlank(blank) }
    }

    fun rebuild() {
        synchronized(lock) { rebuildLocked() }
    }

    fun startFollow(sessionId: String) {
        synchronized(lock) { state.clearFollowCursor() }
        if (!followSession(sessionId, HISTORY_PAGE_SIZE)) {
            logger("cannot follow $sessionId: no connection generation")
        }
    }

    fun handleFollowFrame(sessionId: String, frame: SessionFollowFrame) {
        when (frame) {
            is SessionFollowFrame.Snapshot -> applyFollowSnapshot(sessionId, frame)
            is SessionFollowFrame.Entry -> applyFollowEntry(sessionId, frame.record)
            is SessionFollowFrame.AssistantStream -> applyAssistantFrame(sessionId, frame)
        }
    }

    fun applyQueue(sessionId: String, items: List<QueueItem>) {
        synchronized(lock) {
            if (sessionId == currentSessionId()) {
                state.setQueue(items)
                rebuildLocked()
            }
        }
    }

    fun mergeProjection(sessionId: String, key: String, seq: Int, value: JsonElement) {
        synchronized(lock) {
            if (sessionId == currentSessionId()) {
                state.mergeProjection(key, seq, value)
                rebuildLocked()
            }
        }
    }

    fun applyProjectionBaseline(sessionId: String, block: JsonObject) {
        synchronized(lock) {
            if (sessionId != currentSessionId()) return@synchronized
            val asOf = block["asOfSeq"]?.jsonPrimitive?.intOrNull ?: 0
            (block["values"] as? JsonObject)?.forEach { (key, value) ->
                state.mergeProjection(key, asOf, value)
            }
            rebuildLocked()
        }
    }

    private data class PageOwner(
        val sessionId: String,
        val epoch: Long,
        val connection: Any,
        val api: DshApiClient,
        val request: SessionPageRequest,
    )

    private fun ownsPage(owner: PageOwner): Boolean =
        owner.epoch == openEpoch && owner.sessionId == currentSessionId() &&
            owner.connection === connectionIdentity()

    suspend fun loadOlder() = withContext(Dispatchers.Default) {
        val owner = synchronized(lock) {
            val sessionId = currentSessionId() ?: return@withContext
            val connection = connectionIdentity() ?: return@withContext
            val api = apiProvider() ?: return@withContext
            if (connection !== connectionIdentity() || loadingOlder.value) return@withContext
            val (oldestSeq, cursor) = state.pageAnchor()
            if (cursor == null) {
                logger("cannot page $sessionId: no follow cursor yet")
                return@withContext
            }
            loadingOlder.value = true
            PageOwner(sessionId, openEpoch, connection, api, SessionPageRequest(
                address = SessionAddress.Session(sessionId = sessionId),
                throughSeq = cursor,
                beforeSeq = oldestSeq?.toInt(),
                maxMessages = HISTORY_PAGE_SIZE,
            ))
        }
        try {
            when (val result = owner.api.sessionPage(owner.request)) {
                is RpcResult.Ok -> {
                    val envelopes = expandRecords(result.value.records)
                    val page = historyTail(envelopes)
                    val overDelivered = envelopes.size > page.size
                    synchronized(lock) {
                        if (!ownsPage(owner)) return@synchronized
                        onConnectionRecovered()
                        loadOlderFailed.value = false
                        state.prependPage(page, result.value.hasMore, overDelivered)
                        rebuildLocked()
                    }
                }
                is RpcResult.Err -> synchronized(lock) {
                    if (ownsPage(owner)) loadOlderFailed.value = true
                }
            }
        } finally {
            synchronized(lock) {
                if (ownsPage(owner)) loadingOlder.value = false
            }
        }
    }

    private fun applyFollowSnapshot(sessionId: String, frame: SessionFollowFrame.Snapshot) {
        val envelopes = expandRecords(frame.records)
        val page = historyTail(envelopes)
        val overDelivered = envelopes.size > page.size
        synchronized(lock) {
            if (currentSessionId() != sessionId) return@synchronized
            openEpoch += 1L
            loadingOlder.value = false
            loadOlderFailed.value = false
            onConnectionRecovered()
            state.installSnapshot(frame, page, overDelivered)
            rebuildLocked()
        }
    }

    private fun applyFollowEntry(sessionId: String, record: SessionHistoryRecord) {
        for (event in expandRecords(listOf(record))) {
            onDurableEvent(sessionId, event)
            synchronized(lock) {
                if (currentSessionId() == sessionId) {
                    state.acceptDurable(event)
                    rebuildTicks.trySend(Unit)
                }
            }
        }
    }

    private fun applyAssistantFrame(
        sessionId: String,
        frame: SessionFollowFrame.AssistantStream,
    ) {
        val changed = synchronized(lock) {
            if (currentSessionId() != sessionId) return
            state.acceptAssistant(frame)
        }
        if (changed) rebuildTicks.trySend(Unit)
    }

    private fun observeRebuildTicks() {
        scope.launch {
            for (ignored in rebuildTicks) {
                synchronized(lock) { rebuildLocked() }
                delay(REBUILD_INTERVAL_MS)
            }
        }
    }

    private fun rebuildLocked() {
        val sessionId = currentSessionId() ?: return
        conversation.value = state.rebuild(sessionId, runningForSession(sessionId))
    }

    private fun expandRecords(records: List<SessionHistoryRecord>): List<SessionEventEnvelope> =
        ChunkRows.expandAll(records).map(::wireEventToEnvelope)

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

    private companion object {
        const val HISTORY_PAGE_SIZE = 60
        const val MAX_PAGE_EVENTS = 4_000
        val SURFACE_EVENT_TYPES = setOf("user/message", "assistant/message", "tool/result")
        const val REBUILD_INTERVAL_MS = 50L
    }
}

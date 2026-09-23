package com.labteto.dshmobile.data

import com.labteto.dshmobile.core.session.AssistantLiveState
import com.labteto.dshmobile.core.session.ConversationSnapshot
import com.labteto.dshmobile.core.session.EventFold
import com.labteto.dshmobile.core.session.QueueItem
import com.labteto.dshmobile.core.session.SessionEventEnvelope
import com.labteto.dshmobile.core.wire.dto.SessionFollowFrame
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Owns the mutable fold state for the one remote session currently open in the foreground.
 *
 * SessionStore keeps the lock, streams and RPC orchestration. This class owns only the event window,
 * projection watermarks, paging cursor, queue and transient assistant attempt that must move together
 * when a follow snapshot, live event or backwards page arrives.
 */
internal class OpenSessionFoldState {
    private data class ProjectionValue(val seq: Int, val value: JsonElement)

    private val events = ArrayList<SessionEventEnvelope>()
    private var hasMore = false
    private var blank = true
    private val projections = HashMap<String, ProjectionValue>()
    private var queue = emptyList<QueueItem>()
    private var followCursor: Int? = null
    private val liveAssistant = AssistantLiveState()

    fun reset(blank: Boolean) {
        events.clear()
        hasMore = false
        this.blank = blank
        projections.clear()
        queue = emptyList()
        followCursor = null
        liveAssistant.clear()
    }

    fun clearFollowCursor() {
        followCursor = null
    }

    fun setBlank(value: Boolean) {
        blank = value
    }

    fun setQueue(items: List<QueueItem>) {
        queue = items
    }

    fun mergeProjection(key: String, seq: Int, value: JsonElement) {
        val existing = projections[key]
        if (existing == null || seq >= existing.seq) {
            projections[key] = ProjectionValue(seq, value)
        }
    }

    fun projection(key: String): JsonElement? = projections[key]?.value

    fun acceptDurable(envelope: SessionEventEnvelope) {
        val data = envelope.data as? JsonObject
        liveAssistant.acceptDurable(
            type = envelope.type,
            turn = data?.get("turn")?.jsonPrimitive?.intOrNull,
            step = data?.get("step")?.jsonPrimitive?.intOrNull,
            seq = envelope.seq,
            surfaceOp = envelope.surfaceOp,
        )
        appendEvent(envelope)
    }

    fun acceptAssistant(frame: SessionFollowFrame.AssistantStream): Boolean =
        liveAssistant.accept(frame.frame) != AssistantLiveState.Change.NONE

    fun installSnapshot(
        frame: SessionFollowFrame.Snapshot,
        page: List<SessionEventEnvelope>,
        overDelivered: Boolean,
    ) {
        followCursor = frame.cursor
        events.clear()
        events.addAll(page)
        events.sortBy(SessionEventEnvelope::seq)
        hasMore = frame.hasMore || overDelivered
        val asOf = frame.projections["asOfSeq"]?.jsonPrimitive?.intOrNull ?: frame.cursor
        (frame.projections["values"] as? JsonObject)?.forEach { (key, value) ->
            mergeProjection(key, asOf, value)
        }
        liveAssistant.seed(frame.assistantStream)
    }

    fun pageAnchor(): Pair<Long?, Int?> = events.firstOrNull()?.seq to followCursor

    fun prependPage(
        page: List<SessionEventEnvelope>,
        hostHasMore: Boolean,
        overDelivered: Boolean,
    ) {
        val existingSeqs = events.mapTo(HashSet()) { it.seq }
        val fresh = page.filter { it.seq !in existingSeqs }
        if (fresh.isNotEmpty()) {
            events.addAll(fresh)
            events.sortBy(SessionEventEnvelope::seq)
        }
        hasMore = nextHasMore(fresh.size, hostHasMore, overDelivered)
    }

    fun rebuild(sessionId: String, running: Boolean?): ConversationSnapshot {
        val durable = events.toList()
        val snapshot = EventFold(sessionId).fold(durable, liveAssistant.transientEnvelopes())
        return snapshot.copy(
            blank = if (durable.isEmpty()) blank else snapshot.blank,
            running = running ?: snapshot.running,
            hasMore = hasMore,
            queue = queue,
            projections = projections.mapValues { it.value.value },
        )
    }

    private fun appendEvent(envelope: SessionEventEnvelope) {
        val lastSeq = events.lastOrNull()?.seq
        if (lastSeq == null || envelope.seq > lastSeq) {
            events.add(envelope)
            return
        }
        val index = events.indexOfFirst { it.seq == envelope.seq }
        if (index >= 0) {
            events[index] = envelope
        } else {
            events.add(envelope)
            events.sortBy(SessionEventEnvelope::seq)
        }
    }
}

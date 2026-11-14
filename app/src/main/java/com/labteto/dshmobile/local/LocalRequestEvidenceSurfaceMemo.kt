package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.model.LocalRequestMessageSurfaceState
import com.labteto.dshmobile.local.model.encodeLocalRequestMessageSurface
import com.labteto.dshmobile.local.model.replayLocalRequestMessageSurface
import com.labteto.dshmobile.local.session.LocalSessionEventLog
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Request evidence memo: a non-authoritative cache of references into the existing SessionEventLog.
 *
 * Message, tool and context surfaces use the same identity, digest and sequence contract.
 * This class does not persist, reconstruct, or own evidence: the EventLog is the sole authority.
 * After log recreation/truncation, a reference with a sequence above the current log tip is
 * invalidated and its original payload is appended again by the request coordinator.
 */
internal enum class LocalRequestSurfaceKind { MESSAGE, TOOL, CONTEXT }

internal class LocalRequestEvidenceSurfaceMemo {
    private data class Reference(val digest: String, val sequence: Long)
    private val lock = Any()
    private val entries = mutableMapOf<LocalRequestSurfaceKind, MutableMap<String, Reference>>()
    // Small process-local acceleration only. The committed EventLog remains authoritative.
    private val messages = linkedMapOf<String, LocalRequestMessageSurfaceState>()

    /**
     * A single writer lease covers base selection and surface publication. The first call
     * after a process restart rehydrates the last durable surface; it never duplicates
     * the whole existing request history just because this memo was lost.
     */
    fun ensureMessages(
        log: LocalSessionEventLog,
        key: String,
        digest: String,
        visibleMessages: List<JsonObject>,
        redacted: Boolean,
    ): Long = synchronized(lock) {
        log.withWriterLease {
            val memoKey = key + "\u0000" + log.historyInstanceIdentity +
                "\u0000" + log.resetGeneration
            var previous = messages[memoKey]?.takeIf { it.sequence <= log.latestSequence() }
            if (previous == null) {
                val last = log.latest("request/message-surface")
                if (last != null) {
                    val issues = mutableListOf<String>()
                    val restored = replayLocalRequestMessageSurface(
                        last,
                        eventAt = { seq ->
                            log.pageAfter(seq - 1L, 1).singleOrNull()
                                ?.takeIf { it.sequence == seq }
                        },
                        issues = issues,
                    )
                    check(issues.isEmpty() && restored != null) {
                        "历史请求消息证据损坏，停止追加：${issues.joinToString()}"
                    }
                    val oldDigest = (last.data["digest"] as? JsonPrimitive)?.contentOrNull
                        ?: error("历史消息面缺少摘要")
                    previous = LocalRequestMessageSurfaceState(
                        sequence = last.sequence,
                        messages = restored,
                        digest = oldDigest,
                        depth = (last.data["depth"] as? JsonPrimitive)?.intOrNull ?: 0,
                        redacted = (last.data["redacted"] as? JsonPrimitive)?.booleanOrNull ?: false,
                    )
                }
            }
            if (previous != null && previous.digest == digest &&
                previous.redacted == redacted && previous.messages == visibleMessages
            ) {
                messages[memoKey] = previous
                return@withWriterLease previous.sequence
            }
            val next = encodeLocalRequestMessageSurface(
                messages = visibleMessages,
                digest = digest,
                redacted = redacted,
                previous = previous,
            )
            val sequence = log.append("request/message-surface", next.payload).sequence
            messages[memoKey] = LocalRequestMessageSurfaceState(
                sequence, visibleMessages.toList(), digest, next.depth, redacted,
            )
            // A runtime can visit many different routes; never pin unbounded old contexts in RAM.
            while (messages.size > 24) messages.remove(messages.keys.first())
            sequence
        }
    }

    fun ensure(
        kind: LocalRequestSurfaceKind,
        key: String,
        digest: String,
        currentLogSequence: Long,
        append: () -> Long,
    ): Long = synchronized(lock) {
        val surface = entries.getOrPut(kind) { mutableMapOf() }
        val cached = surface[key]
        if (cached?.digest == digest && cached.sequence <= currentLogSequence) {
            cached.sequence
        } else {
            // Only memoize after the durable EventLog append actually succeeds.
            val sequence = append()
            surface[key] = Reference(digest, sequence)
            sequence
        }
    }
}

package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.session.LocalSessionEventLog
import java.util.WeakHashMap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

private val pendingTurnJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
private const val PENDING_EVENT = "chat/pending-turn"

private data class PendingBatchCacheKey(
    val processedThroughSequence: Long,
    val upper: Long,
    val generation: Long,
    val scope: String,
    val wanted: Int,
    val activeBranchIds: Set<String>?,
)

private object PendingBatchRetryCache {
    private val entries = WeakHashMap<LocalSessionEventLog, Pair<PendingBatchCacheKey, List<ChatPendingTurn>>>()

    @Synchronized
    fun get(log: LocalSessionEventLog, key: PendingBatchCacheKey): List<ChatPendingTurn>? =
        entries[log]?.takeIf { it.first == key }?.second

    @Synchronized
    fun put(log: LocalSessionEventLog, key: PendingBatchCacheKey, value: List<ChatPendingTurn>) {
        entries[log] = key to value
    }
}


/** Full facts stay durable; the context only carries a small request-time window. */
internal fun ChatContextState.enqueuePendingDurably(
    turn: ChatPendingTurn,
    eventLog: LocalSessionEventLog,
    scope: String = "direct",
): ChatContextState {
    // Legacy snapshots may contain a larger queue. Archive it before applying the new bound.
    if (!pendingArchiveReady || pendingTurns.size > MAX_PENDING_CONTEXT_TURNS) {
        pendingTurns.forEach { eventLog.persistPendingTurn(it, scope) }
    }
    eventLog.persistPendingTurn(turn, scope)
    return enqueuePending(turn).copy(pendingArchiveReady = true)
}

internal fun ChatContextState.boundDurablePending(
    eventLog: LocalSessionEventLog,
    scope: String = "direct",
): ChatContextState {
    if (!pendingArchiveReady) pendingTurns.forEach { eventLog.persistPendingTurn(it, scope) }
    return boundedPendingWindow().copy(pendingArchiveReady = true)
}

private fun LocalSessionEventLog.persistPendingTurn(turn: ChatPendingTurn, scope: String) {
    val data = pendingTurnJson.encodeToJsonElement(ChatPendingTurn.serializer(), turn).jsonObject
    append(PENDING_EVENT, kotlinx.serialization.json.JsonObject(data + ("scope" to JsonPrimitive(scope))))
}

/** Reads the oldest unfinished batch, including facts that have left the in-memory window. */
internal fun ChatContextState.loadPendingBatch(
    eventLog: LocalSessionEventLog,
    limit: Int,
    scope: String = "direct",
    activeBranchMessageIds: Set<String>? = null,
): List<ChatPendingTurn> {
    val upper = maxOf(pendingThroughSequence, pendingTurns.maxOfOrNull { it.sequence } ?: -1L)
    if (upper <= processedThroughSequence) return emptyList()
    val wanted = limit.coerceIn(1, 64)
    val cacheKey = PendingBatchCacheKey(
        processedThroughSequence = processedThroughSequence,
        upper = upper,
        generation = generation,
        scope = scope,
        wanted = wanted,
        activeBranchIds = activeBranchMessageIds?.toSet(),
    )
    PendingBatchRetryCache.get(eventLog, cacheKey)?.let { return it }

    val candidates = linkedMapOf<Pair<Long, String>, ChatPendingTurn>()
    fun collect(turn: ChatPendingTurn) {
        if (turn.sequence <= processedThroughSequence || turn.sequence > upper) return
        if (activeBranchMessageIds != null && turn.branchHeadId.isNotBlank() && turn.assistantMessageId !in activeBranchMessageIds) return
        candidates.putIfAbsent(turn.sequence to turn.assistantMessageId, turn.copy(generation = generation))
    }
    val scanThrough = eventLog.latestSequence()
    var cursor = processedThroughSequence
    while (cursor < scanThrough) {
        val page = eventLog.pageAfter(cursor, 200)
        if (page.isEmpty()) break
        for (event in page) {
            if (event.sequence > scanThrough) break
            if (
                event.type == PENDING_EVENT &&
                (event.data["scope"] as? JsonPrimitive)?.contentOrNull == scope
            ) {
                collect(pendingTurnJson.decodeFromJsonElement(ChatPendingTurn.serializer(), event.data))
                if (candidates.size > wanted) {
                    val newest = candidates.keys.maxBy { it.first }
                    candidates.remove(newest)
                }
            }
            cursor = event.sequence
        }
        if (page.last().sequence >= scanThrough || page.size < 200) break
    }
    // Pre-upgrade queues have no markers yet. They are archived on the next enqueue.
    pendingTurns.forEach { turn ->
        collect(turn)
        if (candidates.size > wanted) candidates.remove(candidates.keys.maxBy { it.first })
    }
    return candidates.values.sortedBy(ChatPendingTurn::sequence).take(wanted).also { batch ->
        PendingBatchRetryCache.put(eventLog, cacheKey, batch)
    }
}

/** A continuation owns a new log and must import unfinished facts using that log's sequences. */
internal fun ChatContextState.continuePendingInSession(
    sessionsRoot: java.io.File,
    sourceId: String,
    targetId: String,
    scope: String,
    activeBranchMessageIds: Set<String>? = null,
): ChatContextState {
    require(sourceId != targetId) { "继续会话需要新的日志身份" }
    fun log(id: String): LocalSessionEventLog {
        val file = java.io.File(sessionsRoot, "$id.events.jsonl").canonicalFile
        require(file.parentFile == sessionsRoot.canonicalFile) { "会话日志路径无效" }
        return LocalSessionEventLog(file, pendingTurnJson)
    }
    val source = log(sourceId)
    val target = log(targetId)
    var remaining = boundDurablePending(source, scope)
    var result = copy(
        processedThroughSequence = -1L, pendingThroughSequence = -1L,
        pendingTurns = emptyList(), pendingArchiveReady = true, generation = generation + 1L,
        sceneEvents = sceneEvents.mapIndexed { index, event ->
            event.copy(sequence = (index - sceneEvents.size).toLong())
        },
    )
    while (true) {
        val batch = remaining.loadPendingBatch(source, 8, scope, activeBranchMessageIds)
        if (batch.isEmpty()) break
        for (turn in batch) {
            val marker = target.append("chat/pending-transfer", kotlinx.serialization.json.buildJsonObject {
                put("source_session", JsonPrimitive(sourceId))
                put("source_sequence", JsonPrimitive(turn.sequence))
            })
            result = result.enqueuePendingDurably(
                turn.copy(sequence = marker.sequence, branchHeadId = "", generation = result.generation), target, scope,
            )
        }
        remaining = remaining.commitProcessed(remaining.scene, remaining.continuity, batch.last().sequence)
    }
    return result
}

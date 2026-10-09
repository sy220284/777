package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.memory.MemoryStore
import com.labteto.dshmobile.local.session.LocalSessionEventLog
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Recoverable Work-owned projection cleanup after historical timeline changes. */
@Singleton
internal class LocalWorkTimelineMemoryRecovery @Inject constructor(
    private val memoryStore: MemoryStore,
) {
    internal fun recover(log: LocalSessionEventLog): Boolean {
        val rewrite = log.latestMatching(setOf("work/active-transcript")) { data ->
            data["edit_id"]?.jsonPrimitive?.contentOrNull != null &&
                data["memory_rollback"] is JsonObject
        } ?: return false
        val id = rewrite.data["edit_id"]?.jsonPrimitive?.contentOrNull ?: return false
        val applied = log.latestMatching(setOf("work/memory-rollback-committed")) { data ->
            data["edit_id"]?.jsonPrimitive?.contentOrNull == id
        }
        if (applied != null && applied.sequence > rewrite.sequence) return false

        val rollback = rewrite.data["memory_rollback"] as? JsonObject ?: return false
        val sessionId = rollback["session_id"]?.jsonPrimitive?.contentOrNull ?: return false
        val from = (rollback["created_at"] as? JsonPrimitive)?.longOrNull ?: return false
        val messages = (rollback["message_ids"] as? JsonArray)?.mapNotNull {
            (it as? JsonPrimitive)?.contentOrNull
        }?.toSet() ?: return false

        // MemoryStore rollback is idempotent; the commit marker is durable and restart-recoverable.
        memoryStore.rollbackSourceSessionFrom(sessionId, from, messages)
        log.append("work/memory-rollback-committed", buildJsonObject { put("edit_id", id) })
        return true
    }
}

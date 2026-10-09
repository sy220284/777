package com.labteto.dshmobile.local

import com.labteto.dshmobile.harness.session.ModelHistoryCheckpointCodec
import com.labteto.dshmobile.local.agent.LOCAL_AGENT_INBOX_EVENT_TYPE
import com.labteto.dshmobile.local.chat.timelineRewriteModelHistory
import com.labteto.dshmobile.local.model.LocalCanonicalModelCodec
import com.labteto.dshmobile.local.model.LocalModelPromptUpdateMode
import com.labteto.dshmobile.local.session.LocalSessionEventLog
import com.labteto.dshmobile.local.session.assistantModelMessageFromEvent
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal data class LocalModelHistoryRestore(
    val messages: List<JsonObject>,
    val replayedTail: Boolean,
    val usedLegacyFallback: Boolean,
    val checkpointRecommended: Boolean,
)

/**
 * Restore model-visible history from the latest valid durable checkpoint and semantic tail events.
 *
 * Legacy JSON history is used only when no valid checkpoint exists. Because old snapshots do not
 * carry a precise model-history event cursor, their overlapping event tail is deliberately not
 * replayed; the caller should immediately write a durable checkpoint and rewrite the snapshot.
 */
internal fun restoreLocalModelHistory(
    events: List<LocalSessionEventLog.Event>,
    legacyFallback: List<JsonObject>,
    codec: ModelHistoryCheckpointCodec,
): LocalModelHistoryRestore {
    val latestCheckpointIndex = events.indexOfLast { event ->
        event.type == ModelHistoryCheckpointCodec.EVENT_TYPE
    }

    var checkpointIndex = -1
    var checkpointMessages: List<JsonObject>? = null
    var checkpointAsOfSequence: Long? = null
    for (index in events.indices.reversed()) {
        val event = events[index]
        if (event.type != ModelHistoryCheckpointCodec.EVENT_TYPE) continue
        val decoded = codec.decodeCheckpoint(event.data) ?: continue
        val decodedWatermark = decoded.asOfSequence
        if (decodedWatermark != null && decodedWatermark >= event.sequence) continue
        checkpointIndex = index
        checkpointMessages = decoded.messages
        checkpointAsOfSequence = decodedWatermark
        break
    }

    val usedLegacyFallback = checkpointMessages == null && legacyFallback.isNotEmpty()
    val history = when {
        checkpointMessages != null -> checkpointMessages.toMutableList()
        usedLegacyFallback -> legacyFallback.toMutableList()
        else -> mutableListOf()
    }
    val replayStart = when {
        checkpointIndex >= 0 && checkpointAsOfSequence != null ->
            events.indexOfFirst { event -> event.sequence > checkpointAsOfSequence!! }
                .takeIf { it >= 0 }
                ?: events.size
        checkpointIndex >= 0 -> checkpointIndex + 1
        usedLegacyFallback -> events.size
        else -> 0
    }

    var replayed = false
    for (index in replayStart until events.size) {
        if (applyModelHistoryEvent(history, events[index])) replayed = true
    }

    val invalidCheckpointAfterRestore = latestCheckpointIndex > checkpointIndex
    val checkpointNeedsWatermark =
        checkpointMessages != null && checkpointAsOfSequence == null
    val (sanitizedHistory, repairedStructure) = sanitizeRestoredModelHistory(history)
    return LocalModelHistoryRestore(
        messages = sanitizedHistory,
        replayedTail = replayed,
        usedLegacyFallback = usedLegacyFallback,
        checkpointRecommended =
            usedLegacyFallback ||
                replayed ||
                invalidCheckpointAfterRestore ||
                checkpointNeedsWatermark ||
                repairedStructure,
    )
}

private fun sanitizeRestoredModelHistory(source: List<JsonObject>): Pair<List<JsonObject>, Boolean> {
    val restored = mutableListOf<JsonObject>()
    var pendingBatch = mutableListOf<JsonObject>()
    var pendingCallIds = linkedSetOf<String>()
    var changed = false

    fun discardPendingBatch() {
        if (pendingBatch.isNotEmpty()) changed = true
        pendingBatch = mutableListOf()
        pendingCallIds = linkedSetOf()
    }

    source.forEach { message ->
        when (message["role"]?.jsonPrimitive?.contentOrNull) {
            "assistant" -> {
                if (pendingCallIds.isNotEmpty()) discardPendingBatch()
                val callIds = modelToolCallIds(message)
                if (callIds == null) {
                    changed = true
                } else if (callIds.isEmpty()) {
                    restored += message
                } else if (callIds.size != callIds.toSet().size) {
                    changed = true
                } else {
                    pendingBatch += message
                    pendingCallIds += callIds
                }
            }
            "tool" -> {
                val callId = message["tool_call_id"]?.jsonPrimitive?.contentOrNull
                if (callId != null && pendingCallIds.remove(callId)) {
                    pendingBatch += message
                    if (pendingCallIds.isEmpty()) {
                        restored += pendingBatch
                        pendingBatch = mutableListOf()
                    }
                } else {
                    changed = true
                }
            }
            else -> {
                if (pendingCallIds.isNotEmpty()) discardPendingBatch()
                restored += message
            }
        }
    }
    if (pendingCallIds.isNotEmpty()) discardPendingBatch()
    return restored to (changed || restored.size != source.size)
}

private fun modelToolCallIds(message: JsonObject): List<String>? {
    val rawCalls = message["tool_calls"] ?: message["model_tool_calls"] ?: return emptyList()
    val calls = rawCalls as? JsonArray ?: return null
    return calls.map { raw ->
        (raw as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
            ?: return null
    }
}

private fun applyModelHistoryEvent(
    history: MutableList<JsonObject>,
    event: LocalSessionEventLog.Event,
): Boolean {
    return when (event.type) {
        "system/prompt" -> {
            val content = event.data["content"]?.jsonPrimitive?.contentOrNull ?: return false
            val updateMode = event.data["update_mode"]?.jsonPrimitive?.contentOrNull
            val modelContent = event.data["model_content"]?.jsonPrimitive?.contentOrNull ?: content
            val message = buildJsonObject {
                put("role", "system")
                put("content", modelContent)
            }
            val hasSystem = history.firstOrNull()?.get("role")?.jsonPrimitive?.contentOrNull == "system"
            if (hasSystem && updateMode == LocalModelPromptUpdateMode.APPEND_ONLY.name.lowercase()) {
                history += message
            } else if (hasSystem) {
                history[0] = message
            } else {
                history.add(0, message)
            }
            true
        }
        "chat/active-transcript", "work/active-transcript" -> {
            val rewritten = if (event.type == "chat/active-transcript") {
                timelineRewriteModelHistory(event.data)
            } else {
                val encoded = event.data["model_history"] as? JsonArray ?: return false
                encoded.map { it as? JsonObject ?: return false }
            } ?: return false
            history.clear()
            history += rewritten
            true
        }
        "user/message" -> {
            // queued=true records the human-visible transcript immediately. It becomes model-visible
            // only when a later user/queue consumed or resumed event carries model_messages.
            // Ignoring the original queued row prevents stop/crash/restart from resurrecting
            // a message the user already cancelled.
            if (event.data["queued"]?.jsonPrimitive?.booleanOrNull == true) return false
            val structured = event.data["model_message"] as? JsonObject
            if (structured != null) {
                history += structured
                true
            } else {
                val content = event.data["content"]?.jsonPrimitive?.contentOrNull ?: return false
                history += buildJsonObject {
                    put("role", "user")
                    put("content", content)
                }
                true
            }
        }
        "user/queue", LOCAL_AGENT_INBOX_EVENT_TYPE -> {
            val action = event.data["action"]?.jsonPrimitive?.contentOrNull
            val modelVisible = when (event.type) {
                "user/queue" -> action in setOf("consumed", "resumed")
                LOCAL_AGENT_INBOX_EVENT_TYPE -> action in setOf("claimed", "resumed")
                else -> false
            }
            if (!modelVisible) return false
            val messages = event.data["model_messages"] as? JsonArray ?: return false
            var changed = false
            messages.forEach { element ->
                val message = element as? JsonObject ?: return@forEach
                if (message["role"]?.jsonPrimitive?.contentOrNull != "user") return@forEach
                history += message
                changed = true
            }
            changed
        }
        "assistant/message" -> {
            val message = assistantModelMessageFromEvent(event.data)
            if (message["role"]?.jsonPrimitive?.contentOrNull != "assistant") return false
            if (event.data["replaces"]?.jsonPrimitive?.contentOrNull != null &&
                history.lastOrNull()?.get("role")?.jsonPrimitive?.contentOrNull == "assistant"
            ) history.removeAt(history.lastIndex)
            history += message
            true
        }
        "tool/result" -> {
            val nested = event.data["message"] as? JsonObject
            val callId = nested?.get("tool_call_id")?.jsonPrimitive?.contentOrNull
                ?: event.data["id"]?.jsonPrimitive?.contentOrNull
                ?: return false

            // A tool result is valid only inside the currently open assistant tool batch. Recovery
            // must never manufacture an orphan result when an assistant event was lost/corrupted.
            var ownerIndex = history.lastIndex
            val currentBatchResults = linkedSetOf<String>()
            while (ownerIndex >= 0 &&
                history[ownerIndex]["role"]?.jsonPrimitive?.contentOrNull == "tool"
            ) {
                history[ownerIndex]["tool_call_id"]?.jsonPrimitive?.contentOrNull
                    ?.let(currentBatchResults::add)
                ownerIndex -= 1
            }
            val owner = history.getOrNull(ownerIndex)
                ?.takeIf { it["role"]?.jsonPrimitive?.contentOrNull == "assistant" }
                ?: return false
            val ownerCalls = runCatching {
                LocalCanonicalModelCodec.canonicalToolCalls(owner).mapTo(linkedSetOf()) { it.id }
            }.getOrNull() ?: return false
            if (callId !in ownerCalls || callId in currentBatchResults) return false

            val message = if (nested?.get("role")?.jsonPrimitive?.contentOrNull == "tool") {
                nested
            } else {
                buildJsonObject {
                    put("role", "tool")
                    put("tool_call_id", callId)
                    put(
                        "content",
                        event.data["model_content"]?.jsonPrimitive?.contentOrNull
                            ?: event.data["content"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    )
                }
            }
            history += message
            true
        }
        else -> false
    }
}

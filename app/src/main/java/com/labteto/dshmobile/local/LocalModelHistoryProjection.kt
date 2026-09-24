package com.labteto.dshmobile.local

import com.labteto.dshmobile.harness.session.ModelHistoryCheckpointCodec
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
    for (index in events.indices.reversed()) {
        val event = events[index]
        if (event.type != ModelHistoryCheckpointCodec.EVENT_TYPE) continue
        val decoded = codec.decode(event.data) ?: continue
        checkpointIndex = index
        checkpointMessages = decoded
        break
    }

    val usedLegacyFallback = checkpointMessages == null && legacyFallback.isNotEmpty()
    val history = when {
        checkpointMessages != null -> checkpointMessages.toMutableList()
        usedLegacyFallback -> legacyFallback.toMutableList()
        else -> mutableListOf()
    }
    val replayStart = when {
        checkpointIndex >= 0 -> checkpointIndex + 1
        usedLegacyFallback -> events.size
        else -> 0
    }

    var replayed = false
    for (index in replayStart until events.size) {
        if (applyModelHistoryEvent(history, events[index])) replayed = true
    }

    val invalidCheckpointAfterRestore = latestCheckpointIndex > checkpointIndex
    return LocalModelHistoryRestore(
        messages = history,
        replayedTail = replayed,
        usedLegacyFallback = usedLegacyFallback,
        checkpointRecommended = usedLegacyFallback || replayed || invalidCheckpointAfterRestore,
    )
}

private fun applyModelHistoryEvent(
    history: MutableList<JsonObject>,
    event: LocalSessionEventLog.Event,
): Boolean {
    return when (event.type) {
        "system/prompt" -> {
            val content = event.data["content"]?.jsonPrimitive?.contentOrNull ?: return false
            val message = buildJsonObject {
                put("role", "system")
                put("content", content)
            }
            if (history.firstOrNull()?.get("role")?.jsonPrimitive?.contentOrNull == "system") {
                history[0] = message
            } else {
                history.add(0, message)
            }
            true
        }
        "user/message" -> {
            // queued=true records the human-visible transcript immediately, but it does not become
            // model-visible until a later durable history checkpoint consumes that queue item.
            // Ignoring an uncheckpointed queued tail prevents stop/crash/restart from resurrecting
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
        "user/queue" -> {
            val action = event.data["action"]?.jsonPrimitive?.contentOrNull
            if (action !in setOf("consumed", "resumed")) return false
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
            history += message
            true
        }
        "tool/result" -> {
            val nested = event.data["message"] as? JsonObject
            val callId = nested?.get("tool_call_id")?.jsonPrimitive?.contentOrNull
                ?: event.data["id"]?.jsonPrimitive?.contentOrNull
                ?: return false
            val alreadyPresent = history.any { message ->
                message["role"]?.jsonPrimitive?.contentOrNull == "tool" &&
                    message["tool_call_id"]?.jsonPrimitive?.contentOrNull == callId
            }
            if (alreadyPresent) return false
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

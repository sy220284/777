package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.session.LocalSessionEventLog
import com.labteto.dshmobile.local.session.decodeTranscriptMessages
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Recover a proactive Chat message that became durable before its automation bookkeeping finished.
 *
 * Recovery starts from the newest event page and walks backwards, so a long-lived conversation does
 * not stream the complete archive merely to inspect the latest interrupted automation turn.
 */
internal fun recoverAutomationChatOutput(
    eventLog: LocalSessionEventLog,
    startedAt: Long?,
): String? {
    val threshold = startedAt ?: return null
    val start = findLatestProactiveTurnStart(eventLog, threshold) ?: return null

    var beforeSequenceExclusive = Long.MAX_VALUE
    var turnEnded = false
    var recovered: Pair<Long, String>? = null
    while (true) {
        val page = eventLog.pageBeforeNewestFirst(
            sequenceExclusive = beforeSequenceExclusive,
            limit = AUTOMATION_RECOVERY_PAGE_SIZE,
        )
        if (page.isEmpty()) break

        page.forEach { event ->
            if (event.sequence <= start.sequence) return@forEach
            when (event.type) {
                "turn/end" -> turnEnded = true
                "assistant/message" -> {
                    if (recovered == null) {
                        decodeTranscriptMessages(event.data)
                            .orEmpty()
                            .lastOrNull { message ->
                                message.role == "assistant" && message.proactive
                            }
                            ?.let { message -> recovered = event.sequence to message.content }
                    }
                }
            }
        }

        val oldestSequence = page.minOf(LocalSessionEventLog.Event::sequence)
        if (oldestSequence <= start.sequence || page.size < AUTOMATION_RECOVERY_PAGE_SIZE) break
        beforeSequenceExclusive = oldestSequence
    }

    val output = recovered?.second ?: return null
    if (!turnEnded) {
        eventLog.append("turn/end", buildJsonObject {
            put("reason", "completed")
            put("steps", 1)
            put("mode", "chat")
            put("automation", true)
            put("proactive", true)
            put("recovered", true)
        })
    }
    return output
}

private fun findLatestProactiveTurnStart(
    eventLog: LocalSessionEventLog,
    threshold: Long,
): LocalSessionEventLog.Event? {
    var beforeSequenceExclusive = Long.MAX_VALUE
    while (true) {
        val page = eventLog.pageBeforeNewestFirst(
            sequenceExclusive = beforeSequenceExclusive,
            limit = AUTOMATION_RECOVERY_PAGE_SIZE,
        )
        if (page.isEmpty()) return null

        page.firstOrNull { event ->
            event.createdAt >= threshold &&
                event.type == "turn/start" &&
                event.data["automation"]?.jsonPrimitive?.booleanOrNull == true &&
                event.data["proactive"]?.jsonPrimitive?.booleanOrNull == true
        }?.let { return it }

        val oldestSequence = page.minOf(LocalSessionEventLog.Event::sequence)
        if (page.size < AUTOMATION_RECOVERY_PAGE_SIZE || oldestSequence <= 0L) return null
        beforeSequenceExclusive = oldestSequence
    }
}

private const val AUTOMATION_RECOVERY_PAGE_SIZE = 200

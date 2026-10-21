package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.session.CHAT_STREAM_CHECKPOINT_EVENT
import com.labteto.dshmobile.local.session.LocalHarnessMessage
import com.labteto.dshmobile.local.session.LocalSessionEventLog
import com.labteto.dshmobile.local.session.encodeTranscriptMessages
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Partially generated Chat text is visible and recoverable, never a completed model
 * response or authority to execute tools. Recovery is intentionally one-shot.
 */
private const val CHAT_PARTIAL_VISIBLE_EVENT = "assistant/partial-visible"

internal fun recoverInterruptedChatReply(log: LocalSessionEventLog): LocalHarnessMessage? {
    val lastTurn = log.latest("turn/start") ?: return null
    if (lastTurn.data["mode"]?.jsonPrimitive?.contentOrNull != "chat") return null
    // Failed regenerations retain the previously selected answer. Their provisional text
    // must not appear as an independent reply after cancellation or process restart.
    if (lastTurn.data["replaces"]?.jsonPrimitive?.contentOrNull != null) return null
    val turnEvents = log.snapshotAfter(lastTurn.sequence)
    // A retry can reuse request_uid and restart chunk_index from zero. Select ONLY the
    // last admitted provider attempt; never splice chunks across attempts or display
    // an abandoned attempt after a later attempt produced no visible text.
    val newestProviderAttempt = turnEvents.lastOrNull { it.type == "request/provider-attempt" }
    val checkpoints = turnEvents.filter {
        it.type == CHAT_STREAM_CHECKPOINT_EVENT &&
            it.sequence > (newestProviderAttempt?.sequence ?: lastTurn.sequence)
    }
    val last = checkpoints.lastOrNull() ?: return null
    if (newestProviderAttempt != null &&
        newestProviderAttempt.data["request_uid"]?.jsonPrimitive?.contentOrNull !=
            last.data["request_uid"]?.jsonPrimitive?.contentOrNull) return null
    if ((log.latest("assistant/message")?.sequence ?: -1L) > last.sequence) return null
    if ((log.latest(CHAT_PARTIAL_VISIBLE_EVENT)?.sequence ?: -1L) > last.sequence) return null
    val uid = last.data["request_uid"]?.jsonPrimitive?.contentOrNull
        ?.takeIf(String::isNotBlank) ?: return null

    val recovered = StringBuilder()
    var expectedIndex = 0
    for (event in checkpoints) {
        if (event.data["request_uid"]?.jsonPrimitive?.contentOrNull != uid) return null
        val index = (event.data["chunk_index"] as? JsonPrimitive)?.intOrNull ?: return null
        if (index != expectedIndex) return null // Never join a torn or missing chunk series.
        val chunk = event.data["content"]?.jsonPrimitive?.contentOrNull ?: return null
        if (chunk.length > 4_096 || recovered.length + chunk.length > 262_144) return null
        recovered.append(chunk)
        expectedIndex++
    }
    if (recovered.isBlank()) return null

    val text = recovered.toString() + if (recovered.length == 262_144)
        "\n【已达到部分回复保存上限，后续内容可能未被保留】" else ""
    val message = LocalHarnessMessage(
        id = "interrupted-stream:$uid",
        role = "assistant",
        content = "【部分回复 · 本轮未完成】\n$text",
        createdAt = last.createdAt,
    )
    log.append(CHAT_PARTIAL_VISIBLE_EVENT, buildJsonObject {
        put("request_uid", uid)
        put("completion_reason", "INTERRUPTED")
        put("transcript", encodeTranscriptMessages(listOf(message)))
    })
    return message
}

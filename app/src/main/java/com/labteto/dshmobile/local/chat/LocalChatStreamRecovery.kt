package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.session.LocalHarnessMessage
import com.labteto.dshmobile.local.session.LocalSessionEventLog
import com.labteto.dshmobile.local.session.encodeTranscriptMessages
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Visible text only. No reasoning, tool arguments or executable model-history changes. */
internal const val CHAT_STREAM_CHECKPOINT_EVENT = "chat/stream-checkpoint"
private const val CHAT_PARTIAL_VISIBLE_EVENT = "assistant/partial-visible"

/** Materialize a stranded Chat checkpoint at most once, without replaying the provider request. */
internal fun recoverInterruptedChatReply(log: LocalSessionEventLog): LocalHarnessMessage? {
    val lastTurn = log.latest("turn/start") ?: return null
    if (lastTurn.data["mode"]?.jsonPrimitive?.contentOrNull != "chat") return null
    val checkpoint = log.latest(CHAT_STREAM_CHECKPOINT_EVENT) ?: return null
    if (checkpoint.sequence <= lastTurn.sequence) return null
    if ((log.latest("assistant/message")?.sequence ?: -1L) > checkpoint.sequence) return null
    if ((log.latest(CHAT_PARTIAL_VISIBLE_EVENT)?.sequence ?: -1L) > checkpoint.sequence) return null
    val uid = checkpoint.data["request_uid"]?.jsonPrimitive?.contentOrNull
        ?.takeIf(String::isNotBlank) ?: return null
    val content = checkpoint.data["content"]?.jsonPrimitive?.contentOrNull
        ?.takeIf(String::isNotBlank) ?: return null
    val message = LocalHarnessMessage(
        id = "interrupted-stream:$uid",
        role = "assistant",
        content = "【部分回复 · 本轮未完成】\n$content",
        createdAt = checkpoint.createdAt,
    )
    log.append(CHAT_PARTIAL_VISIBLE_EVENT, buildJsonObject {
        put("request_uid", uid)
        put("completion_reason", "INTERRUPTED")
        put("transcript", encodeTranscriptMessages(listOf(message)))
    })
    return message
}

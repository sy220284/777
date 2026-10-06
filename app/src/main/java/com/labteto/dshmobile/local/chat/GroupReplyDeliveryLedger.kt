package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.session.LocalSessionEventLog
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Owns one group turn's delivery failures, durable projection and terminal delivery facts. */
internal class GroupReplyDeliveryLedger(
    private val sessionId: String,
    previousFailedMemberIds: List<String>,
    responderIds: List<String>,
    private val state: LocalChatStatePort,
    private val persist: suspend (String) -> Unit,
) {
    private val failedIds = previousFailedMemberIds.filterNot { it in responderIds }.toMutableSet()

    suspend fun fail(memberId: String, group: LocalGroupChatState): LocalGroupChatState {
        failedIds += memberId
        return publish(group)
    }

    suspend fun publish(group: LocalGroupChatState): LocalGroupChatState {
        val candidate = group.copy(failedReplyMemberIds = failedIds.toList())
        var accepted = false
        state.update { current ->
            accepted = current.sessionId == sessionId
            if (accepted) {
                current.copy(chat = current.chat.copy(groupChat = candidate))
            } else {
                current
            }
        }
        if (accepted) persist(sessionId)
        return candidate
    }

    fun recordCompletedOutcome(log: LocalSessionEventLog, replies: Int) {
        log.append("turn/end", buildJsonObject {
            put("reason", "completed")
            put("mode", "group-chat")
            put("replies", replies)
            put("partial_success", failedIds.isNotEmpty())
            put("failed_members", JsonArray(failedIds.map(::JsonPrimitive)))
        })
    }
}

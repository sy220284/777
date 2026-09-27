package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.chat.ChatCharacterState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Resolve the durable event that originally carried one user message.
 *
 * Historical edit uses the event sequence as the rollback boundary so old future state cannot
 * leak into the replacement timeline even when timestamps are equal or reordered.
 */
internal fun sourceEventSequenceForMessage(
    events: Sequence<LocalSessionEventLog.Event>,
    messageId: String,
): Long? = events
    .filter { event ->
        event.type == "user/message" || event.type == LOCAL_AGENT_INBOX_EVENT_TYPE
    }
    .filter { event ->
        decodeTranscriptMessages(event.data)
            .orEmpty()
            .any { message -> message.id == messageId }
    }
    .maxOfOrNull(LocalSessionEventLog.Event::sequence)

/** Recover the newest single-chat state strictly before a timeline rewrite boundary. */
internal fun restoreChatStateBefore(
    events: Sequence<LocalSessionEventLog.Event>,
    json: Json,
    sequenceExclusive: Long?,
    createdAtExclusive: Long,
): ChatCharacterState? = events
    .filter { event ->
        if (sequenceExclusive != null) {
            event.sequence < sequenceExclusive
        } else {
            event.createdAt < createdAtExclusive
        }
    }
    .filter { event ->
        event.type == "chat/post-turn" &&
            event.data["status"]?.jsonPrimitive?.contentOrNull == "updated"
    }
    .sortedBy(LocalSessionEventLog.Event::sequence)
    .mapNotNull { event ->
        val encoded = event.data["state"] as? JsonObject
        if (encoded != null) {
            runCatching {
                json.decodeFromJsonElement(ChatCharacterState.serializer(), encoded)
            }.getOrNull()
        } else {
            // Compatibility with old post-turn events that only persisted these two fields.
            val mood = event.data["mood"]?.jsonPrimitive?.contentOrNull
            val relationship = event.data["relationship_state"]?.jsonPrimitive?.contentOrNull
            if (mood == null && relationship == null) {
                null
            } else {
                ChatCharacterState(
                    mood = mood ?: "自然",
                    relationshipState = relationship ?: "熟悉中",
                    updatedAt = event.createdAt,
                )
            }
        }
    }
    .lastOrNull()

/** Recover the newest group-chat state strictly before a timeline rewrite boundary. */
internal fun restoreGroupStateBefore(
    events: Sequence<LocalSessionEventLog.Event>,
    json: Json,
    sequenceExclusive: Long?,
    createdAtExclusive: Long,
): LocalGroupChatState? = events
    .filter { event ->
        if (sequenceExclusive != null) {
            event.sequence < sequenceExclusive
        } else {
            event.createdAt < createdAtExclusive
        }
    }
    .filter { event -> event.type == "group/state" }
    .sortedBy(LocalSessionEventLog.Event::sequence)
    .mapNotNull { event ->
        val encoded = event.data["state"] as? JsonObject ?: return@mapNotNull null
        runCatching {
            json.decodeFromJsonElement(LocalGroupChatState.serializer(), encoded)
        }.getOrNull()
    }
    .lastOrNull()

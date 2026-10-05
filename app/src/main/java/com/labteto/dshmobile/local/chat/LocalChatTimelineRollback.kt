package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.LocalHarnessState
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.agent.LOCAL_AGENT_INBOX_EVENT_TYPE
import com.labteto.dshmobile.local.session.LocalSessionEventLog
import com.labteto.dshmobile.local.session.decodeTranscriptMessages
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Persist the state that exists immediately before a user message is durably recorded.
 *
 * One baseline per user event makes historical edits deterministic even after relationship changes,
 * proactive messages, direction changes, or a non-empty saved-story/continuation start state.
 */
internal fun persistChatTimelineBaseline(
    eventLog: LocalSessionEventLog,
    json: Json,
    state: LocalHarnessState,
) {
    if (state.usageMode != LocalUsageMode.CHAT) return
    eventLog.append("chat/state-baseline", buildJsonObject {
        put("state", json.encodeToJsonElement(ChatCharacterState.serializer(), state.chat.chatState))
        if (state.chat.groupChat.enabled) {
            put("group_state", json.encodeToJsonElement(LocalGroupChatState.serializer(), state.chat.groupChat))
        }
    })
}

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
        event.type == "user/message" ||
            event.type == LOCAL_AGENT_INBOX_EVENT_TYPE ||
            (event.type == "chat/active-transcript" && isTimelineRewriteEditedMessage(event.data, messageId))
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
        event.type == "chat/state-baseline" ||
            (
                event.type == "chat/post-turn" &&
                    event.data["status"]?.jsonPrimitive?.contentOrNull == "updated"
            )
    }
    .sortedBy(LocalSessionEventLog.Event::sequence)
    .mapNotNull { event ->
        val encoded = event.data["state"] as? JsonObject
        if (encoded != null) {
            runCatching {
                json.decodeFromJsonElement(ChatCharacterState.serializer(), encoded)
                    .canonicalizeLegacyCharacterState()
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
    .filter { event -> event.type == "group/state" || event.type == "chat/state-baseline" }
    .sortedBy(LocalSessionEventLog.Event::sequence)
    .mapNotNull { event ->
        val key = if (event.type == "chat/state-baseline") "group_state" else "state"
        val encoded = event.data[key] as? JsonObject ?: return@mapNotNull null
        runCatching {
            json.decodeFromJsonElement(LocalGroupChatState.serializer(), encoded)
                .migrateLegacyConversationContext()
        }.getOrNull()
    }
    .lastOrNull()

/**
 * Paged variant for long-lived sessions. Historical edit is an interactive path, so it must not
 * materialize the complete event archive merely to locate one source message or rollback state.
 */
internal fun sourceEventSequenceForMessage(
    eventLog: LocalSessionEventLog,
    messageId: String,
): Long? {
    var beforeSequenceExclusive = Long.MAX_VALUE
    while (true) {
        val page = eventLog.pageBefore(
            sequenceExclusive = beforeSequenceExclusive,
            limit = CHAT_TIMELINE_SCAN_PAGE_SIZE,
        )
        if (page.isEmpty()) return null

        page.asReversed().firstOrNull { event ->
            (
                event.type == "user/message" ||
                    event.type == LOCAL_AGENT_INBOX_EVENT_TYPE ||
                    (event.type == "chat/active-transcript" && isTimelineRewriteEditedMessage(event.data, messageId))
                ) &&
                decodeTranscriptMessages(event.data)
                    .orEmpty()
                    .any { message -> message.id == messageId }
        }?.let { event -> return event.sequence }

        val oldestSequence = page.minOf(LocalSessionEventLog.Event::sequence)
        if (page.size < CHAT_TIMELINE_SCAN_PAGE_SIZE || oldestSequence <= 0L) return null
        beforeSequenceExclusive = oldestSequence
    }
}

internal fun restoreChatStateBefore(
    eventLog: LocalSessionEventLog,
    json: Json,
    sequenceExclusive: Long?,
    createdAtExclusive: Long,
): ChatCharacterState? = scanTimelineBackward(
    eventLog = eventLog,
    sequenceExclusive = sequenceExclusive,
    createdAtExclusive = createdAtExclusive,
) { event ->
    if (
        event.type != "chat/state-baseline" &&
        !(
            event.type == "chat/post-turn" &&
                event.data["status"]?.jsonPrimitive?.contentOrNull == "updated"
            )
    ) {
        return@scanTimelineBackward null
    }
    decodeChatStateEvent(event, json)
}

internal fun restoreGroupStateBefore(
    eventLog: LocalSessionEventLog,
    json: Json,
    sequenceExclusive: Long?,
    createdAtExclusive: Long,
): LocalGroupChatState? = scanTimelineBackward(
    eventLog = eventLog,
    sequenceExclusive = sequenceExclusive,
    createdAtExclusive = createdAtExclusive,
) { event ->
    if (event.type != "group/state" && event.type != "chat/state-baseline") {
        return@scanTimelineBackward null
    }
    val key = if (event.type == "chat/state-baseline") "group_state" else "state"
    val encoded = event.data[key] as? JsonObject ?: return@scanTimelineBackward null
    runCatching {
        json.decodeFromJsonElement(LocalGroupChatState.serializer(), encoded)
            .migrateLegacyConversationContext()
    }.getOrNull()
}

private fun decodeChatStateEvent(
    event: LocalSessionEventLog.Event,
    json: Json,
): ChatCharacterState? {
    val encoded = event.data["state"] as? JsonObject
    if (encoded != null) {
        return runCatching {
            json.decodeFromJsonElement(ChatCharacterState.serializer(), encoded)
                .canonicalizeLegacyCharacterState()
        }.getOrNull()
    }

    // Compatibility with old post-turn events that only persisted these two fields.
    val mood = event.data["mood"]?.jsonPrimitive?.contentOrNull
    val relationship = event.data["relationship_state"]?.jsonPrimitive?.contentOrNull
    if (mood == null && relationship == null) return null
    return ChatCharacterState(
        mood = mood ?: "自然",
        relationshipState = relationship ?: "熟悉中",
        updatedAt = event.createdAt,
    )
}

private inline fun <T> scanTimelineBackward(
    eventLog: LocalSessionEventLog,
    sequenceExclusive: Long?,
    createdAtExclusive: Long,
    crossinline decode: (LocalSessionEventLog.Event) -> T?,
): T? {
    var beforeSequenceExclusive = sequenceExclusive ?: Long.MAX_VALUE
    while (true) {
        val page = eventLog.pageBefore(
            sequenceExclusive = beforeSequenceExclusive,
            limit = CHAT_TIMELINE_SCAN_PAGE_SIZE,
        )
        if (page.isEmpty()) return null

        for (event in page.asReversed()) {
            if (sequenceExclusive == null && event.createdAt >= createdAtExclusive) continue
            decode(event)?.let { return it }
        }

        val oldestSequence = page.minOf(LocalSessionEventLog.Event::sequence)
        if (page.size < CHAT_TIMELINE_SCAN_PAGE_SIZE || oldestSequence <= 0L) return null
        beforeSequenceExclusive = oldestSequence
    }
}

private const val CHAT_TIMELINE_SCAN_PAGE_SIZE = 200


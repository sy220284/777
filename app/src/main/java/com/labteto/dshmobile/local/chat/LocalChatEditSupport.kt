package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.harness.session.ModelHistoryCheckpointCodec
import com.labteto.dshmobile.local.agent.LOCAL_AGENT_INBOX_EVENT_TYPE
import com.labteto.dshmobile.local.agent.decodeLocalAgentInboxPending
import com.labteto.dshmobile.local.chat.LocalChatBranchState
import com.labteto.dshmobile.local.chat.LocalChatUserEditResult
import com.labteto.dshmobile.local.chat.LocalTimelineRewriteProjectionInput
import com.labteto.dshmobile.local.chat.LocalTimelineRewriteState
import com.labteto.dshmobile.local.chat.appendTimelineRewriteCommit
import com.labteto.dshmobile.local.chat.editableChatUserText
import com.labteto.dshmobile.local.chat.encodeChatBranchStateEvent
import com.labteto.dshmobile.local.chat.groupTranscriptLine
import com.labteto.dshmobile.local.chat.recoverPendingTimelineRewriteProjection
import com.labteto.dshmobile.local.chat.sourceEventSequenceForMessage
import com.labteto.dshmobile.local.chat.timelineRewriteEditedModelMessage
import com.labteto.dshmobile.local.chat.withEditedChatUserText
import com.labteto.dshmobile.local.model.LocalModelHistoryBuffer
import com.labteto.dshmobile.local.model.replaceLocalUserModelMessageText
import com.labteto.dshmobile.local.runtime.LOCAL_TRANSCRIPT_RUNTIME_WINDOW_MESSAGES
import com.labteto.dshmobile.local.session.LocalHarnessMessage
import com.labteto.dshmobile.local.session.LocalHarnessSession
import com.labteto.dshmobile.local.session.LocalSessionEventLog
import com.labteto.dshmobile.local.session.LocalSessionTranscriptPager
import com.labteto.dshmobile.local.session.buildLocalTranscriptRuntimeIndex
import com.labteto.dshmobile.local.session.decodeTranscriptMessages
import com.labteto.dshmobile.local.session.encodeTranscriptMessages
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal fun editedChatUserModelMessage(
    eventLog: LocalSessionEventLog,
    originalMessageId: String,
    content: String,
): JsonObject {
    val original = findDurableUserModelMessage(
        eventLog = eventLog,
        messageId = originalMessageId,
    )
    return original?.let { replaceLocalUserModelMessageText(it, content) }
        ?: buildJsonObject {
            put("role", "user")
            put("content", content)
        }
}

internal fun buildEditedChatModelHistory(
    eventLog: LocalSessionEventLog,
    messages: List<LocalHarnessMessage>,
    groupMode: Boolean,
    editedMessageId: String,
    editedModelMessage: JsonObject,
    systemPrompt: String,
): List<JsonObject> {
    val durableUserMessages = loadDurableUserModelMessages(
        eventLog = eventLog,
        messageIds = messages.asSequence()
            .filter { message -> message.role == "user" && message.id != editedMessageId }
            .map(LocalHarnessMessage::id)
            .toSet(),
    )

    return buildList {
        add(buildJsonObject {
            put("role", "system")
            put("content", systemPrompt)
        })
        messages.forEach { message ->
            when (message.role) {
                "user" -> add(
                    if (message.id == editedMessageId) {
                        editedModelMessage
                    } else {
                        durableUserMessages[message.id] ?: buildJsonObject {
                            put("role", "user")
                            put("content", message.content)
                        }
                    },
                )
                "assistant" -> add(buildJsonObject {
                    put("role", "assistant")
                    put("content", if (groupMode) groupTranscriptLine(message) else message.content)
                })
            }
        }
    }
}

internal fun buildDurableChatModelHistory(
    eventLog: LocalSessionEventLog,
    messages: List<LocalHarnessMessage>,
    systemPrompt: String,
): List<JsonObject> {
    val durableUserMessages = loadDurableUserModelMessages(
        eventLog = eventLog,
        messageIds = messages.asSequence()
            .filter { message -> message.role == "user" }
            .map(LocalHarnessMessage::id)
            .toSet(),
    )
    return buildList {
        add(buildJsonObject {
            put("role", "system")
            put("content", systemPrompt)
        })
        messages.forEach { message ->
            when (message.role) {
                "user" -> add(
                    durableUserMessages[message.id] ?: buildJsonObject {
                        put("role", "user")
                        put("content", message.content)
                    },
                )
                "assistant" -> add(buildJsonObject {
                    put("role", "assistant")
                    put("content", message.content)
                })
            }
        }
    }
}

internal fun persistRewrittenChatTranscript(
    eventLog: LocalSessionEventLog,
    reason: String,
    activeTranscript: List<LocalHarnessMessage>,
): Long {
    val clearedBranches = LocalChatBranchState()
    eventLog.append(
        "chat/branch-state",
        JsonObject(
            encodeChatBranchStateEvent(clearedBranches) + ("reason" to JsonPrimitive(reason)),
        ),
    )
    return persistActiveChatTranscript(
        eventLog = eventLog,
        reason = reason,
        activeTranscript = activeTranscript,
    )
}

internal fun persistActiveChatTranscript(
    eventLog: LocalSessionEventLog,
    reason: String,
    activeTranscript: List<LocalHarnessMessage>,
): Long = eventLog.append("chat/active-transcript", buildJsonObject {
    put("reason", reason)
    put("transcript", encodeTranscriptMessages(activeTranscript))
}).sequence

private fun findDurableUserModelMessage(
    eventLog: LocalSessionEventLog,
    messageId: String,
): JsonObject? {
    var beforeSequenceExclusive = Long.MAX_VALUE
    while (true) {
        val page = eventLog.pageBefore(
            sequenceExclusive = beforeSequenceExclusive,
            limit = CHAT_EVENT_SCAN_PAGE_SIZE,
        )
        if (page.isEmpty()) return null

        page.asReversed().forEach { event ->
            val containsTarget = decodeTranscriptMessages(event.data)
                .orEmpty()
                .any { message -> message.id == messageId }
            if (!containsTarget) return@forEach

            when (event.type) {
                "user/message" -> {
                    (event.data["model_message"] as? JsonObject)?.let { return it }
                }
                "chat/active-transcript" -> {
                    timelineRewriteEditedModelMessage(event.data, messageId)?.let { return it }
                }
                LOCAL_AGENT_INBOX_EVENT_TYPE -> {
                    decodeLocalAgentInboxPending(event.data)
                        ?.firstOrNull { input -> input.id == messageId }
                        ?.modelMessage
                        ?.let { return it }
                }
            }
        }

        val oldestSequence = page.minOf(LocalSessionEventLog.Event::sequence)
        if (page.size < CHAT_EVENT_SCAN_PAGE_SIZE || oldestSequence <= 0L) return null
        beforeSequenceExclusive = oldestSequence
    }
}

private fun loadDurableUserModelMessages(
    eventLog: LocalSessionEventLog,
    messageIds: Set<String>,
): Map<String, JsonObject> {
    if (messageIds.isEmpty()) return emptyMap()
    val remaining = messageIds.toMutableSet()
    val result = linkedMapOf<String, JsonObject>()
    var beforeSequenceExclusive = Long.MAX_VALUE

    while (remaining.isNotEmpty()) {
        val page = eventLog.pageBefore(
            sequenceExclusive = beforeSequenceExclusive,
            limit = CHAT_EVENT_SCAN_PAGE_SIZE,
        )
        if (page.isEmpty()) break

        page.asReversed().forEach { event ->
            val transcriptUsers = decodeTranscriptMessages(event.data)
                .orEmpty()
                .asSequence()
                .filter { message -> message.role == "user" && message.id in remaining }
                .toList()
            if (transcriptUsers.isEmpty()) return@forEach

            when (event.type) {
                "user/message" -> {
                    val structured = event.data["model_message"] as? JsonObject ?: return@forEach
                    transcriptUsers.forEach { message ->
                        result[message.id] = structured
                        remaining.remove(message.id)
                    }
                }
                "chat/active-transcript" -> {
                    val editedId = (event.data["edited_message_id"] as? JsonPrimitive)?.content
                        ?.takeIf(remaining::contains)
                        ?: return@forEach
                    val structured = timelineRewriteEditedModelMessage(event.data, editedId)
                        ?: return@forEach
                    result[editedId] = structured
                    remaining.remove(editedId)
                }
                LOCAL_AGENT_INBOX_EVENT_TYPE -> {
                    val queuedById = decodeLocalAgentInboxPending(event.data)
                        .orEmpty()
                        .associateBy { input -> input.id }
                    transcriptUsers.forEach userLoop@ { message ->
                        val structured = queuedById[message.id]?.modelMessage ?: return@userLoop
                        result[message.id] = structured
                        remaining.remove(message.id)
                    }
                }
            }
        }
        if (remaining.isEmpty()) break

        val oldestSequence = page.minOf(LocalSessionEventLog.Event::sequence)
        if (page.size < CHAT_EVENT_SCAN_PAGE_SIZE || oldestSequence <= 0L) break
        beforeSequenceExclusive = oldestSequence
    }
    return result
}

private const val CHAT_EVENT_SCAN_PAGE_SIZE = 200


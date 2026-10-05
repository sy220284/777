package com.labteto.dshmobile.local

import com.labteto.dshmobile.harness.session.ModelHistoryCheckpointCodec
import com.labteto.dshmobile.local.agent.LOCAL_AGENT_INBOX_EVENT_TYPE
import com.labteto.dshmobile.local.agent.decodeLocalAgentInboxPending
import com.labteto.dshmobile.local.chat.ChatPersonaGalleryStore
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
import com.labteto.dshmobile.local.memory.MemoryStore
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal fun editAndResendWorkUserMessage(
    messageId: String,
    requestedText: String,
    eventLog: LocalSessionEventLog,
    memoryStore: MemoryStore,
    galleryStore: ChatPersonaGalleryStore,
    modelHistory: LocalModelHistoryBuffer,
    modelHistoryCheckpointCodec: ModelHistoryCheckpointCodec,
    stateFlow: MutableStateFlow<LocalHarnessState>,
    updateContextMetrics: () -> Unit,
    updateTranscriptProjectionCursor: (Long) -> Unit,
    checkpointModelHistory: (String) -> Unit,
    persist: () -> Unit,
    newUserMessage: (String) -> LocalHarnessMessage,
    startTurn: (String, String, String) -> Unit,
): LocalChatUserEditResult {
    val state = stateFlow.value
    val activeTranscript = LocalSessionTranscriptPager(eventLog).all()
    val originalIndex = activeTranscript.indexOfFirst { message -> message.id == messageId }
    if (originalIndex < 0) return LocalChatUserEditResult.MESSAGE_MISSING
    val original = activeTranscript[originalIndex]
    if (original.role != "user") return LocalChatUserEditResult.MESSAGE_MISSING

    val content = withEditedChatUserText(original, requestedText)
    if (content.isBlank()) return LocalChatUserEditResult.EMPTY
    if (editableChatUserText(original).trim() == requestedText) return LocalChatUserEditResult.UNCHANGED

    val sourceSequence = sourceEventSequenceForMessage(eventLog, messageId)
        ?: return LocalChatUserEditResult.MESSAGE_MISSING
    val eventsBeforeEdit = eventLog.snapshot().filter { event -> event.sequence < sourceSequence }
    val restoredHistory = restoreLocalModelHistory(
        events = eventsBeforeEdit,
        legacyFallback = emptyList(),
        codec = modelHistoryCheckpointCodec,
    ).messages
    val restoredControls = projectSessionControlTail(
        snapshot = LocalHarnessSession(id = state.sessionId),
        events = eventsBeforeEdit,
        sequenceExclusive = -1L,
    )
    val retainedPrefix = activeTranscript.take(originalIndex)
    val discarded = activeTranscript.drop(originalIndex)
    val editedModelMessage = editedChatUserModelMessage(eventLog, messageId, content)
    val edited = newUserMessage(content)
    val rewritten = retainedPrefix + edited
    val rewrittenHistory = restoredHistory + editedModelMessage
    val rewrite = appendTimelineRewriteCommit(
        eventLog = eventLog,
        reason = "work-user-edited",
        activeTranscript = rewritten,
        modelHistory = rewrittenHistory,
        state = LocalTimelineRewriteState(
            plan = restoredControls.plan,
            todos = restoredControls.todos,
            goal = restoredControls.goal,
            planMode = restoredControls.planMode,
            chatState = state.chat.chatState,
            chatContext = state.chat.chatContext,
            chatBranches = LocalChatBranchState(),
            groupChat = state.chat.groupChat,
        ),
        projection = LocalTimelineRewriteProjectionInput(
            sourceSessionId = state.sessionId,
            createdAtInclusive = original.createdAt,
            discardedMessageIds = discarded.map(LocalHarnessMessage::id),
        ),
        editedMessageId = edited.id,
        editedModelMessage = editedModelMessage,
    )
    updateTranscriptProjectionCursor(rewrite.sequence)

    modelHistory.reset(rewrittenHistory)
    updateContextMetrics()
    stateFlow.update { current ->
        current.copy(
            messages = rewritten.takeLast(LOCAL_TRANSCRIPT_RUNTIME_WINDOW_MESSAGES),
            transcriptIndex = buildLocalTranscriptRuntimeIndex(rewritten),
            work = current.work.copy(
                plan = restoredControls.plan,
                todos = restoredControls.todos,
                goal = restoredControls.goal,
                planMode = restoredControls.planMode,
            ),
            error = null,
        )
    }
    recoverPendingTimelineRewriteProjection(eventLog, memoryStore, galleryStore)
    checkpointModelHistory("work/user-edit-commit")
    persist()
    startTurn(content, requestedText, edited.id)
    return LocalChatUserEditResult.SENT
}

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


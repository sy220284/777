package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.session.LocalHarnessMessage
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

private const val CHAT_BRANCH_ROOT = "__chat_root__"
private const val LOCAL_IMPORTED_ATTACHMENT_MARKER = "本次附件已导入本机工作区："

enum class LocalChatUserEditResult {
    SENT, BUSY, UNAVAILABLE, MESSAGE_MISSING, EMPTY, UNCHANGED,
}

@Serializable
data class LocalChatBranchNode(
    val message: LocalHarnessMessage,
    val parentId: String? = null,
    val chatStateAfter: ChatCharacterState? = null,
    val chatContextAfter: ChatContextState? = null,
    val replySuggestionsAfter: List<ChatReplySuggestion> = emptyList(),
)

@Serializable
data class LocalChatBranchState(
    val nodes: List<LocalChatBranchNode> = emptyList(),
    val selectedChildByParent: Map<String, String> = emptyMap(),
)

internal fun LocalChatBranchState.canonicalizeLegacyChatBranchState(): LocalChatBranchState =
    copy(
        nodes = nodes.map { node ->
            val legacyState = node.chatStateAfter ?: return@map node
            val migratedContext = node.chatContextAfter
                ?.withLegacyFallback(legacyState)
                ?: ChatContextState().withLegacyFallback(legacyState).takeIf { it.hasUsefulFacts() }
            node.copy(
                chatStateAfter = legacyState
                    .canonicalizeLegacyCharacterState()
                    .withoutLegacyConversationContext(),
                chatContextAfter = migratedContext,
            )
        },
    )

data class LocalChatBranchInfo(
    val index: Int,
    val count: Int,
) {
    val hasPrevious: Boolean get() = index > 0
    val hasNext: Boolean get() = index + 1 < count
}

private val chatBranchJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

internal fun chatBranchingEligible(messages: List<LocalHarnessMessage>): Boolean {
    if (messages.any { message ->
            message.role !in setOf("user", "assistant", "system")
        }) return false
    val dialogue = messages.filter { it.role == "user" || it.role == "assistant" }
    if (dialogue.isEmpty()) return true
    val turnDialogue = dialogue.filterNot { it.role == "assistant" && it.proactive }
    if (turnDialogue.isEmpty()) return true
    if (turnDialogue.first().role != "user") return false
    // Group chat may legitimately produce several assistant messages for one user turn, while
    // queued/steering input can legitimately leave several user messages next to each other.
    // Either shape remains valid for branch navigation and historical-edit eligibility once idle.
    return true
}

internal fun chatMessageHasAttachmentContext(message: LocalHarnessMessage): Boolean =
    message.role == "user" && LOCAL_IMPORTED_ATTACHMENT_MARKER in message.content

internal fun editableChatUserText(message: LocalHarnessMessage): String {
    if (message.role != "user") return message.content
    val markerIndex = message.content.indexOf(LOCAL_IMPORTED_ATTACHMENT_MARKER)
    if (markerIndex < 0) return message.content
    return message.content.substring(0, markerIndex).trimEnd()
}

internal fun withEditedChatUserText(
    message: LocalHarnessMessage,
    replacement: String,
): String {
    val clean = replacement.trim()
    if (message.role != "user") return clean

    val markerIndex = message.content.indexOf(LOCAL_IMPORTED_ATTACHMENT_MARKER)
    if (markerIndex < 0) return clean
    val attachmentContext = message.content.substring(markerIndex).trim()
    return buildString {
        if (clean.isNotEmpty()) {
            append(clean)
            append("\n\n")
        }
        append(attachmentContext)
    }
}

/**
 * Rewrites the active conversation from one historical user turn.
 *
 * Everything from the original turn onward is discarded from the active transcript. The edited
 * user message becomes the new tail; the next generated assistant reply continues from there.
 */
internal fun rewriteChatTranscriptFromUserEdit(
    activeMessages: List<LocalHarnessMessage>,
    originalMessageId: String,
    editedMessage: LocalHarnessMessage,
): List<LocalHarnessMessage>? {
    if (editedMessage.role != "user") return null
    val index = activeMessages.indexOfFirst { message -> message.id == originalMessageId }
    if (index < 0 || activeMessages[index].role != "user") return null
    return buildList(index + 1) {
        addAll(activeMessages.subList(0, index))
        add(editedMessage)
    }
}

/** A paged, visible message may precede the hot window or a partially restored branch graph. */
internal fun activeTranscriptForUserEdit(
    messageId: String,
    activeBranch: List<LocalHarnessMessage>,
    hotMessages: List<LocalHarnessMessage>,
    loadDurableTranscript: () -> List<LocalHarnessMessage>,
): List<LocalHarnessMessage> {
    val branchContainsTarget = activeBranch.any { it.id == messageId }
    val hotContainsTarget = hotMessages.any { it.id == messageId }

    // Never infer that a bounded runtime window is the whole conversation from the runtime count.
    // Legacy migrations and interrupted projection updates can leave that count stale. The durable
    // projection supplies the prefix; a currently visible/selected tail remains authoritative.
    val durable = loadDurableTranscript()
    return when {
        branchContainsTarget -> mergeDurableTranscriptWithLiveTail(durable, activeBranch)
        hotContainsTarget -> mergeDurableTranscriptWithLiveTail(durable, hotMessages)
        else -> durable
    }
}

private fun mergeDurableTranscriptWithLiveTail(
    durable: List<LocalHarnessMessage>,
    liveTail: List<LocalHarnessMessage>,
): List<LocalHarnessMessage> {
    if (durable.isEmpty()) return liveTail
    if (liveTail.isEmpty()) return durable

    val liveIds = liveTail.mapTo(hashSetOf(), LocalHarnessMessage::id)
    val firstOverlap = durable.indexOfFirst { message -> message.id in liveIds }
    val firstLiveCreatedAt = liveTail.first().createdAt
    val durablePrefix = if (firstOverlap >= 0) {
        durable.take(firstOverlap)
    } else {
        // Different snapshots can carry the same visible turn under regenerated ids. In that case
        // time is only a fallback boundary: keep facts strictly before the live tail, never a stale
        // durable "future" that would survive the destructive edit.
        durable.takeWhile { message -> message.createdAt < firstLiveCreatedAt }
    }
    val seen = hashSetOf<String>()
    return buildList(durablePrefix.size + liveTail.size) {
        durablePrefix.forEach { message ->
            if (seen.add(message.id)) add(message)
        }
        liveTail.forEach { message ->
            if (seen.add(message.id)) add(message)
        }
    }
}

internal fun replayHardChatContextFromTranscript(
    messages: List<LocalHarnessMessage>,
    generation: Long,
): ChatContextState {
    var context = ChatContextState(generation = generation)
    val pendingUsers = mutableListOf<String>()
    var syntheticSequence = 1L
    messages.forEach { message ->
        when (message.role) {
            "user" -> if (message.content.isNotBlank()) pendingUsers += message.content
            "assistant" -> {
                context = context.applySceneTurn(
                    userMessage = pendingUsers.joinToString("\n"),
                    assistantMessage = message.content,
                    sequence = syntheticSequence++,
                )
                pendingUsers.clear()
            }
        }
    }
    return context.copy(
        continuity = ChatContinuityState(),
        pendingTurns = emptyList(),
        processedThroughSequence = 0L,
        generation = generation,
    ).normalized()
}

internal fun syncChatBranchState(
    current: LocalChatBranchState,
    activeMessages: List<LocalHarnessMessage>,
    chatState: ChatCharacterState,
    replySuggestions: List<ChatReplySuggestion>,
    chatContext: ChatContextState = ChatContextState(),
): LocalChatBranchState {
    val dialogue = activeMessages.filter { it.role == "user" || it.role == "assistant" }
    if (dialogue.isEmpty()) return if (current.nodes.isEmpty()) current else current

    var state = current
    var parentId: String? = null
    dialogue.forEachIndexed { index, message ->
        val existing = state.nodes.firstOrNull { it.message.id == message.id }
        val node = if (existing == null) {
            LocalChatBranchNode(
                message = message,
                parentId = parentId,
                chatStateAfter = if (index == dialogue.lastIndex) chatState else null,
                chatContextAfter = if (index == dialogue.lastIndex) chatContext else null,
                replySuggestionsAfter = if (index == dialogue.lastIndex) replySuggestions else emptyList(),
            )
        } else {
            existing.copy(
                message = message,
                parentId = existing.parentId ?: parentId,
                chatStateAfter = if (index == dialogue.lastIndex && existing.chatStateAfter == null) {
                    chatState
                } else {
                    existing.chatStateAfter
                },
                chatContextAfter = if (index == dialogue.lastIndex && existing.chatContextAfter == null) {
                    chatContext
                } else {
                    existing.chatContextAfter
                },
                replySuggestionsAfter = if (
                    index == dialogue.lastIndex &&
                    existing.replySuggestionsAfter.isEmpty()
                ) {
                    replySuggestions
                } else {
                    existing.replySuggestionsAfter
                },
            )
        }
        state = upsertChatBranchNode(state, node, select = true)
        parentId = message.id
    }
    return state
}

/**
 * Keep ordinary linear chats out of the branch graph.
 *
 * A branch graph is only useful after the user actually creates an alternative. Until then the
 * active transcript is already the source of truth, so mirroring every message here only doubles
 * long-chat memory and snapshot size.
 */
internal fun syncMaterializedChatBranchState(
    current: LocalChatBranchState,
    activeMessages: List<LocalHarnessMessage>,
    chatState: ChatCharacterState,
    replySuggestions: List<ChatReplySuggestion>,
    chatContext: ChatContextState = ChatContextState(),
): LocalChatBranchState =
    if (current.nodes.isEmpty()) {
        current
    } else {
        syncChatBranchState(current, activeMessages, chatState, replySuggestions, chatContext)
    }

/**
 * Drop legacy linear-only branch graphs on load, while preserving real user-created alternatives.
 */
internal fun restoreMaterializedChatBranchState(
    current: LocalChatBranchState,
    activeMessages: List<LocalHarnessMessage>,
    chatState: ChatCharacterState,
    replySuggestions: List<ChatReplySuggestion>,
): LocalChatBranchState =
    if (!hasChatBranchAlternatives(current)) {
        LocalChatBranchState()
    } else {
        // Real alternatives are already a durable branch graph. Re-syncing from a bounded hot
        // transcript would re-parent the first visible node to the synthetic root.
        current
    }

internal fun appendMaterializedChatBranchMessage(
    current: LocalChatBranchState,
    activeMessages: List<LocalHarnessMessage>,
    message: LocalHarnessMessage,
    parentId: String?,
    chatState: ChatCharacterState,
    replySuggestions: List<ChatReplySuggestion> = emptyList(),
    chatContext: ChatContextState = ChatContextState(),
): LocalChatBranchState {
    if (current.nodes.isEmpty()) return current
    return upsertChatBranchNode(
        state = current,
        node = LocalChatBranchNode(
            message = message,
            parentId = parentId,
            chatStateAfter = chatState,
            chatContextAfter = chatContext,
            replySuggestionsAfter = replySuggestions,
        ),
        select = true,
    )
}

internal fun upsertChatBranchNode(
    state: LocalChatBranchState,
    node: LocalChatBranchNode,
    select: Boolean,
): LocalChatBranchState {
    val nodes = state.nodes.toMutableList()
    val existingIndex = nodes.indexOfFirst { it.message.id == node.message.id }
    if (existingIndex >= 0) nodes[existingIndex] = node else nodes += node

    val selected = state.selectedChildByParent.toMutableMap()
    if (select) selected[branchParentKey(node.parentId)] = node.message.id
    return state.copy(nodes = nodes, selectedChildByParent = selected)
}

internal fun updateChatBranchNodeSnapshot(
    state: LocalChatBranchState,
    messageId: String,
    chatState: ChatCharacterState,
    replySuggestions: List<ChatReplySuggestion>,
    chatContext: ChatContextState = ChatContextState(),
): LocalChatBranchState {
    val index = state.nodes.indexOfFirst { it.message.id == messageId }
    if (index < 0) return state
    val nodes = state.nodes.toMutableList()
    nodes[index] = nodes[index].copy(
        chatStateAfter = chatState,
        chatContextAfter = chatContext,
        replySuggestionsAfter = replySuggestions,
    )
    return state.copy(nodes = nodes)
}

internal fun activeChatBranchMessages(state: LocalChatBranchState): List<LocalHarnessMessage> {
    if (state.nodes.isEmpty()) return emptyList()
    val byId = state.nodes.associateBy { it.message.id }
    val result = mutableListOf<LocalHarnessMessage>()
    val visited = hashSetOf<String>()
    var parentKey = CHAT_BRANCH_ROOT

    while (result.size <= state.nodes.size) {
        val selectedId = state.selectedChildByParent[parentKey] ?: break
        if (!visited.add(selectedId)) break
        val node = byId[selectedId] ?: break
        result += node.message
        parentKey = node.message.id
    }
    return result
}

internal fun hasChatBranchAlternatives(state: LocalChatBranchState): Boolean =
    state.nodes.groupBy { branchParentKey(it.parentId) }
        .values
        .any { siblings -> siblings.groupBy { it.message.role }.values.any { it.size > 1 } }

internal fun chatBranchInfo(
    state: LocalChatBranchState,
    messageId: String,
): LocalChatBranchInfo? {
    val node = state.nodes.firstOrNull { it.message.id == messageId } ?: return null
    val siblings = state.nodes
        .filter { it.parentId == node.parentId && it.message.role == node.message.role }
    if (siblings.size <= 1) return null
    val index = siblings.indexOfFirst { it.message.id == messageId }
    if (index < 0) return null
    return LocalChatBranchInfo(index = index, count = siblings.size)
}

internal fun selectChatBranchVariant(
    state: LocalChatBranchState,
    messageId: String,
    targetIndex: Int,
): LocalChatBranchState? {
    val node = state.nodes.firstOrNull { it.message.id == messageId } ?: return null
    val siblings = state.nodes
        .filter { it.parentId == node.parentId && it.message.role == node.message.role }
    val target = siblings.getOrNull(targetIndex) ?: return null
    val selected = state.selectedChildByParent.toMutableMap()
    selected[branchParentKey(node.parentId)] = target.message.id
    return state.copy(selectedChildByParent = selected)
}

internal fun chatBranchParentState(
    state: LocalChatBranchState,
    messageId: String,
): ChatCharacterState? {
    val node = state.nodes.firstOrNull { it.message.id == messageId } ?: return null
    val parentId = node.parentId ?: return null
    return state.nodes.firstOrNull { it.message.id == parentId }?.chatStateAfter
}

internal fun chatBranchParentContext(
    state: LocalChatBranchState,
    messageId: String,
): ChatContextState? {
    val node = state.nodes.firstOrNull { it.message.id == messageId } ?: return null
    val parentId = node.parentId ?: return null
    return state.nodes.firstOrNull { it.message.id == parentId }?.chatContextAfter
}

internal fun chatBranchLastSnapshot(
    state: LocalChatBranchState,
): Pair<ChatCharacterState, List<ChatReplySuggestion>>? {
    val active = activeChatBranchMessages(state)
    val last = active.lastOrNull() ?: return null
    val node = state.nodes.firstOrNull { it.message.id == last.id } ?: return null
    val chatState = node.chatStateAfter ?: return null
    return chatState to node.replySuggestionsAfter
}

internal fun chatBranchLastContext(
    state: LocalChatBranchState,
): ChatContextState? {
    val active = activeChatBranchMessages(state)
    val last = active.lastOrNull() ?: return null
    return state.nodes.firstOrNull { it.message.id == last.id }?.chatContextAfter
}

internal fun encodeChatBranchStateEvent(state: LocalChatBranchState): JsonObject =
    buildJsonObject {
        put("state", chatBranchJson.encodeToJsonElement(LocalChatBranchState.serializer(), state))
    }

internal fun decodeChatBranchStateEvent(data: JsonObject): LocalChatBranchState? =
    runCatching {
        val encoded = data["state"]?.jsonObject ?: return@runCatching null
        chatBranchJson.decodeFromJsonElement(LocalChatBranchState.serializer(), encoded)
            .canonicalizeLegacyChatBranchState()
    }.getOrNull()

private fun branchParentKey(parentId: String?): String = parentId ?: CHAT_BRANCH_ROOT

package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.chat.ChatCharacterState
import com.labteto.dshmobile.local.chat.ChatReplySuggestion
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

private const val CHAT_BRANCH_ROOT = "__chat_root__"
private const val LOCAL_IMPORTED_ATTACHMENT_MARKER = "本次附件已导入本机工作区："

@Serializable
data class LocalChatBranchNode(
    val message: LocalHarnessMessage,
    val parentId: String? = null,
    val chatStateAfter: ChatCharacterState? = null,
    val replySuggestionsAfter: List<ChatReplySuggestion> = emptyList(),
)

@Serializable
data class LocalChatBranchState(
    val nodes: List<LocalChatBranchNode> = emptyList(),
    val selectedChildByParent: Map<String, String> = emptyMap(),
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
    if (turnDialogue.any(::chatMessageHasAttachmentContext)) return false
    return turnDialogue.zipWithNext().all { (left, right) -> left.role != right.role }
}

internal fun chatMessageHasAttachmentContext(message: LocalHarnessMessage): Boolean =
    message.role == "user" && LOCAL_IMPORTED_ATTACHMENT_MARKER in message.content

internal fun syncChatBranchState(
    current: LocalChatBranchState,
    activeMessages: List<LocalHarnessMessage>,
    chatState: ChatCharacterState,
    replySuggestions: List<ChatReplySuggestion>,
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
): LocalChatBranchState =
    if (current.nodes.isEmpty()) {
        current
    } else {
        syncChatBranchState(current, activeMessages, chatState, replySuggestions)
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
): LocalChatBranchState {
    if (current.nodes.isEmpty()) return current
    return upsertChatBranchNode(
        state = current,
        node = LocalChatBranchNode(
            message = message,
            parentId = parentId,
            chatStateAfter = chatState,
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
): LocalChatBranchState {
    val index = state.nodes.indexOfFirst { it.message.id == messageId }
    if (index < 0) return state
    val nodes = state.nodes.toMutableList()
    nodes[index] = nodes[index].copy(
        chatStateAfter = chatState,
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

internal fun chatBranchLastSnapshot(
    state: LocalChatBranchState,
): Pair<ChatCharacterState, List<ChatReplySuggestion>>? {
    val active = activeChatBranchMessages(state)
    val last = active.lastOrNull() ?: return null
    val node = state.nodes.firstOrNull { it.message.id == last.id } ?: return null
    val chatState = node.chatStateAfter ?: return null
    return chatState to node.replySuggestionsAfter
}

internal fun encodeChatBranchStateEvent(state: LocalChatBranchState): JsonObject =
    buildJsonObject {
        put("state", chatBranchJson.encodeToJsonElement(LocalChatBranchState.serializer(), state))
    }

internal fun decodeChatBranchStateEvent(data: JsonObject): LocalChatBranchState? =
    runCatching {
        val encoded = data["state"]?.jsonObject ?: return@runCatching null
        chatBranchJson.decodeFromJsonElement(LocalChatBranchState.serializer(), encoded)
    }.getOrNull()

private fun branchParentKey(parentId: String?): String = parentId ?: CHAT_BRANCH_ROOT

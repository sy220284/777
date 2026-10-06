package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.session.LocalSessionEventLog
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal const val LOCAL_CHAT_DOMAIN_STATE_EVENT_TYPE = "chat/domain-state"
private const val LOCAL_CHAT_DOMAIN_STATE_PAYLOAD_KEY = "state"

private val localChatDomainStateJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

@Serializable
internal data class LocalChatDurableState(
    val personaId: String,
    val galleryId: String? = null,
    val galleryStoryId: String? = null,
    val gallerySaveSuppressedThrough: Long = 0L,
    val chatState: ChatCharacterState = ChatCharacterState(),
    val chatContext: ChatContextState = ChatContextState(),
    val replySuggestions: List<ChatReplySuggestion> = emptyList(),
    val chatBranches: LocalChatBranchState = LocalChatBranchState(),
    val groupChat: LocalGroupChatState = LocalGroupChatState(),
    val handoffSummary: String? = null,
)

internal fun LocalChatProjectionState.toLocalChatDurableState(): LocalChatDurableState =
    LocalChatDurableState(
        personaId = chat.personaId,
        galleryId = chat.galleryId,
        galleryStoryId = chat.galleryStoryId,
        gallerySaveSuppressedThrough = chat.gallerySaveSuppressedThrough,
        chatState = chat.chatState,
        chatContext = chat.chatContext,
        replySuggestions = chat.replySuggestions,
        chatBranches = chat.chatBranches,
        groupChat = chat.groupChat,
        handoffSummary = handoffSummary,
    )

internal fun encodeChatDomainStateEvent(
    state: LocalChatDurableState,
    reason: String,
): JsonObject = buildJsonObject {
    put("reason", reason)
    put(
        LOCAL_CHAT_DOMAIN_STATE_PAYLOAD_KEY,
        localChatDomainStateJson.encodeToJsonElement(LocalChatDurableState.serializer(), state),
    )
}

internal fun decodeChatDomainStateEvent(data: JsonObject): LocalChatDurableState? {
    val encoded = data[LOCAL_CHAT_DOMAIN_STATE_PAYLOAD_KEY] ?: return null
    return runCatching {
        localChatDomainStateJson.decodeFromJsonElement(LocalChatDurableState.serializer(), encoded)
    }.getOrNull()
}

internal fun appendChatDomainStateCommit(
    eventLog: LocalSessionEventLog,
    state: LocalChatProjectionState,
    reason: String,
): LocalSessionEventLog.Event =
    eventLog.append(
        LOCAL_CHAT_DOMAIN_STATE_EVENT_TYPE,
        encodeChatDomainStateEvent(state.toLocalChatDurableState(), reason),
    )

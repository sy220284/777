package com.labteto.dshmobile.local.chat

import kotlinx.serialization.Serializable

@Serializable
enum class ChatDiarySourceMode {
    DIRECT,
    GROUP,
}

@Serializable
enum class ChatDiaryDisclosure {
    PRIVATE,
    SHAREABLE,
    PUBLIC,
}

@Serializable
data class ChatDiaryDelta(
    val event: String = "",
    val feeling: String = "",
    val innerThought: String = "",
    val relationshipMeaning: String = "",
    val unresolvedEcho: String = "",
    val importance: Int = 0,
    val disclosure: String = "SHAREABLE",
)

@Serializable
data class ChatDiarySourceRef(
    val sessionId: String,
    val userMessageId: String = "",
    val assistantMessageId: String = "",
)

@Serializable
data class ChatDiaryRevision(
    val event: String,
    val feeling: String = "",
    val innerThought: String = "",
    val relationshipMeaning: String = "",
    val unresolvedEcho: String = "",
    val importance: Int = 3,
    val disclosure: ChatDiaryDisclosure = ChatDiaryDisclosure.SHAREABLE,
    val sources: List<ChatDiarySourceRef> = emptyList(),
    val updatedAt: Long,
)

@Serializable
data class ChatDiaryEntry(
    val id: String,
    val subjectKey: String,
    val personaName: String,
    val event: String,
    val feeling: String = "",
    val innerThought: String = "",
    val relationshipMeaning: String = "",
    val unresolvedEcho: String = "",
    val importance: Int = 3,
    val sourceMode: ChatDiarySourceMode = ChatDiarySourceMode.DIRECT,
    val disclosure: ChatDiaryDisclosure = ChatDiaryDisclosure.SHAREABLE,
    val sources: List<ChatDiarySourceRef> = emptyList(),
    val revisions: List<ChatDiaryRevision> = emptyList(),
    val generation: Long = 0L,
    val active: Boolean = true,
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
internal data class ChatDiaryDocument(
    val formatVersion: Int = 2,
    val entries: List<ChatDiaryEntry> = emptyList(),
)

internal data class ChatDiaryWriteRequest(
    val subjectKey: String,
    val personaName: String,
    val delta: ChatDiaryDelta?,
    val turnSignificance: String,
    val sourceMode: ChatDiarySourceMode,
    val sourceSessionId: String,
    val sourceUserMessageIds: List<String>,
    val sourceAssistantMessageIds: List<String>,
    val evidenceText: String,
    val generation: Long,
)

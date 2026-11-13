package com.labteto.dshmobile.local.chat

import kotlinx.serialization.Serializable

internal const val MAX_CHAT_DIARY_ENTRIES = 4_000

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
    /** First-person private voice, never a verified fact or an instruction to the character. */
    val privateReflection: String = "",
    val importance: Int = 0,
    val disclosure: String = "SHAREABLE",
    /** Search-only concepts grounded in this event; never instructions for the character. */
    val recallAnchors: List<String> = emptyList(),
    /** Optional original character expression supporting the subjective fields. */
    val subjectiveEvidence: String = "",
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
    /** First-person private voice, never a verified fact or an instruction to the character. */
    val privateReflection: String = "",
    val importance: Int = 3,
    val disclosure: ChatDiaryDisclosure = ChatDiaryDisclosure.SHAREABLE,
    val sources: List<ChatDiarySourceRef> = emptyList(),
    val updatedAt: Long,
    val projectionId: String? = null,
    /** A user-authored revision supersedes the generated summary without rewriting its source. */
    val userCorrected: Boolean = false,
    val recallAnchors: List<String> = emptyList(),
    val subjectiveEvidence: String = "",
    val subjectiveInferred: Boolean = true,
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
    /** First-person private voice, never a verified fact or an instruction to the character. */
    val privateReflection: String = "",
    val importance: Int = 3,
    val sourceMode: ChatDiarySourceMode = ChatDiarySourceMode.DIRECT,
    val disclosure: ChatDiaryDisclosure = ChatDiaryDisclosure.SHAREABLE,
    val sources: List<ChatDiarySourceRef> = emptyList(),
    val revisions: List<ChatDiaryRevision> = emptyList(),
    val generation: Long = 0L,
    /** Newer active diary entry that replaced this entry's current meaning. Historical recall may still use it. */
    val supersededBy: String? = null,
    /** User correction/disable of the replacement does not revive this obsolete meaning.
     * Independent of the replacement row so pruning and persona transfer preserve the decision. */
    val supersessionRetained: Boolean = false,
    val active: Boolean = true,
    val createdAt: Long,
    val updatedAt: Long,
    val experienceId: String = "",
    val recallAnchors: List<String> = emptyList(),
    val subjectiveEvidence: String = "",
    val subjectiveInferred: Boolean = true,
)

@Serializable
internal data class ChatDiaryDocument(
    val formatVersion: Int = 4,
    val entries: List<ChatDiaryEntry> = emptyList(),
    /** Raw user-message sources explicitly invalidated by a later durable memory correction. */
    val invalidatedSourceRefs: List<ChatDiarySourceRef> = emptyList(),
)

@Serializable
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
    val projectionId: String? = null,
    val experienceId: String = "",
    val experienceSessionIds: List<String> = emptyList(),
    val characterEvidenceText: String = "",
)

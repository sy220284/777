package com.labteto.dshmobile.local.chat

import kotlinx.serialization.Serializable

@Serializable
data class UserChatPattern(
    val replyLength: String = "mixed",
    val directness: Int = 50,
    val playfulness: Int = 50,
    val initiative: Int = 50,
    val emojiStyle: String = "",
    val preferredTone: String = "",
    val observedTurns: Int = 0,
    val averageMessageChars: Int = 0,
    val updatedAt: Long = 0L,
)

@Serializable
data class ChatSceneState(
    val sceneTime: String = "",
    val location: String = "",
    val participants: List<String> = emptyList(),
    val positions: List<String> = emptyList(),
    val activeActions: List<String> = emptyList(),
    val keyObjects: List<String> = emptyList(),
    val currentEvent: String = "",
    val lastSceneChange: String = "",
)

@Serializable
enum class ChatContinuityFactKind {
    EVENT,
    DECISION,
    OPEN_THREAD,
}

@Serializable
data class ChatContinuityEvidence(
    val kind: ChatContinuityFactKind = ChatContinuityFactKind.EVENT,
    val text: String = "",
    val sourceSequence: Long = 0L,
    val sourceUserMessageId: String = "",
    val sourceAssistantMessageId: String = "",
    val evidence: String = "",
)

@Serializable
data class ChatContinuityState(
    val recentEvents: List<String> = emptyList(),
    val recurringEvents: List<String> = emptyList(),
    val decisions: List<String> = emptyList(),
    val unfinished: List<String> = emptyList(),
    /**
     * System-owned provenance for the active soft continuity facts above.
     * Old persisted states decode with an empty list and are gradually grounded by later turns.
     */
    val evidence: List<ChatContinuityEvidence> = emptyList(),
)


@Serializable
data class ChatCharacterState(
    /** Short-lived bodily condition that can naturally affect attention and reply length. */
    val physicalState: String = "",
    val mood: String = "自然",
    val relationshipState: String = "熟悉中",
    val currentFocus: String = "",
    /** Legacy decode-only field. Storage boundaries migrate it once, then clear it. */
    val recentImpression: String = "",
    /** Durable subjective impression; unlike transient mood/focus it does not expire by turn count. */
    val currentUserImpression: String = "",
    val lifeState: CharacterLifeState = CharacterLifeState(),
    val activeGoal: String = "",
    val currentAgenda: String = "",
    val internalConflict: String = "",
    val immediateConcern: String = "",
    val unresolvedThreads: List<String> = emptyList(),
    val initiative: Int = 50,
    val shareDesire: Int = 50,
    val dynamics: RelationshipDynamics = RelationshipDynamics(),
    val evolution: CharacterEvolutionState = CharacterEvolutionState(),
    val behaviorTuning: CharacterBehaviorTuning = CharacterBehaviorTuning(),
    val userPattern: UserChatPattern = UserChatPattern(),
    val scene: ChatSceneState = ChatSceneState(),
    val continuity: ChatContinuityState = ChatContinuityState(),
    val interactionIntent: String = ChatInteractionIntent.NORMAL.name,
    val interactionIntentStrength: Int = 0,
    val interactionIntensity: Int = 0,
    val recentActionTags: List<String> = emptyList(),
    val recentPoseTags: List<String> = emptyList(),
    val recentVerbalTags: List<String> = emptyList(),
    val recentAddressTerms: List<String> = emptyList(),
    val interactionCooldowns: Map<String, Int> = emptyMap(),
    /** Per-field age in completed turns. Used to expire short-lived roleplay state. */
    val transientAges: Map<String, Int> = emptyMap(),
    /** Open-thread age keyed by normalized thread text. */
    val unresolvedThreadAges: Map<String, Int> = emptyMap(),
    val updatedAt: Long = 0L,
)

internal fun ChatCharacterState.canonicalizeLegacyCharacterState(): ChatCharacterState {
    val migratedImpression = currentUserImpression.ifBlank { recentImpression }
    return if (recentImpression.isBlank() && migratedImpression == currentUserImpression) {
        this
    } else {
        copy(
            currentUserImpression = migratedImpression,
            recentImpression = "",
        )
    }
}

@Serializable
data class ChatReplySuggestion(
    val label: String,
    // Directly sendable user draft. Defaults keep older saved sessions decodable.
    val text: String = "",
    val style: String = "",
    val bold: Boolean = false,
)

@Serializable
data class ChatPostTurnPlan(
    val state: ChatCharacterState = ChatCharacterState(),
    val suggestions: List<ChatReplySuggestion> = emptyList(),
    val turnSignificance: String = "MINOR",
    val diaryDelta: ChatDiaryDelta? = null,
)
@Serializable
data class ChatReplySuggestionPlan(
    val suggestions: List<ChatReplySuggestion> = emptyList(),
)

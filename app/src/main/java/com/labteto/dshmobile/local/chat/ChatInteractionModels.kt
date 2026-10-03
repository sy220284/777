package com.labteto.dshmobile.local.chat

import kotlinx.serialization.Serializable

@Serializable
data class RelationshipEvidence(
    val text: String = "",
    val confidence: Int = 50,
    val source: String = "",
)

@Serializable
data class RelationshipDynamics(
    val stage: String = "FAMILIAR",
    val warmth: Int = 50,
    val trust: Int = 50,
    val reciprocity: Int = 50,
    val tension: Int = 10,
    val stability: Int = 50,
    val unresolvedConflict: String = "",
    val facts: List<RelationshipEvidence> = emptyList(),
    val hypotheses: List<RelationshipEvidence> = emptyList(),
    val unknowns: List<String> = emptyList(),
    val sharedMoments: List<String> = emptyList(),
    /** Shared objects, private jokes and small promises that make the relationship feel lived-in. */
    val sharedObjects: List<String> = emptyList(),
)

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
    val recentImpression: String = "",
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
    val narrativeDirection: ChatNarrativeDirection? = null,
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

@Serializable
data class ChatNarrativeDirection(
    val label: String,
    val guidance: String,
)

@Serializable
data class ChatReplySuggestion(
    val label: String,
    // Directly sendable user draft. Defaults keep older saved sessions decodable.
    val text: String = "",
    val style: String = "",
    val bold: Boolean = false,
    // Legacy story-direction fields are retained only for session compatibility.
    val direction: String = "",
    val impact: String = "",
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

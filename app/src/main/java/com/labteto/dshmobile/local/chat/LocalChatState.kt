package com.labteto.dshmobile.local.chat



/** Chat-owned runtime state. Durable Session storage remains a separate projection boundary. */
data class LocalChatState(
    val personaId: String = PersonaProfile.DEFAULT_PERSONA_ID,
    val galleryId: String? = null,
    val galleryStoryId: String? = null,
    val gallerySaveSuppressedThrough: Long = 0L,
    val chatPersona: PersonaProfile = PersonaProfile(),
    val chatState: ChatCharacterState = ChatCharacterState(),
    val chatContext: ChatContextState = ChatContextState(),
    val replySuggestions: List<ChatReplySuggestion> = emptyList(),
    val chatBranches: LocalChatBranchState = LocalChatBranchState(),
    val groupChat: LocalGroupChatState = LocalGroupChatState(),
    val groupActiveSpeakerName: String? = null,
    val personaCorrectionNotice: ChatPersonaCorrectionNotice? = null,
    val chatStyleGuardEnabled: Boolean = true,
    val chatStyleGuardCustomPhrases: List<String> = emptyList(),
    val styleGuardHits: List<String> = emptyList(),
)

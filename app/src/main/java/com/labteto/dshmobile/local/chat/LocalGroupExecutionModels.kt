package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.chat.ChatCharacterState
import com.labteto.dshmobile.local.chat.PersonaProfile

internal data class GroupReplyForStateUpdate(
    val member: LocalGroupChatMember,
    val persona: PersonaProfile,
    val content: String,
)

internal data class GroupStateRefreshBatch(
    val states: Map<String, ChatCharacterState>,
    val complete: Boolean,
)

internal data class GroupGeneratedReply(
    val member: LocalGroupChatMember,
    val persona: PersonaProfile,
    val content: String = "",
    val failure: Throwable? = null,
)

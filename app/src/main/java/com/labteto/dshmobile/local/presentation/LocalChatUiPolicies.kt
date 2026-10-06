package com.labteto.dshmobile.local.presentation

import com.labteto.dshmobile.local.chat.ChatCharacterState
import com.labteto.dshmobile.local.chat.LocalChatBranchInfo
import com.labteto.dshmobile.local.chat.LocalChatBranchState
import com.labteto.dshmobile.local.chat.MAX_GROUP_CHAT_MEMBERS as CHAT_MAX_GROUP_CHAT_MEMBERS
import com.labteto.dshmobile.local.chat.MAX_PERSONA_TRANSFER_BYTES as CHAT_MAX_PERSONA_TRANSFER_BYTES
import com.labteto.dshmobile.local.chat.MIN_GROUP_CHAT_MEMBERS as CHAT_MIN_GROUP_CHAT_MEMBERS
import com.labteto.dshmobile.local.chat.PERSONA_WORD_MIME as CHAT_PERSONA_WORD_MIME
import com.labteto.dshmobile.local.chat.PersonaGalleryEntry
import com.labteto.dshmobile.local.chat.PersonaPresetCatalog as ChatPersonaPresetCatalog
import com.labteto.dshmobile.local.chat.PersonaProfile
import com.labteto.dshmobile.local.chat.chatBranchInfo as featureChatBranchInfo
import com.labteto.dshmobile.local.chat.chatMessageHasAttachmentContext as featureChatMessageHasAttachmentContext
import com.labteto.dshmobile.local.chat.chatRelationshipSubjectKey as featureChatRelationshipSubjectKey
import com.labteto.dshmobile.local.chat.editableChatUserText as featureEditableChatUserText
import com.labteto.dshmobile.local.chat.findEstablishedGroupChatSession as featureFindEstablishedGroupChatSession
import com.labteto.dshmobile.local.chat.galleryEntryHasUnsavedChanges as featureGalleryEntryHasUnsavedChanges
import com.labteto.dshmobile.local.chat.galleryMessageArchiveKey as featureGalleryMessageArchiveKey
import com.labteto.dshmobile.local.chat.groupMessageVisibleContent as featureGroupMessageVisibleContent
import com.labteto.dshmobile.local.chat.isMeaningfulGalleryPersona as featureIsMeaningfulGalleryPersona
import com.labteto.dshmobile.local.chat.isUnboundChatPersona as featureIsUnboundChatPersona
import com.labteto.dshmobile.local.chat.samePersonaIdentity as featureSamePersonaIdentity
import com.labteto.dshmobile.local.chat.withoutLegacyConversationContext as featureWithoutLegacyConversationContext
import com.labteto.dshmobile.local.session.LocalHarnessMessage
import com.labteto.dshmobile.local.session.LocalSessionSummary

/**
 * Stable UI-facing Chat policy surface.
 *
 * UI may use Chat DTOs, but domain decisions and product constants stay behind this presentation
 * boundary so a top-level Feature helper cannot silently become an architecture bypass.
 */
internal val PersonaPresetCatalog get() = ChatPersonaPresetCatalog
internal const val MAX_PERSONA_TRANSFER_BYTES: Int = CHAT_MAX_PERSONA_TRANSFER_BYTES
internal const val PERSONA_WORD_MIME: String = CHAT_PERSONA_WORD_MIME
internal const val MAX_GROUP_CHAT_MEMBERS: Int = CHAT_MAX_GROUP_CHAT_MEMBERS
internal const val MIN_GROUP_CHAT_MEMBERS: Int = CHAT_MIN_GROUP_CHAT_MEMBERS

internal fun chatRelationshipSubjectKey(galleryId: String?, personaId: String?): String? =
    featureChatRelationshipSubjectKey(galleryId, personaId)

internal fun findEstablishedGroupChatSession(sessions: List<LocalSessionSummary>): LocalSessionSummary? =
    featureFindEstablishedGroupChatSession(sessions)

internal fun PersonaProfile.isUnboundChatPersona(): Boolean = featureIsUnboundChatPersona()

internal fun galleryMessageArchiveKey(message: LocalHarnessMessage): String =
    featureGalleryMessageArchiveKey(message)

internal fun chatMessageHasAttachmentContext(message: LocalHarnessMessage): Boolean =
    featureChatMessageHasAttachmentContext(message)

internal fun editableChatUserText(message: LocalHarnessMessage): String =
    featureEditableChatUserText(message)

internal fun chatBranchInfo(state: LocalChatBranchState, messageId: String): LocalChatBranchInfo? =
    featureChatBranchInfo(state, messageId)

internal fun groupMessageVisibleContent(message: LocalHarnessMessage): String =
    featureGroupMessageVisibleContent(message)

internal fun galleryEntryHasUnsavedChanges(
    entry: PersonaGalleryEntry,
    storyId: String?,
    persona: PersonaProfile,
    history: List<LocalHarnessMessage>,
    chatState: ChatCharacterState,
): Boolean = featureGalleryEntryHasUnsavedChanges(entry, storyId, persona, history, chatState)

internal fun isMeaningfulGalleryPersona(persona: PersonaProfile): Boolean =
    featureIsMeaningfulGalleryPersona(persona)

internal fun samePersonaIdentity(left: PersonaProfile, right: PersonaProfile): Boolean =
    featureSamePersonaIdentity(left, right)

internal fun ChatCharacterState.withoutLegacyConversationContext(): ChatCharacterState =
    featureWithoutLegacyConversationContext()

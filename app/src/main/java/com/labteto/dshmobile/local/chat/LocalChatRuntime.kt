package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.LocalChatMode
import com.labteto.dshmobile.local.LocalChatUserEditResult
import com.labteto.dshmobile.local.LocalHarnessEngine
import com.labteto.dshmobile.local.LocalImportedAttachment
import javax.inject.Inject
import javax.inject.Singleton
/** Chat/persona capability boundary for the local UI. */
@Singleton
class LocalChatRuntime @Inject constructor(
    private val engine: LocalHarnessEngine,
) {
    internal suspend fun configureChatPersona(profile: PersonaProfile): Result<Unit> = engine.configureChatPersona(profile)
    internal fun selectChatPersona(profile: PersonaProfile, galleryId: String? = null) =
        engine.selectChatPersona(profile, galleryId)
    internal fun bindChatGallery(galleryId: String, galleryStoryId: String?) =
        engine.bindChatGallery(galleryId, galleryStoryId)
    internal fun clearChatGalleryBinding(
        expectedGalleryId: String,
        expectedStoryId: String? = null,
        keepCharacter: Boolean = false,
    ) = engine.clearChatGalleryBinding(expectedGalleryId, expectedStoryId, keepCharacter)
    internal suspend fun syncDefaultChatPersona(profile: PersonaProfile): PersonaProfile =
        engine.syncDefaultChatPersona(profile)
    internal fun createGroupChatSession(entries: List<PersonaGalleryEntry>): Boolean = engine.createGroupChatSession(entries)
    internal fun createSingleChatSession() = engine.createSingleChatSession()
    internal fun switchChatMode(mode: LocalChatMode) = engine.switchChatMode(mode)
    internal fun configureGroupChatMembers(entries: List<PersonaGalleryEntry>): Boolean =
        engine.configureGroupChatMembers(entries)
    internal suspend fun setGroupChatAnnouncement(text: String): Result<Unit> = engine.setGroupChatAnnouncement(text)
    internal fun removeGroupChatMemberByGalleryId(galleryId: String) =
        engine.removeGroupChatMemberByGalleryId(galleryId)
    internal suspend fun generateReplySuggestions(): Boolean = engine.generateReplySuggestions()
    internal fun diaryEntries(subjectKey: String, limit: Int = MAX_CHAT_DIARY_ENTRIES) = engine.chatDiaryEntries(subjectKey, limit)
    internal fun diaryEntriesForTransfer(subjectKey: String) = engine.chatDiaryEntriesForTransfer(subjectKey)
    internal fun importDiaryEntriesForTransfer(subjectKey: String, personaName: String, entries: List<ChatDiaryEntry>): Int =
        engine.importChatDiaryEntriesForTransfer(subjectKey, personaName, entries)
    internal fun send(text: String, attachments: List<LocalImportedAttachment> = emptyList()): com.labteto.dshmobile.local.send.LocalSendResult = engine.send(text, attachments)
    internal fun editAndResendUserMessage(messageId: String, replacement: String): LocalChatUserEditResult =
        engine.editAndResendUserMessage(messageId, replacement)
    internal fun selectChatMessageVariant(messageId: String, targetIndex: Int): Boolean =
        engine.selectChatMessageVariant(messageId, targetIndex)
    internal fun regenerateReply(messageId: String): Boolean = engine.regenerateReply(messageId)
    internal fun undoChatPersonaCorrection(noticeId: Long, personaId: String, correction: String) =
        engine.undoChatPersonaCorrection(noticeId, personaId, correction)
}

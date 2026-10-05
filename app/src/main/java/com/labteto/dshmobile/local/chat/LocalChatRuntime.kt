package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.chat.LocalChatMode
import com.labteto.dshmobile.local.chat.LocalChatUserEditResult
import com.labteto.dshmobile.local.LocalHarnessEngine
import com.labteto.dshmobile.local.attachment.LocalImportedAttachment
import com.labteto.dshmobile.local.session.LocalSessionRuntime
import com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore
import com.labteto.dshmobile.local.runtime.LocalSessionStorageRuntime
import javax.inject.Inject
import javax.inject.Singleton
/** Chat/persona capability boundary for the local UI. */
@Singleton
class LocalChatRuntime @Inject internal constructor(
    private val engine: LocalHarnessEngine,
    private val persistence: LocalChatPersistence,
    private val sessionRuntime: LocalSessionRuntime,
    private val personaCorrections: LocalChatPersonaCorrectionCoordinator,
    private val behaviorTuning: LocalCharacterBehaviorTuningCoordinator,
    private val runtimeStateStore: LocalRuntimeStateStore,
    private val sessionStorage: LocalSessionStorageRuntime,
    private val personaCoordinator: LocalChatPersonaCoordinator,
    private val groupMembership: LocalGroupChatMembershipCoordinator,
) {
    internal suspend fun configureChatPersona(profile: PersonaProfile): Result<Unit> = behaviorTuning.configure(profile)
    internal fun selectChatPersona(profile: PersonaProfile, galleryId: String? = null) =
        personaCoordinator.select(profile, galleryId)
    internal fun bindChatGallery(galleryId: String, galleryStoryId: String?) =
        personaCoordinator.bindGallery(galleryId, galleryStoryId)
    internal fun clearChatGalleryBinding(
        expectedGalleryId: String,
        expectedStoryId: String? = null,
        keepCharacter: Boolean = false,
    ) = personaCoordinator.clearGalleryBinding(expectedGalleryId, expectedStoryId, keepCharacter)
    internal suspend fun syncDefaultChatPersona(profile: PersonaProfile): PersonaProfile =
        personaCoordinator.syncDefault(profile)
    internal fun createGroupChatSession(entries: List<PersonaGalleryEntry>): Boolean = sessionRuntime.createGroupChatSession(entries)
    internal fun createSingleChatSession() = sessionRuntime.createSingleChatSession()
    internal fun switchChatMode(mode: LocalChatMode) = sessionRuntime.switchChatMode(mode)
    internal fun configureGroupChatMembers(entries: List<PersonaGalleryEntry>): Boolean =
        groupMembership.configure(entries)
    internal suspend fun setGroupChatAnnouncement(text: String): Result<Unit> {
        val sessionId = runtimeStateStore.currentSessionId
        return saveGroupChatAnnouncement(
            state = runtimeStateStore.mutableState,
            text = text,
            sessionId = sessionId,
            transcriptProjectedThroughSequence =
                runtimeStateStore.foregroundTranscriptProjectionCursor,
            sessionCoordinator = sessionStorage.coordinator,
            eventLog = sessionStorage.eventLogs.get(sessionId),
        )
    }
    internal fun removeGroupChatMemberByGalleryId(galleryId: String) =
        groupMembership.remove(galleryId)
    internal suspend fun generateReplySuggestions(): Boolean = engine.generateReplySuggestions()
    internal fun diaryEntries(subjectKey: String, limit: Int = MAX_CHAT_DIARY_ENTRIES) = persistence.diaryStore.listActive(subjectKey, limit)
    internal fun diaryEntriesForTransfer(subjectKey: String) = persistence.diaryStore.listForTransfer(subjectKey)
    internal fun <T> importDiaryEntriesForTransfer(subjectKey: String, personaName: String, entries: List<ChatDiaryEntry>, commit: () -> T): T = persistence.diaryStore.importForTransfer(subjectKey, personaName, entries, commit)
    internal fun send(text: String, attachments: List<LocalImportedAttachment> = emptyList()): com.labteto.dshmobile.local.send.LocalSendResult = engine.send(text, attachments)
    internal fun editAndResendUserMessage(messageId: String, replacement: String): LocalChatUserEditResult =
        engine.editAndResendUserMessage(messageId, replacement)
    internal fun selectChatMessageVariant(messageId: String, targetIndex: Int): Boolean =
        engine.selectChatMessageVariant(messageId, targetIndex)
    internal fun regenerateReply(messageId: String): Boolean = engine.regenerateReply(messageId)
    internal fun stop() = runtimeStateStore.performVisibleOperation("停止聊天时保存失败") {
        engine.stopForegroundRun()
    }
    internal suspend fun undoChatPersonaCorrection(
        noticeId: Long,
        personaId: String,
        correction: String,
    ) = personaCorrections.undo(noticeId, personaId, correction)
}

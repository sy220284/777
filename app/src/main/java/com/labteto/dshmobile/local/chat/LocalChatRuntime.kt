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
    private val chatState: LocalChatStatePort,
    private val runtimeStateStore: LocalRuntimeStateStore,
    private val sessionStorage: LocalSessionStorageRuntime,
    private val personaCoordinator: LocalChatPersonaCoordinator,
    private val groupMembership: LocalGroupChatMembershipCoordinator,
    private val branchCoordinator: LocalChatBranchCoordinator,
    private val replySuggestions: LocalReplySuggestionCoordinator,
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
    internal fun createGroupChatSession(entries: List<PersonaGalleryEntry>): Boolean {
        val selected = entries
            .distinctBy(PersonaGalleryEntry::id)
            .take(MAX_GROUP_CHAT_MEMBERS)
        if (selected.size !in MIN_GROUP_CHAT_MEMBERS..MAX_GROUP_CHAT_MEMBERS) return false
        val snapshot = chatState.value
        if (snapshot.loading || snapshot.kernel.running) return false
        return sessionRuntime.createSession(
            mode = com.labteto.dshmobile.local.session.LocalConversationMode.INDEPENDENT,
            usageMode = com.labteto.dshmobile.local.LocalUsageMode.CHAT,
            domainSpec = LocalChatSessionCreateSpec(
                chatMode = LocalChatMode.GROUP,
                groupEntries = selected,
            ),
        )
    }

    internal fun createSingleChatSession(): Boolean = sessionRuntime.createSession(
        mode = com.labteto.dshmobile.local.session.LocalConversationMode.INDEPENDENT,
        usageMode = com.labteto.dshmobile.local.LocalUsageMode.CHAT,
        domainSpec = LocalChatSessionCreateSpec(chatMode = LocalChatMode.SINGLE),
    )

    internal fun startSessionFromGallery(
        entry: PersonaGalleryEntry,
        storyId: String?,
        freshStory: Boolean,
    ): Boolean {
        val snapshot = chatState.value
        if (
            snapshot.loading ||
            snapshot.kernel.running ||
            snapshot.usageMode != com.labteto.dshmobile.local.LocalUsageMode.CHAT
        ) return false
        return sessionRuntime.createSession(
            mode = com.labteto.dshmobile.local.session.LocalConversationMode.INDEPENDENT,
            usageMode = com.labteto.dshmobile.local.LocalUsageMode.CHAT,
            domainSpec = LocalChatSessionCreateSpec(
                galleryEntry = entry,
                galleryStoryId = storyId,
                freshGalleryStory = freshStory,
            ),
        )
    }

    internal fun switchChatMode(mode: LocalChatMode) =
        sessionRuntime.switchDomainMode(LocalChatSessionModeCommand(mode))
    internal fun configureGroupChatMembers(entries: List<PersonaGalleryEntry>): Boolean =
        groupMembership.configure(entries)
    internal suspend fun setGroupChatAnnouncement(text: String): Result<Unit> {
        val sessionId = runtimeStateStore.currentSessionId
        return saveGroupChatAnnouncement(
            state = chatState,
            text = text,
            sessionId = sessionId,
            persistNow = sessionStorage::writeCurrentSnapshotNow,
        )
    }
    internal fun removeGroupChatMemberByGalleryId(galleryId: String) =
        groupMembership.remove(galleryId)
    internal suspend fun generateReplySuggestions(): Boolean = replySuggestions.generate()
    internal fun diaryEntries(subjectKey: String, limit: Int = MAX_CHAT_DIARY_ENTRIES) = persistence.diaryStore.listActive(subjectKey, limit)
    internal fun diaryEntriesForTransfer(subjectKey: String) = persistence.diaryStore.listForTransfer(subjectKey)
    internal fun <T> importDiaryEntriesForTransfer(subjectKey: String, personaName: String, entries: List<ChatDiaryEntry>, commit: () -> T): T = persistence.diaryStore.importForTransfer(subjectKey, personaName, entries, commit)
    internal fun send(text: String, attachments: List<LocalImportedAttachment> = emptyList()): com.labteto.dshmobile.local.send.LocalSendResult = engine.send(text, attachments)
    internal fun editAndResendUserMessage(messageId: String, replacement: String): LocalChatUserEditResult =
        engine.editAndResendUserMessage(messageId, replacement)
    internal fun selectChatMessageVariant(messageId: String, targetIndex: Int): Boolean =
        branchCoordinator.selectVariant(messageId, targetIndex)
    internal fun regenerateReply(messageId: String): Boolean = engine.regenerateReply(messageId)
    internal fun stop() = runtimeStateStore.performVisibleOperation("停止聊天时保存失败") {
        val sessionId = runtimeStateStore.currentSessionId
        LocalChatPostTurnJobOwner.cancel()
        runtimeStateStore.cancelForegroundRun(sessionStorage.eventLogs.get(sessionId))
    }
    internal suspend fun undoChatPersonaCorrection(
        noticeId: Long,
        personaId: String,
        correction: String,
    ) = personaCorrections.undo(noticeId, personaId, correction)
}

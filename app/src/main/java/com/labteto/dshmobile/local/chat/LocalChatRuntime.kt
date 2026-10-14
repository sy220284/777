package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.chat.LocalChatMode
import com.labteto.dshmobile.local.chat.LocalChatUserEditResult
import com.labteto.dshmobile.local.attachment.LocalImportedAttachment
import com.labteto.dshmobile.local.session.LocalSessionRuntime
import com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore
import com.labteto.dshmobile.local.runtime.LocalSessionStorageRuntime
import javax.inject.Inject
import javax.inject.Singleton
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.uiBindingIdentity
import com.labteto.dshmobile.uicontract.PersonaSelectionUiPort
import com.labteto.dshmobile.uicontract.PersonaStoryUiPort
import com.labteto.dshmobile.uicontract.PersonaGalleryDeletionUiPort
import com.labteto.dshmobile.uicontract.PersonaGallerySaveUiPort
import com.labteto.dshmobile.uicontract.SavePersonaGalleryCommand
import com.labteto.dshmobile.uicontract.UiPersonaGallerySaveReceipt
import com.labteto.dshmobile.uicontract.UiStorySessionSync
import com.labteto.dshmobile.uicontract.DeletePersonaGalleryCommand
import com.labteto.dshmobile.uicontract.UiPersonaGalleryDeletionReceipt
import com.labteto.dshmobile.uicontract.PersonaStoryUiCommand
import com.labteto.dshmobile.uicontract.UiPersonaStoryReceipt
import com.labteto.dshmobile.uicontract.UiPersonaSelectionSnapshot
import com.labteto.dshmobile.uicontract.PersonaSelectionUiCommand
import com.labteto.dshmobile.uicontract.UiCommandReceipt
import com.labteto.dshmobile.uicontract.UiCommandRejection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
/** Chat/persona capability boundary for the local UI. */
@Singleton
class LocalChatRuntime @Inject internal constructor(
    private val execution: LocalChatExecutionPort,
    private val persistence: LocalChatPersistence,
    private val sessionRuntime: LocalSessionRuntime,
    private val personaCorrections: LocalChatPersonaCorrectionCoordinator,
    private val behaviorTuning: LocalCharacterBehaviorTuningCoordinator,
    private val chatState: LocalChatStatePort,
    private val runtimeStateStore: LocalRuntimeStateStore,
    private val sessionStorage: LocalSessionStorageRuntime,
    private val personaCoordinator: LocalChatPersonaCoordinator,
    private val groupMembership: LocalGroupChatMembershipCoordinator,
    private val replySuggestions: LocalReplySuggestionCoordinator,
    private val gallery: ChatPersonaGalleryStore,
) : PersonaSelectionUiPort, PersonaStoryUiPort, PersonaGalleryDeletionUiPort, PersonaGallerySaveUiPort {
    override suspend fun save(command: SavePersonaGalleryCommand): UiPersonaGallerySaveReceipt =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val before = synchronized(runtimeStateStore.foregroundRunHandle.lock) {
                val current = runtimeStateStore.state.value
                if (runtimeStateStore.sessionTransitioning || current.loading || current.kernel.running ||
                    current.usageMode != LocalUsageMode.CHAT || current.chat.groupChat.enabled ||
                    runtimeStateStore.uiBindingIdentity(current) != command.identity) null else chatState.value
            } ?: return@withContext UiPersonaGallerySaveReceipt.Rejected
            val lease = com.labteto.dshmobile.local.runtime.LocalSessionRuntimeRegistry.tryAcquire(before.sessionId,
                com.labteto.dshmobile.local.runtime.LocalSessionRuntimeKind.MAINTENANCE)
                ?: return@withContext UiPersonaGallerySaveReceipt.Rejected
            fun sameArchiveSource(): Boolean {
                val latest = chatState.value
                return !latest.loading && !latest.kernel.running && latest.usageMode == LocalUsageMode.CHAT && !latest.chat.groupChat.enabled &&
                    !runtimeStateStore.sessionTransitioning && runtimeStateStore.uiBindingIdentity(runtimeStateStore.state.value) == command.identity &&
                    latest.chat.chatPersona == before.chat.chatPersona && latest.chat.chatState == before.chat.chatState &&
                    latest.chat.chatContext == before.chat.chatContext && latest.chat.galleryId == before.chat.galleryId &&
                    latest.chat.galleryStoryId == before.chat.galleryStoryId && latest.chat.gallerySaveSuppressedThrough == before.chat.gallerySaveSuppressedThrough &&
                    latest.transcriptIndex.latestDialogueMessageId == before.transcriptIndex.latestDialogueMessageId
            }
            try {
                val history = sessionRuntime.completeTranscriptForUi(before.sessionId)
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                val stillCurrent = synchronized(runtimeStateStore.foregroundRunHandle.lock) {
                    sameArchiveSource()
                }
                if (!stillCurrent) return@withContext UiPersonaGallerySaveReceipt.Rejected
                val archived = if (before.chat.galleryStoryId == null && before.chat.gallerySaveSuppressedThrough > 0L)
                    history.filter { it.createdAt > before.chat.gallerySaveSuppressedThrough } else history
                val outcome = gallery.save(before.chat.chatPersona, before.sessionId, archived,
                    before.chat.chatState.withoutLegacyConversationContext(), command.notes, before.chat.chatContext,
                    command.existingId ?: before.chat.galleryId, command.existingStoryId ?: before.chat.galleryStoryId, command.forceNewStory)
                val applied = synchronized(runtimeStateStore.foregroundRunHandle.lock) {
                    // Binding has its own authoritative maintenance admission. Release our IO
                    // lease under the same foreground lock; another owner may still reject binding.
                    lease.close()
                    if (!sameArchiveSource()) false
                    else personaCoordinator.bindGallery(outcome.entry.id, outcome.storyId)
                }
                UiPersonaGallerySaveReceipt.Saved(outcome.entry.id, outcome.storyId,
                    if (applied) UiStorySessionSync.APPLIED else UiStorySessionSync.DEFERRED)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { UiPersonaGallerySaveReceipt.Failed(failure.message) }
            finally { lease.close() }
        }

    override suspend fun delete(command: DeletePersonaGalleryCommand): UiPersonaGalleryDeletionReceipt =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val deleted = gallery.deleteEntryForUi(command.galleryId) ?: return@withContext UiPersonaGalleryDeletionReceipt.Missing
                val portraitWarning = try { gallery.cleanupDeletedPortrait(deleted.portraitPath); null }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { failure.message ?: "旧立绘清理未完成" }
                val sync = synchronized(runtimeStateStore.foregroundRunHandle.lock) {
                    val current = runtimeStateStore.state.value
                    if (command.identity == null || runtimeStateStore.sessionTransitioning ||
                        runtimeStateStore.uiBindingIdentity(current) != command.identity) return@synchronized UiStorySessionSync.DEFERRED
                    val groupBound = current.usageMode == LocalUsageMode.CHAT && current.chat.groupChat.members.any { it.galleryId == command.galleryId }
                    val personaBound = current.usageMode == LocalUsageMode.CHAT && current.chat.galleryId == command.galleryId
                    if (!groupBound && !personaBound) return@synchronized UiStorySessionSync.NOT_BOUND
                    // Import may have restored the same archive while cleanup was pending.
                    if (gallery.findEntry(command.galleryId) != null) return@synchronized UiStorySessionSync.DEFERRED
                    val groupApplied = !groupBound || groupMembership.remove(command.galleryId)
                    val personaApplied = !personaBound || personaCoordinator.clearGalleryBinding(command.galleryId)
                    if (groupApplied && personaApplied) UiStorySessionSync.APPLIED else UiStorySessionSync.DEFERRED
                }
                UiPersonaGalleryDeletionReceipt.Deleted(sync, portraitWarning)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { UiPersonaGalleryDeletionReceipt.Failed(failure.message) }
        }

    override suspend fun submit(command: PersonaStoryUiCommand): UiPersonaStoryReceipt =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val changed = when (command) {
                    is PersonaStoryUiCommand.SaveDetails -> gallery.updateStoryDetails(command.galleryId, command.storyId, command.notes, command.storyStage)
                    is PersonaStoryUiCommand.Delete -> gallery.deleteStory(command.galleryId, command.storyId)
                }
                if (!changed) return@withContext UiPersonaStoryReceipt.Missing
                val sync = synchronized(runtimeStateStore.foregroundRunHandle.lock) {
                    val current = runtimeStateStore.state.value
                    if (command.identity == null || runtimeStateStore.sessionTransitioning ||
                        runtimeStateStore.uiBindingIdentity(current) != command.identity) return@synchronized UiStorySessionSync.DEFERRED
                    if (current.usageMode != LocalUsageMode.CHAT || current.chat.galleryId != command.galleryId ||
                        current.chat.galleryStoryId != command.storyId) return@synchronized UiStorySessionSync.NOT_BOUND
                    val applied = when (command) {
                        is PersonaStoryUiCommand.SaveDetails -> {
                            val stage = gallery.findEntry(command.galleryId)?.stories?.firstOrNull { it.id == command.storyId }?.chatContext?.storyStage
                                ?: return@synchronized UiStorySessionSync.DEFERRED
                            personaCoordinator.updateBoundStoryStage(command.galleryId, command.storyId, stage)
                        }
                        is PersonaStoryUiCommand.Delete -> {
                            if (gallery.findEntry(command.galleryId)?.stories?.any { it.id == command.storyId } == true)
                                return@synchronized UiStorySessionSync.DEFERRED
                            personaCoordinator.clearGalleryBinding(command.galleryId, command.storyId, keepCharacter = true)
                        }
                    }
                    if (applied) UiStorySessionSync.APPLIED else UiStorySessionSync.DEFERRED
                }
                UiPersonaStoryReceipt.Saved(sync)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { UiPersonaStoryReceipt.Failed(failure.message) }
        }

    override val snapshots = runtimeStateStore.state.map { snapshot() }.distinctUntilChanged()

    override fun snapshot(): UiPersonaSelectionSnapshot = synchronized(runtimeStateStore.foregroundRunHandle.lock) {
        val current = runtimeStateStore.state.value
        val ready = current.usageMode == LocalUsageMode.CHAT && !current.loading &&
            !current.kernel.running && !runtimeStateStore.sessionTransitioning
        UiPersonaSelectionSnapshot(runtimeStateStore.uiBindingIdentity(current),
            ready && personaCoordinator.canSelectForCurrentChat(), ready,
            when {
                !ready -> "请在聊天空闲且会话加载完成后操作"
                current.chat.groupChat.enabled -> "当前是群聊；可以开始新的单人故事"
                current.transcriptIndex.hasDialogue -> "已有对话不能替换人物；可以开始新的故事"
                else -> null
            }, canSaveCurrent = ready && !current.chat.groupChat.enabled)
    }

    override fun submit(command: PersonaSelectionUiCommand): UiCommandReceipt = try {
        synchronized(runtimeStateStore.foregroundRunHandle.lock) {
            val current = runtimeStateStore.state.value
            if (runtimeStateStore.sessionTransitioning) return@synchronized UiCommandReceipt.Rejected(UiCommandRejection.SESSION_TRANSITION)
            if (runtimeStateStore.uiBindingIdentity(current) != command.identity || current.loading ||
                current.usageMode != LocalUsageMode.CHAT) return@synchronized UiCommandReceipt.Rejected(UiCommandRejection.STALE_IDENTITY)
            if (current.kernel.running) return@synchronized UiCommandReceipt.NotAdmitted
            val entry = gallery.findEntry(command.galleryId) ?: return@synchronized UiCommandReceipt.NotAdmitted
            when (command) {
                is PersonaSelectionUiCommand.SelectForCurrentChat ->
                    if (personaCoordinator.select(entry.persona, entry.id, runtimeStateStore.foregroundRunHandle)) UiCommandReceipt.Queued
                    else UiCommandReceipt.NotAdmitted
                is PersonaSelectionUiCommand.StartStory -> {
                    if (command.storyId != null && entry.stories.none { it.id == command.storyId }) return@synchronized UiCommandReceipt.NotAdmitted
                    if (startSessionFromGallery(entry, command.storyId, command.freshStory)) UiCommandReceipt.Accepted else UiCommandReceipt.NotAdmitted
                }
            }
        }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: Exception) { UiCommandReceipt.Failed(failure.message ?: "人物操作未完成，请刷新确认状态") }

    internal suspend fun configureChatPersona(profile: PersonaProfile): Result<Unit> = behaviorTuning.configure(profile)
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
        groupMembership.configure(entries, runtimeStateStore.foregroundRunHandle)
    /** UI admission holds the Shared foreground lock; the durable fact precedes its cache. */
    internal fun commitGroupAnnouncementFromUi(text: String): Result<Unit> {
        val sessionId = runtimeStateStore.currentSessionId
        val result = commitGroupChatAnnouncement(chatState, text, sessionId) { target ->
            appendChatDomainStateCommit(sessionStorage.eventLogs.get(sessionId), target, "group-announcement-updated")
        }
        if (result.isFailure) return Result.failure(requireNotNull(result.exceptionOrNull()))
        if (result.getOrThrow()) {
            try { sessionStorage.enqueueCurrentSnapshot(sessionId) }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (failure: Exception) {
                chatState.update { current -> if (current.sessionId == sessionId)
                    current.copy(error = "群公告已保存，快照更新失败：${failure.message}") else current }
            }
        }
        return Result.success(Unit)
    }
    internal fun removeGroupChatMemberByGalleryId(galleryId: String) =
        groupMembership.remove(galleryId)
    internal suspend fun generateReplySuggestions(): Boolean = replySuggestions.generate()
    internal fun diaryEntries(subjectKey: String, limit: Int = MAX_CHAT_DIARY_ENTRIES) = persistence.diaryStore.listActive(subjectKey, limit)
    internal fun correctDiaryEntry(subjectKey: String, id: String, revision: Long, correction: ChatDiaryDelta): Boolean {
        return commitDiaryDecision { persistence.diaryStore.correctEntry(subjectKey, id, revision, correction) }
    }
    internal fun deactivateDiaryEntry(subjectKey: String, id: String, revision: Long): Boolean {
        return commitDiaryDecision { persistence.diaryStore.deactivateEntry(subjectKey, id, revision) }
    }

    private fun commitDiaryDecision(write: () -> Boolean): Boolean {
        val snapshot = chatState.value
        val lease = com.labteto.dshmobile.local.runtime.LocalSessionRuntimeRegistry.tryAcquire(
            snapshot.sessionId, com.labteto.dshmobile.local.runtime.LocalSessionRuntimeKind.MAINTENANCE,
        ) ?: return false
        try {
            if (chatState.value.sessionId != snapshot.sessionId || !write()) return false
            reconcileDiaryDecision()
            return true
        } finally { lease.close() }
    }

    private fun reconcileDiaryDecision() {
        val snapshot = chatState.value
        if (snapshot.usageMode != com.labteto.dshmobile.local.LocalUsageMode.CHAT) return
        LocalChatPostTurnJobOwner.cancel()
        chatState.update { current ->
            if (current.sessionId != snapshot.sessionId) return@update current
            val singleContext = current.chat.chatContext.withExperience(current.sessionId, listOf(current.sessionId))
            val singleDecisions = persistence.diaryStore.decisionsForExperience(
                chatRelationshipSubjectKey(current.chat.galleryId, current.chat.personaId), singleContext,
            )
            val groupContext = current.chat.groupChat.context.withExperience(current.sessionId, listOf(current.sessionId))
            val groupDecisions = current.chat.groupChat.members.flatMap { member ->
                persistence.diaryStore.decisionsForExperience(
                    chatRelationshipSubjectKey(member.galleryId, member.personaId), groupContext,
                )
            }
            current.copy(chat = current.chat.copy(
                chatState = reconcileDiaryState(current.chat.chatState, singleDecisions),
                chatContext = reconcileDiaryContext(singleContext, singleDecisions).rebaseGeneration(),
                groupChat = current.chat.groupChat.copy(
                    context = reconcileDiaryContext(groupContext, groupDecisions).rebaseGeneration(),
                    members = current.chat.groupChat.members.map { member ->
                        member.copy(chatState = persistence.diaryStore.reconcileState(
                            chatRelationshipSubjectKey(member.galleryId, member.personaId), member.chatState, groupContext,
                        ))
                    },
                ),
                replySuggestions = emptyList(),
            ))
        }
        val target = chatState.value
        appendChatDomainStateCommit(sessionStorage.eventLogs.get(target.sessionId), target, "diary-decision-reconciled")
        sessionStorage.enqueueCurrentSnapshot(target.sessionId)
    }
    internal fun diaryEntriesForTransfer(subjectKey: String) = persistence.diaryStore.listForTransfer(subjectKey)
    internal fun <T> importDiaryEntriesForTransfer(subjectKey: String, personaName: String, entries: List<ChatDiaryEntry>, commit: () -> T): T = persistence.diaryStore.importForTransfer(subjectKey, personaName, entries, commit)
    internal fun send(text: String, attachments: List<LocalImportedAttachment> = emptyList()): com.labteto.dshmobile.local.send.LocalSendResult = execution.send(text, attachments)
    internal fun editAndResendUserMessage(messageId: String, replacement: String): LocalChatUserEditResult =
        execution.editAndResendUserMessage(messageId, replacement)
    internal fun selectChatMessageVariant(messageId: String, targetIndex: Int): Boolean =
        execution.selectMessageVariant(messageId, targetIndex)
    internal fun regenerateReply(messageId: String): Boolean = execution.regenerateReply(messageId)
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

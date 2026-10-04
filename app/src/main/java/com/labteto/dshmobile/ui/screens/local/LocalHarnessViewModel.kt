package com.labteto.dshmobile.ui.screens.local

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.labteto.dshmobile.local.LocalImportedAttachment
import com.labteto.dshmobile.local.LocalConversationMode
import com.labteto.dshmobile.local.LocalChatUserEditResult
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.send.LocalSendResult
import com.labteto.dshmobile.local.presentation.LocalUiRuntime
import com.labteto.dshmobile.local.presentation.projectChatSurfaceState
import com.labteto.dshmobile.local.presentation.projectShellState
import com.labteto.dshmobile.local.presentation.projectWorkSurfaceState
import com.labteto.dshmobile.local.presentation.projectWorkState
import com.labteto.dshmobile.local.chat.PersonaAutoFillService
import com.labteto.dshmobile.local.chat.GroupAnnouncementService
import com.labteto.dshmobile.local.chat.PersonaProfile
import com.labteto.dshmobile.local.chat.PersonaPreset
import com.labteto.dshmobile.local.chat.PersonaPresetCatalog
import com.labteto.dshmobile.local.chat.ChatPersonaGalleryStore
import com.labteto.dshmobile.local.chat.ChatDiaryEntry
import com.labteto.dshmobile.local.chat.PersonaGalleryEntry
import com.labteto.dshmobile.local.chat.PersonaAppendSuggestion
import com.labteto.dshmobile.local.chat.PersonaInspectionResult
import com.labteto.dshmobile.local.chat.PersonaInspectionService
import com.labteto.dshmobile.local.chat.PersonaTransferDocument
import com.labteto.dshmobile.local.chat.PersonaTransferFormat
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
@HiltViewModel
class LocalHarnessViewModel @Inject constructor(
    private val runtime: LocalUiRuntime,
    private val personaAutoFillService: PersonaAutoFillService,
    private val groupAnnouncementService: GroupAnnouncementService,
    personaInspectionService: PersonaInspectionService,
    galleryStore: ChatPersonaGalleryStore,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {
    val state = runtime.session.state
    val streamingState = runtime.session.streamingState; internal val sendFeedbackState = runtime.session.sendFeedbackState
    val shellState = state.projectShellState(viewModelScope)
    val chatSurfaceState = state.projectChatSurfaceState(viewModelScope)
    val workSurfaceState = state.projectWorkSurfaceState(viewModelScope)
    val activeModelProfile = runtime.model.activeProfile
    val workState = state.projectWorkState(viewModelScope)
    private val personaGalleryController = LocalPersonaGalleryUiController(
        runtime = runtime,
        personaAutoFillService = personaAutoFillService,
        personaInspectionService = personaInspectionService,
        galleryStore = galleryStore,
        appContext = appContext,
        scope = viewModelScope,
    )
    private val chatModelAssistController = LocalChatModelAssistController(
        runtime = runtime,
        personaAutoFillService = personaAutoFillService,
        groupAnnouncementService = groupAnnouncementService,
    )
    val gallery = personaGalleryController.gallery
    val personaPresets: List<PersonaPreset> = personaGalleryController.personaPresets
    private val transcriptHistoryController = LocalTranscriptHistoryController(
        session = runtime.session,
        currentSessionId = { state.value.sessionId },
        liveMessages = { state.value.messages },
        scope = viewModelScope,
    )
    internal val transcriptHistory = transcriptHistoryController.history
    private val conversationUiState = LocalConversationUiStateStore(appContext)
    val pinnedSessionIds = conversationUiState.pinnedSessionIds
    val sessionTitleOverrides = conversationUiState.sessionTitleOverrides
    suspend fun saveCurrentToGallery(
        notes: String,
        existingId: String? = null,
        existingStoryId: String? = null,
        forceNewStory: Boolean = false,
    ): Result<PersonaGalleryEntry> =
        personaGalleryController.saveCurrentToGallery(notes, existingId, existingStoryId, forceNewStory)
    fun hasUnsavedCurrentPersona(): Boolean = personaGalleryController.hasUnsavedCurrentPersona()
    fun currentGalleryNeedsUpdate(): Boolean = personaGalleryController.currentGalleryNeedsUpdate()
    fun currentGalleryHasUnsavedChanges(): Boolean = personaGalleryController.currentGalleryHasUnsavedChanges()
    fun selectGalleryPersonaForCurrentChat(id: String): Boolean =
        personaGalleryController.selectGalleryPersonaForCurrentChat(id)
    suspend fun createGalleryPersona(profile: PersonaProfile): Result<PersonaGalleryEntry> =
        personaGalleryController.createGalleryPersona(profile)
    suspend fun autoFillNewPersona(description: String): Result<PersonaProfile> =
        personaGalleryController.autoFillNewPersona(description)
    suspend fun inspectGalleryPersona(id: String, storyId: String?): Result<PersonaInspectionResult> =
        personaGalleryController.inspectGalleryPersona(id, storyId)
    suspend fun applyGallerySuggestions(
        id: String,
        suggestions: List<PersonaAppendSuggestion>,
    ): Result<PersonaGalleryEntry> = personaGalleryController.applyGallerySuggestions(id, suggestions)
    suspend fun setGalleryPortrait(id: String, uri: Uri): Result<PersonaGalleryEntry> =
        personaGalleryController.setGalleryPortrait(id, uri)
    suspend fun removeGalleryPortrait(id: String): Result<PersonaGalleryEntry> =
        personaGalleryController.removeGalleryPortrait(id)
    suspend fun editGalleryNotes(id: String, storyId: String, notes: String): Result<Unit> =
        personaGalleryController.editGalleryNotes(id, storyId, notes)
    suspend fun renameGalleryStory(id: String, storyId: String, title: String): Result<Unit> =
        personaGalleryController.renameGalleryStory(id, storyId, title)
    internal suspend fun exportGalleryPersona(
        id: String,
        format: PersonaTransferFormat,
    ): Result<PersonaTransferDocument> = personaGalleryController.exportGalleryPersona(id, format)
    internal suspend fun importGalleryPersona(
        bytes: ByteArray,
        fileName: String?,
        mimeType: String?,
    ): Result<PersonaGalleryEntry> =
        personaGalleryController.importGalleryPersona(bytes, fileName, mimeType)
    suspend fun installPersonaPreset(id: String): Result<PersonaGalleryEntry> =
        personaGalleryController.installPersonaPreset(id)
    suspend fun deleteGalleryEntry(id: String): Result<Unit> =
        personaGalleryController.deleteGalleryEntry(id)
    suspend fun deleteGalleryStory(id: String, storyId: String): Result<Unit> =
        personaGalleryController.deleteGalleryStory(id, storyId)
    suspend fun deleteGalleryHistoryMessage(
        id: String,
        storyId: String,
        messageKey: String,
    ): Result<Unit> = personaGalleryController.deleteGalleryHistoryMessage(id, storyId, messageKey)
    fun startFromGallery(id: String, storyId: String?, freshStory: Boolean): Boolean =
        personaGalleryController.startFromGallery(id, storyId, freshStory)

    internal suspend fun prepareTranscriptHistory(
        sessionId: String,
        force: Boolean = false,
    ) = transcriptHistoryController.prepare(sessionId, force)

    internal suspend fun loadOlderTranscript(sessionId: String): Result<Int> =
        transcriptHistoryController.loadOlder(sessionId)

    fun configure(apiKey: String, model: String, baseUrl: String) = runtime.model.configure(apiKey, model, baseUrl)
    fun selectModel(model: String) = runtime.model.selectModel(model)
    internal fun send(text: String, attachments: List<LocalImportedAttachment> = emptyList()): LocalSendResult = runtime.chat.send(text, attachments)
    suspend fun generateReplySuggestions(): Boolean = runtime.chat.generateReplySuggestions()
    internal suspend fun diaryEntries(subjectKey: String): List<ChatDiaryEntry> =
        withContext(Dispatchers.IO) { runtime.chat.diaryEntries(subjectKey) }
    fun createGroupChatSession(ids: List<String>): Boolean {
        val distinctIds = ids.distinct()
        val entriesById = gallery.value.associateBy(PersonaGalleryEntry::id)
        val entries = distinctIds.mapNotNull(entriesById::get)
        if (entries.size != distinctIds.size) return false
        return runtime.chat.createGroupChatSession(entries)
    }
    fun createSingleChatSession() = runtime.chat.createSingleChatSession()
    fun openGroupChatMode() = runtime.chat.switchChatMode(com.labteto.dshmobile.local.LocalChatMode.GROUP)
    fun leaveGroupChatMode() = runtime.chat.switchChatMode(com.labteto.dshmobile.local.LocalChatMode.SINGLE)
    fun configureGroupChatMembers(ids: List<String>): Boolean {
        val entriesById = gallery.value.associateBy(PersonaGalleryEntry::id)
        val entries = ids.distinct().mapNotNull(entriesById::get)
        if (entries.size != ids.distinct().size) return false
        return runtime.chat.configureGroupChatMembers(entries)
    }
    suspend fun setGroupChatAnnouncement(text: String): Result<Unit> = runtime.chat.setGroupChatAnnouncement(text)

    suspend fun generateGroupChatAnnouncement(direction: String): Result<String> =
        chatModelAssistController.generateGroupAnnouncement(direction)
    suspend fun editAndResendUserMessage(messageId: String, text: String): LocalChatUserEditResult {
        val result = withContext(Dispatchers.IO) {
            runtime.chat.editAndResendUserMessage(messageId, text)
        }
        if (result == LocalChatUserEditResult.SENT) {
            refreshTranscriptHistoryAfterTimelineRewrite()
        }
        return result
    }

    suspend fun selectChatMessageVariant(messageId: String, targetIndex: Int): Boolean {
        val selected = withContext(Dispatchers.IO) {
            runtime.chat.selectChatMessageVariant(messageId, targetIndex)
        }
        if (selected) refreshTranscriptHistoryAfterTimelineRewrite()
        return selected
    }

    private fun refreshTranscriptHistoryAfterTimelineRewrite() =
        transcriptHistoryController.refreshAfterTimelineRewrite()
    fun regenerateReply(messageId: String): Boolean = runtime.chat.regenerateReply(messageId)
    fun toggleSessionPinned(sessionId: String) = conversationUiState.toggleSessionPinned(sessionId)
    fun renameSession(sessionId: String, title: String): Boolean = conversationUiState.renameSession(sessionId, title)
    suspend fun deleteSessions(ids: Set<String>): Int = conversationUiState.deleteSessions(ids, runtime.session::deleteSessions)
    suspend fun importAttachment(uri: Uri): LocalImportedAttachment = runtime.session.importAttachment(uri)
    suspend fun workspaceFiles() = runtime.session.workspaceFilesForUi()
    suspend fun conversationFiles(sessionId: String) = runtime.session.conversationFilesForUi(sessionId)
    suspend fun previewWorkspaceFile(path: String) = runtime.session.previewWorkspaceFileForUi(path)
    fun backgroundJobOutput(jobId: String): String = runtime.work.backgroundJobOutputForUi(jobId)
    fun stopBackgroundJob(jobId: String): String = runtime.work.stopBackgroundJobForUi(jobId)
    fun approve(callId: String) = runtime.work.answerApproval(callId, true)
    fun deny(callId: String) = runtime.work.answerApproval(callId, false)
    fun enableAutoApproval() = runtime.work.enableAutoApproval()
    fun enableAutoApprovalForPending(callId: String) = runtime.work.enableAutoApprovalForPending(callId)
    fun enableDeviceApprovalLease(callId: String) = runtime.work.enableDeviceApprovalLease(callId)
    fun disableDeviceApprovalLease() = runtime.work.disableDeviceApprovalLease()
    fun disableAutoApproval() = runtime.work.disableAutoApproval()
    fun answerQuestion(callId: String, answer: String) = runtime.work.answerQuestion(callId, answer)
    fun cancelQuestion(callId: String) = runtime.work.cancelQuestion(callId)
    fun stop() = runtime.work.stop()
    fun newSession() = runtime.session.createSession(LocalConversationMode.INDEPENDENT)
    fun createSession(mode: LocalConversationMode) = runtime.session.createSession(mode)
    fun setPlanMode(enabled: Boolean) = runtime.work.setPlanMode(enabled)
    fun switchUsageMode(mode: LocalUsageMode) = runtime.session.switchUsageMode(mode)
    suspend fun configureChatPersona(profile: PersonaProfile): Result<Unit> = personaGalleryController.configureChatPersona(profile)
    fun undoChatPersonaCorrection(noticeId: Long, personaId: String, correction: String) =
        runtime.chat.undoChatPersonaCorrection(noticeId, personaId, correction)

    suspend fun autoFillChatPersona(description: String): Result<PersonaProfile> =
        chatModelAssistController.autoFillCurrentPersona(description)
    fun switchSession(sessionId: String) = runtime.session.switchSession(sessionId)
    fun clearCredential() = runtime.model.clearCredential()
}

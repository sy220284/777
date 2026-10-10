package com.labteto.dshmobile.ui.screens.local

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.interaction.LocalApprovalPreferences
import com.labteto.dshmobile.local.tools.LocalNetworkSearchSettings
import com.labteto.dshmobile.local.attachment.LocalImportedAttachment
import com.labteto.dshmobile.local.chat.ChatDiaryEntry
import com.labteto.dshmobile.local.chat.LocalChatUserEditResult
import com.labteto.dshmobile.local.chat.PersonaAppendSuggestion
import com.labteto.dshmobile.local.chat.PersonaGalleryEntry
import com.labteto.dshmobile.local.chat.PersonaInspectionResult
import com.labteto.dshmobile.local.chat.PersonaPreset
import com.labteto.dshmobile.local.presentation.PersonaPresetCatalog
import com.labteto.dshmobile.local.chat.PersonaProfile
import com.labteto.dshmobile.local.chat.PersonaTransferDocument
import com.labteto.dshmobile.local.chat.PersonaTransferFormat
import com.labteto.dshmobile.local.presentation.LocalChatUiFacade
import com.labteto.dshmobile.local.presentation.LocalSessionUiFacade
import com.labteto.dshmobile.local.presentation.LocalUiRuntime
import com.labteto.dshmobile.local.presentation.LocalToolsUiFacade
import com.labteto.dshmobile.local.presentation.LocalProjectUiFacade
import com.labteto.dshmobile.local.presentation.projectChatSurfaceState
import com.labteto.dshmobile.local.presentation.projectShellState
import com.labteto.dshmobile.local.presentation.projectWorkState
import com.labteto.dshmobile.local.presentation.projectWorkSurfaceState
import com.labteto.dshmobile.local.send.LocalSendResult
import com.labteto.dshmobile.local.session.LocalConversationMode
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel
class LocalHarnessViewModel @Inject constructor(
    private val runtime: LocalUiRuntime,
    private val chatUi: LocalChatUiFacade,
    private val sessionUi: LocalSessionUiFacade,
    private val projects: LocalProjectUiFacade,
    private val toolsUi: LocalToolsUiFacade,
    private val approvalPreferences: LocalApprovalPreferences,
    private val networkSearchSettings: LocalNetworkSearchSettings,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {
    internal suspend fun installedSkillDisplayNames(): Map<String, String> =
        toolsUi.installedSkills().associate { it.name to it.displayName }

    val approvalMode = approvalPreferences.approvalMode
    val networkSearchEnabled = networkSearchSettings.enabled
    fun setNetworkSearchEnabled(enabled: Boolean) = networkSearchSettings.setEnabled(enabled)
    val state = runtime.state
    val streamingState = runtime.streamingState; internal val sendFeedbackState = runtime.sendFeedbackState
    val shellState = state.projectShellState(viewModelScope)
    val chatSurfaceState = state.projectChatSurfaceState(viewModelScope)
    val workSurfaceState = state.projectWorkSurfaceState(viewModelScope)
    val activeModelProfile = runtime.model.activeProfile
    val workState = state.projectWorkState(viewModelScope)
    internal val projectCatalog = projects.catalog
    internal val projectRecoveryNotice = projects.recoveryNotice
    internal fun backupAndResetProjectCatalog() = projects.backupAndResetCatalog()
    internal fun createProjectWorkSession() = runtime.session.createSession(
        LocalConversationMode.PROJECT, LocalUsageMode.WORK,
    )
    internal fun createProject(name: String) = projects.create(name)
    internal fun selectProject(id: String) = projects.select(id)
    internal suspend fun deleteProject(id: String) = projects.delete(id)
    internal fun renameProject(id: String, name: String) = projects.rename(id, name)
    internal fun updateProjectInstructions(id: String, instructions: String) = projects.updateInstructions(id, instructions)
    private val personaGalleryController = LocalPersonaGalleryUiController(
        runtime = runtime,
        chatUi = chatUi,
        appContext = appContext,
        scope = viewModelScope,
    )
    private val chatModelAssistController = LocalChatModelAssistController(
        runtime = runtime,
        chatUi = chatUi,
    )
    val gallery = personaGalleryController.gallery
    val personaPresets: List<PersonaPreset> = personaGalleryController.personaPresets
    private val transcriptHistoryController = LocalTranscriptHistoryController(
        session = sessionUi,
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
    internal fun send(
        text: String,
        attachments: List<LocalImportedAttachment> = emptyList(),
    ): LocalSendResult = when (state.value.usageMode) {
        LocalUsageMode.WORK -> runtime.work.send(text, attachments)
        LocalUsageMode.CHAT -> runtime.chat.send(text, attachments)
    }
    internal fun sendWithTeam(
        text: String,
        attachments: List<LocalImportedAttachment> = emptyList(),
    ): LocalSendResult = if (state.value.usageMode == LocalUsageMode.WORK) {
        runtime.work.sendWithTeam(text, attachments)
    } else {
        runtime.chat.send(text, attachments)
    }

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
    fun openGroupChatMode() = runtime.chat.switchChatMode(com.labteto.dshmobile.local.chat.LocalChatMode.GROUP)
    fun leaveGroupChatMode() = runtime.chat.switchChatMode(com.labteto.dshmobile.local.chat.LocalChatMode.SINGLE)
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
            when (state.value.usageMode) {
                LocalUsageMode.WORK -> runtime.work.editAndResendUserMessage(messageId, text)
                LocalUsageMode.CHAT -> runtime.chat.editAndResendUserMessage(messageId, text)
            }
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
    fun regenerateReply(messageId: String): Boolean = when (state.value.usageMode) {
        LocalUsageMode.WORK -> runtime.work.regenerateReply(messageId)
        LocalUsageMode.CHAT -> runtime.chat.regenerateReply(messageId)
    }
    fun toggleSessionPinned(sessionId: String) = conversationUiState.toggleSessionPinned(sessionId)
    fun renameSession(sessionId: String, title: String): Boolean = conversationUiState.renameSession(sessionId, title)
    suspend fun deleteSessions(ids: Set<String>): Int = conversationUiState.deleteSessions(ids, runtime.session::deleteSessions)
    suspend fun importAttachment(uri: Uri): LocalImportedAttachment = runtime.session.importAttachment(uri)
    suspend fun workspaceFiles() = runtime.session.workspaceFilesForUi()
    suspend fun conversationFiles(sessionId: String) = runtime.session.conversationFilesForUi(sessionId)
    suspend fun previewWorkspaceFile(path: String) = runtime.session.previewWorkspaceFileForUi(path)
    fun artifactsForUi(sessionId: String) = runtime.work.artifactsForUi(sessionId)
    fun historyPageForUi(sessionId: String, cursor: com.labteto.dshmobile.local.presentation.LocalWorkHistoryCursor?) =
        runtime.work.historyPageForUi(sessionId, cursor)

    fun artifactHistoryForUi(sessionId: String, eventLimit: Int) =
        runtime.work.artifactsForUi(sessionId, eventLimit)
    fun toolActivitiesForUi(sessionId: String) = runtime.work.toolActivitiesForUi(sessionId)
    fun toolEvidenceForUi(sessionId: String, callId: String, sequence: Long) =
        runtime.work.toolEvidenceForUi(sessionId, callId, sequence)
    fun eventSequenceForUi(sessionId: String) = runtime.work.eventSequenceForUi(sessionId)
    fun backgroundJobOutput(jobId: String): String = runtime.work.backgroundJobOutputForUi(jobId)
    fun stopBackgroundJob(jobId: String): String = runtime.work.stopBackgroundJobForUi(jobId)
    internal suspend fun startBackgroundAgent(task: String): LocalWorkUiActionResult {
        val result = runtime.work.startBackgroundAgentForUi(task)
        return LocalWorkUiActionResult(result.accepted, result.message)
    }

    internal suspend fun startResearchAgent(task: String): LocalWorkUiActionResult {
        val result = runtime.work.startResearchAgentForUi(task)
        return LocalWorkUiActionResult(result.accepted, result.message)
    }

    internal suspend fun sendBackgroundAgentMessage(
        agentId: String,
        message: String,
    ): LocalWorkUiActionResult {
        val result = runtime.work.sendBackgroundAgentMessageForUi(agentId, message)
        return LocalWorkUiActionResult(result.accepted, result.message)
    }

    internal fun teamMemberOutput(jobId: String): String = runtime.work.backgroundJobOutputForUi(jobId)

    internal suspend fun sendTeamMemberMessage(
        memberId: String,
        message: String,
    ): LocalWorkUiActionResult {
        val result = runtime.work.sendTeamMemberMessageForUi(memberId, message)
        return LocalWorkUiActionResult(result.accepted, result.message)
    }

    internal suspend fun stopTeamMember(memberId: String): LocalWorkUiActionResult {
        val result = runtime.work.stopTeamMemberForUi(memberId)
        return LocalWorkUiActionResult(result.accepted, result.message)
    }

    internal suspend fun stopTeam(): LocalWorkUiActionResult {
        val result = runtime.work.stopTeamForUi()
        return LocalWorkUiActionResult(result.accepted, result.message)
    }
    fun approve(callId: String) = runtime.work.answerApproval(callId, true)
    fun deny(callId: String) = runtime.work.answerApproval(callId, false)
    fun useDefaultApproval() = runtime.work.useDefaultApproval()
    fun enableAutoApproval() = runtime.work.enableAutoApproval()
    fun enableAutoApprovalForPending(callId: String) = runtime.work.enableAutoApprovalForPending(callId)
    fun enableDeviceApprovalLease(callId: String) = runtime.work.enableDeviceApprovalLease(callId)
    fun disableDeviceApprovalLease() = runtime.work.disableDeviceApprovalLease()
    fun disableAutoApproval() = runtime.work.disableAutoApproval()
    fun answerQuestion(callId: String, answer: String) = runtime.work.answerQuestion(callId, answer)
    fun cancelQuestion(callId: String) = runtime.work.cancelQuestion(callId)
    fun stop() {
        when (state.value.usageMode) {
            LocalUsageMode.CHAT -> runtime.chat.stop()
            LocalUsageMode.WORK -> runtime.work.stop()
        }
    }
    internal fun workHandoffSummary(): String = runtime.session.currentHandoffSummary()
    internal fun createWorkContinuation(sourceSessionId: String, summary: String): Boolean {
        if (state.value.sessionId != sourceSessionId || state.value.usageMode != LocalUsageMode.CHAT) return false
        return runtime.session.createSession(
            mode = LocalConversationMode.CONTINUATION,
            usageMode = LocalUsageMode.WORK,
            handoffSummaryOverride = summary,
        )
    }
    fun newSession() = runtime.session.createSession(LocalConversationMode.INDEPENDENT, state.value.usageMode)
    fun createSession(mode: LocalConversationMode) = runtime.session.createSession(mode, state.value.usageMode)
    fun setPlanMode(enabled: Boolean) = runtime.work.setPlanMode(enabled)
    fun switchUsageMode(mode: LocalUsageMode) = runtime.session.switchUsageMode(mode)
    suspend fun configureChatPersona(profile: PersonaProfile): Result<Unit> = personaGalleryController.configureChatPersona(profile)
    fun undoChatPersonaCorrection(noticeId: Long, personaId: String, correction: String) {
        viewModelScope.launch {
            runtime.chat.undoChatPersonaCorrection(noticeId, personaId, correction)
        }
    }

    suspend fun autoFillChatPersona(description: String): Result<PersonaProfile> =
        chatModelAssistController.autoFillCurrentPersona(description)
    fun switchSession(sessionId: String) = runtime.session.switchSession(sessionId)
    fun clearCredential() = runtime.model.clearCredential()
}

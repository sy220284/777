package com.labteto.dshmobile.ui.screens.local

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.interaction.LocalApprovalPreferences
import com.labteto.dshmobile.local.tools.LocalNetworkSearchSettings
import com.labteto.dshmobile.local.attachment.LocalImportedAttachment
import com.labteto.dshmobile.local.chat.ChatDiaryDelta
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
import com.labteto.dshmobile.local.presentation.projectChatSurfaceState
import com.labteto.dshmobile.local.presentation.projectShellState
import com.labteto.dshmobile.local.presentation.projectWorkState
import com.labteto.dshmobile.local.presentation.projectWorkSurfaceState
import com.labteto.dshmobile.local.send.LocalSendResult
import com.labteto.dshmobile.local.session.LocalConversationMode
import com.labteto.dshmobile.ui.screens.settings.readDeviceCapabilityState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A user/tool row must not become the source of a generated assistant document. */
internal fun nativeV6SourceAssistantMessageId(
    messages: List<com.labteto.dshmobile.local.session.LocalHarnessMessage>,
): String? = messages.lastOrNull { it.role == "assistant" }?.id

@HiltViewModel
class LocalHarnessViewModel @Inject constructor(
    private val runtime: LocalUiRuntime,
    private val chatUi: LocalChatUiFacade,
    private val sessionUi: LocalSessionUiFacade,
    private val toolsUi: LocalToolsUiFacade,
    private val usageUi: com.labteto.dshmobile.local.presentation.LocalUsageUiFacade,
    private val teamUi: com.labteto.dshmobile.uicontract.WorkTeamUiPort,
    private val documentFactory: com.labteto.dshmobile.local.presentation.LocalUiDocumentUiPortFactory,
    private val documentMedia: com.labteto.dshmobile.uicontract.MediaUiPort,
    private val savedStateHandle: androidx.lifecycle.SavedStateHandle,
    private val conversationPorts: com.labteto.dshmobile.local.LocalConversationUiPortComposition,
    private val approvalPreferences: LocalApprovalPreferences,
    private val networkSearchSettings: LocalNetworkSearchSettings,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    internal suspend fun installedSkillDisplayNames(): Map<String, String> =
        toolsUi.installedSkills().associate { it.name to it.displayName }

    /** Config presence is a hint only; the actual tool invocation still checks credentials and policy. */
    internal suspend fun githubConfiguredForHandoff(): Boolean? = try {
        toolsUi.githubConfigured()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    internal fun deviceReadinessForHandoff(): Pair<Boolean, Boolean> =
        readDeviceCapabilityState().let { it.accessibility to it.notifications }

    internal suspend fun externalReadinessForHandoff(): Pair<Boolean?, Boolean?> = try {
        toolsUi.handoffExternalReadiness()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null to null
    }

    internal fun workHandoffCapabilityReadiness(
        task: String,
        githubConfigured: Boolean?,
        networkSearchEnabled: Boolean,
        modelConfigured: Boolean?,
        mcpToolsAvailable: Boolean?,
        pluginsInstalled: Boolean?,
        accessibilityActive: Boolean?,
        notificationAccessActive: Boolean?,
    ) = toolsUi.workHandoffCapabilityReadiness(
        task, githubConfigured, networkSearchEnabled,
        showModelStatus = true, modelConfigured = modelConfigured,
        mcpToolsAvailable = mcpToolsAvailable, pluginsInstalled = pluginsInstalled,
        accessibilityActive = accessibilityActive, notificationAccessActive = notificationAccessActive,
    )

    private val nativeDocumentDrafts = NativeV6DocumentDraftPort()
    internal val documentForms = NativeV6DocumentFormState(
        savedStateHandle.get<ArrayList<String>>("v6_document_forms").orEmpty(),
    ) { savedStateHandle["v6_document_forms"] = it }
    private val _documentRoute = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    internal val documentRoute: kotlinx.coroutines.flow.StateFlow<String?> = _documentRoute
    private val documentSceneNames = setOf("route.home", "route.persona_gallery", "route.diary",
        "route.workspace", "route.tasks", "route.tools", "route.settings")
    internal val interactiveDocuments = documentFactory.create(
        viewModelScope, nativeDocumentDrafts,
        documentSceneNames.map { com.labteto.dshmobile.uicontract.UiSceneId(it) }.toSet(),
    ) { scene -> _documentRoute.value = scene.value }
    internal val nativeDocuments = NativeV6Documents(interactiveDocuments, conversationPorts, nativeDocumentDrafts, documentForms, documentMedia)
    internal fun documentIdentity() = conversationPorts.identitySnapshot()
    internal fun activeDocumentMessageId(): String? = nativeV6SourceAssistantMessageId(state.value.messages)
    internal fun consumeDocumentRoute(route: String) {
        if (_documentRoute.value == route) _documentRoute.value = null
    }
    internal fun consumePreparedDocumentDraft(sessionId: String): String? =
        nativeDocuments.consumePreparedDraft(sessionId)

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
    internal val usageRevision = usageUi.revision
    internal fun sessionUsage(sessionId: String) = usageUi.sessionSnapshot(sessionId)
    internal fun taskUsage(runId: String) = usageUi.taskDetail(runId)
    internal fun turnUsageSummaries(sessionId: String) = usageUi.turnSummaries(sessionId)
    internal fun turnUsage(sessionId: String, turnId: String) = usageUi.turnDetail(sessionId, turnId)
    internal fun requestUsage(requestId: String) = usageUi.requestDetail(requestId)
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
        // The full runtime/model window is not the foreground history page.
        liveMessages = {
            state.value.messages.takeLast(LOCAL_TRANSCRIPT_INITIAL_VISIBLE_DIALOGUE_MESSAGES)
        },
        scope = viewModelScope,
    )
    internal val transcriptHistory = transcriptHistoryController.history
    internal suspend fun previewSession(sessionId: String): List<com.labteto.dshmobile.local.session.LocalHarnessMessage> =
        sessionUi.previewMessages(sessionId)
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
    suspend fun autoFillNewPersona(current: PersonaProfile, description: String): Result<PersonaProfile> =
        personaGalleryController.autoFillNewPersona(current, description)
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
    suspend fun editGalleryStoryDetails(id: String, storyId: String, notes: String, storyStage: String): Result<Unit> =
        personaGalleryController.editGalleryStoryDetails(id, storyId, notes, storyStage)
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
    ): LocalSendResult = runtime.work.sendWithTeam(text, attachments)

    suspend fun generateReplySuggestions(): Boolean = runtime.chat.generateReplySuggestions()
    internal suspend fun diaryEntries(subjectKey: String): List<ChatDiaryEntry> =
        withContext(Dispatchers.IO) { runtime.chat.diaryEntries(subjectKey) }
    internal suspend fun correctDiaryEntry(
        subjectKey: String, id: String, revision: Long, correction: ChatDiaryDelta,
    ): Boolean = withContext(Dispatchers.IO) {
        runtime.chat.correctDiaryEntry(subjectKey, id, revision, correction)
    }
    internal suspend fun deactivateDiaryEntry(subjectKey: String, id: String, revision: Long): Boolean =
        withContext(Dispatchers.IO) { runtime.chat.deactivateDiaryEntry(subjectKey, id, revision) }
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

    suspend fun editAndResendUserMessage(messageId: String, text: String): LocalChatUserEditResult {
        val result = withContext(Dispatchers.IO) {
            when (state.value.usageMode) {
                LocalUsageMode.WORK -> runtime.work.editAndResendUserMessage(messageId, text)
                LocalUsageMode.CHAT -> runtime.chat.editAndResendUserMessage(messageId, text)
            }
        }
        if (result == LocalChatUserEditResult.SENT ||
            result == LocalChatUserEditResult.COMMITTED_NOT_STARTED
        ) {
            // Both outcomes already changed the durable active timeline.
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
    suspend fun workspaceDirectory(path: String) = runtime.session.workspaceDirectoryForUi(path)
    suspend fun conversationFiles(sessionId: String) = runtime.session.conversationFilesForUi(sessionId)
    suspend fun previewWorkspaceFile(path: String) = runtime.session.previewWorkspaceFileForUi(path)
    internal fun artifactRevisionForUi(sessionId: String) = runtime.work.artifactRevisionForUi(sessionId)
    internal fun refreshArtifactFilesForUi(sessionId: String, items: List<com.labteto.dshmobile.local.presentation.LocalArtifactUiItem>) = runtime.work.refreshArtifactFilesForUi(sessionId, items)
    internal fun inspectArtifactForUi(sessionId: String, item: com.labteto.dshmobile.local.presentation.LocalArtifactUiItem) = runtime.work.inspectArtifactForUi(sessionId, item)
    fun artifactsForUi(sessionId: String) = runtime.work.artifactsForUi(sessionId)
    fun historyPageForUi(sessionId: String, cursor: com.labteto.dshmobile.local.presentation.LocalWorkHistoryCursor?) =
        runtime.work.historyPageForUi(sessionId, cursor)

    fun artifactHistoryForUi(sessionId: String, eventLimit: Int) =
        runtime.work.artifactsForUi(sessionId, eventLimit)
    fun toolActivitiesForUi(sessionId: String) = runtime.work.toolActivitiesForUi(sessionId)
    fun toolEvidenceForUi(sessionId: String, callId: String, sequence: Long) =
        runtime.work.toolEvidenceForUi(sessionId, callId, sequence)
    internal fun requirementEvidenceForUi(sessionId: String) = runtime.work.requirementEvidenceForUi(sessionId)
    fun linkRequirementEvidenceForUi(
        sessionId: String, target: com.labteto.dshmobile.local.presentation.LocalRequirementUiTarget,
        artifact: com.labteto.dshmobile.local.presentation.LocalArtifactUiItem,
    ): Boolean {
        if (runtime.state.value.work.goal?.id != target.goalId) return false
        val prepared = runtime.work.prepareRequirementEvidenceForUi(sessionId, target.index, target.content, artifact) ?: return false
        return runtime.work.commitRequirementEvidenceForUi(prepared)
    }
    fun eventSequenceForUi(sessionId: String) = runtime.work.eventSequenceForUi(sessionId)
    /** Resolve the current binding before every team action; never reuse a stale job identity. */
    internal fun teamMemberOutput(jobId: String): String {
        val snapshot = teamUi.snapshot()
        val identity = snapshot.identity ?: return ""
        val member = snapshot.members.firstOrNull { it.jobId == jobId } ?: return ""
        return teamUi.readOutput(identity, member.id, member.jobId).message
    }

    private fun teamActionResult(result: com.labteto.dshmobile.uicontract.UiWorkTeamResult): LocalWorkUiActionResult {
        val accepted = result.receipt is com.labteto.dshmobile.uicontract.UiCommandReceipt.Accepted ||
            result.receipt is com.labteto.dshmobile.uicontract.UiCommandReceipt.Queued
        return LocalWorkUiActionResult(accepted, result.message)
    }

    internal suspend fun sendTeamMemberMessage(memberId: String, message: String): LocalWorkUiActionResult {
        val snapshot = teamUi.snapshot()
        val identity = snapshot.identity ?: return LocalWorkUiActionResult(false, "会话已变化")
        val member = snapshot.members.firstOrNull { it.id == memberId }
            ?: return LocalWorkUiActionResult(false, "团队成员已变化")
        return teamActionResult(teamUi.submit(
            com.labteto.dshmobile.uicontract.WorkTeamUiCommand.SendMessage(identity, memberId, member.jobId, message)
        ))
    }

    internal suspend fun stopTeamMember(memberId: String): LocalWorkUiActionResult {
        val snapshot = teamUi.snapshot()
        val identity = snapshot.identity ?: return LocalWorkUiActionResult(false, "会话已变化")
        val member = snapshot.members.firstOrNull { it.id == memberId }
            ?: return LocalWorkUiActionResult(false, "团队成员已变化")
        return teamActionResult(teamUi.submit(
            com.labteto.dshmobile.uicontract.WorkTeamUiCommand.StopMember(identity, memberId, member.jobId)
        ))
    }

    internal suspend fun stopTeam(): LocalWorkUiActionResult {
        val snapshot = teamUi.snapshot()
        val identity = snapshot.identity ?: return LocalWorkUiActionResult(false, "会话已变化")
        return teamActionResult(teamUi.submit(
            com.labteto.dshmobile.uicontract.WorkTeamUiCommand.StopTeam(identity, snapshot.members.map { it.jobId }.toSet())
        ))
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

    suspend fun autoFillChatPersona(current: PersonaProfile, description: String): Result<PersonaProfile> =
        chatModelAssistController.autoFillCurrentPersona(current, description)
    fun switchSession(sessionId: String) = runtime.session.switchSession(sessionId)
    fun clearCredential() = runtime.model.clearCredential()
}

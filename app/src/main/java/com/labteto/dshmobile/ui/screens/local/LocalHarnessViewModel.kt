package com.labteto.dshmobile.ui.screens.local

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.labteto.dshmobile.local.LocalUsageMode
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel
class LocalHarnessViewModel @Inject constructor(
    private val documentsFactory: com.labteto.dshmobile.local.presentation.LocalUiDocumentUiPortFactory,
    private val savedStateHandle: androidx.lifecycle.SavedStateHandle,
    private val runtime: LocalUiRuntime,
    val workEvidenceUiPort: com.labteto.dshmobile.uicontract.WorkEvidenceUiPort,
    val workTeamUiPort: com.labteto.dshmobile.uicontract.WorkTeamUiPort,
    val workReadUiPort: com.labteto.dshmobile.uicontract.WorkReadUiPort,
    val filesUiPort: com.labteto.dshmobile.uicontract.FilesUiPort,
    val attachmentUiPort: com.labteto.dshmobile.uicontract.AttachmentUiPort,
    val chatGroupUiPort: com.labteto.dshmobile.uicontract.ChatGroupUiPort,
    val personaGalleryUiPort: com.labteto.dshmobile.uicontract.PersonaGalleryUiPort,
    val personaSelectionUiPort: com.labteto.dshmobile.uicontract.PersonaSelectionUiPort,
    val personaTransferUiPort: com.labteto.dshmobile.uicontract.PersonaTransferUiPort,
    val personaStoryUiPort: com.labteto.dshmobile.uicontract.PersonaStoryUiPort,
    val personaGalleryDeletionUiPort: com.labteto.dshmobile.uicontract.PersonaGalleryDeletionUiPort,
    val personaGallerySaveUiPort: com.labteto.dshmobile.uicontract.PersonaGallerySaveUiPort,
    val personaPortraitUiPort: com.labteto.dshmobile.uicontract.PersonaPortraitUiPort,
    private val uiPorts: com.labteto.dshmobile.local.LocalConversationUiPortComposition,
    private val chatUi: LocalChatUiFacade,
    private val businessSessions: com.labteto.dshmobile.uicontract.SessionUiPort,
    val transcriptUiPort: com.labteto.dshmobile.uicontract.TranscriptUiPort,
    val messageHistoryUiPort: com.labteto.dshmobile.uicontract.UiMessageHistoryPort,
    private val toolsUi: LocalToolsUiFacade,
    private val usageUi: com.labteto.dshmobile.uicontract.UsageUiPort,
    val workPolicyUiPort: com.labteto.dshmobile.uicontract.WorkPolicyUiPort,
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

    val approvalMode = workPolicyUiPort.snapshots.map { it.approvalMode }.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000), workPolicyUiPort.snapshot().approvalMode)
    val networkSearchEnabled = networkSearchSettings.enabled
    fun setNetworkSearchEnabled(enabled: Boolean) = networkSearchSettings.setEnabled(enabled)
    val chatUiPort: com.labteto.dshmobile.uicontract.ChatUiPort = uiPorts.chat
    val workConversationUiPort: com.labteto.dshmobile.uicontract.WorkConversationUiPort = uiPorts.work
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
        galleryPort = personaGalleryUiPort,
        selectionPort = personaSelectionUiPort,
        transferPort = personaTransferUiPort,
        savePort = personaGallerySaveUiPort,
        portraitPort = personaPortraitUiPort,
        deletionPort = personaGalleryDeletionUiPort,
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
        session = transcriptUiPort,
        currentSessionId = { state.value.sessionId },
        liveMessages = { state.value.messages },
        scope = viewModelScope,
    )
    internal val transcriptHistory = transcriptHistoryController.history
    val conversationDraftUiPort: com.labteto.dshmobile.uicontract.ConversationDraftUiPort =
        LocalSessionDraftCache.restore(savedStateHandle.get<List<String>>("conversation_drafts").orEmpty()) {
            savedStateHandle["conversation_drafts"] = ArrayList(it)
        }.apply {
            restoreAttachments(savedStateHandle.get<List<String>>("conversation_attachment_drafts").orEmpty()) {
                savedStateHandle["conversation_attachment_drafts"] = ArrayList(it)
            }
        }

    private val readingState = LocalConversationReadingState(savedStateHandle.get<List<String>>("conversation_reading").orEmpty()) {
            savedStateHandle["conversation_reading"] = ArrayList(it)
        }

    val conversationReadingUiPort: com.labteto.dshmobile.uicontract.ConversationReadingUiPort get() = readingState
    private val formState = com.labteto.dshmobile.ui.host.LocalUiDocumentFormState(
            savedStateHandle.get<List<String>>("ui_document_forms").orEmpty(),
        ) { savedStateHandle["ui_document_forms"] = it }
    val uiDocumentFormPort: com.labteto.dshmobile.uicontract.UiDocumentFormUiPort get() = formState
    val sessionUiPort: com.labteto.dshmobile.uicontract.SessionUiPort by lazy {
        com.labteto.dshmobile.ui.host.HostConversationSessionUiPort(businessSessions, conversationDraftUiPort,
            conversationUiState, readingState, formState)
    }
    val uiSceneRequest = savedStateHandle.getStateFlow<String?>("ui_scene_request", null)
    fun consumeUiSceneRequest(id: String) {
        if (uiSceneRequest.value == id) savedStateHandle["ui_scene_request"] = null
    }
    private val documentNavigationScenes = com.labteto.dshmobile.ui.host.LocalUiSceneCatalog.requirements
        .filter { it.kind == com.labteto.dshmobile.uicontract.UiSceneKind.PAGE &&
            (it.id.value.startsWith("route.") || it.id.value.startsWith("settings.")) }.map { it.id }.toSet()
    val uiDocumentUiPort: com.labteto.dshmobile.uicontract.UiDocumentUiPort = documentsFactory.create(
        viewModelScope, conversationDraftUiPort, documentNavigationScenes,
    ) { scene -> savedStateHandle["ui_scene_request"] = scene.value }
    // The independent conversation slice cannot navigate to unimplemented pages.
    // It reads the same historical documents but explicitly disables those actions.
    val nextUiDocumentUiPort: com.labteto.dshmobile.uicontract.UiDocumentUiPort = documentsFactory.create(
        viewModelScope, conversationDraftUiPort, documentNavigationScenes, navigationScenes = emptySet(),
    ) { _ -> error("No navigation scenes are registered for this slice") }

    private val conversationUiState = LocalConversationUiStateStore(appContext, businessSessions)
    val pinnedSessionIds = conversationUiState.pinnedSessionIds
    val sessionTitleOverrides = conversationUiState.sessionTitleOverrides
    suspend fun saveCurrentToGallery(
        notes: String,
        existingId: String? = null,
        existingStoryId: String? = null,
        forceNewStory: Boolean = false,
        expected: com.labteto.dshmobile.uicontract.UiSessionIdentity?,
    ): Result<PersonaGalleryEntry> =
        personaGalleryController.saveCurrentToGallery(notes, existingId, existingStoryId, forceNewStory, expected)
    fun hasUnsavedCurrentPersona(): Boolean = personaGalleryController.hasUnsavedCurrentPersona()
    fun currentGalleryNeedsUpdate(): Boolean = personaGalleryController.currentGalleryNeedsUpdate()
    fun currentGalleryHasUnsavedChanges(): Boolean = personaGalleryController.currentGalleryHasUnsavedChanges()
    fun selectGalleryPersonaForCurrentChat(id: String, expected: com.labteto.dshmobile.uicontract.UiSessionIdentity?): Boolean =
        personaGalleryController.selectGalleryPersonaForCurrentChat(id, expected)
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
    suspend fun refreshGalleryForUi() = personaGalleryController.refresh()
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
    suspend fun deleteGalleryHistoryMessage(
        id: String,
        storyId: String,
        messageKey: String,
    ): Result<Unit> = personaGalleryController.deleteGalleryHistoryMessage(id, storyId, messageKey)
    fun startFromGallery(id: String, storyId: String?, freshStory: Boolean, expected: com.labteto.dshmobile.uicontract.UiSessionIdentity?): Boolean =
        personaGalleryController.startFromGallery(id, storyId, freshStory, expected)

    internal suspend fun prepareTranscriptHistory(
        sessionId: String,
        force: Boolean = false,
    ) = transcriptHistoryController.prepare(sessionId, force)

    internal suspend fun loadOlderTranscript(sessionId: String): Result<Int> =
        transcriptHistoryController.loadOlder(sessionId)

    fun configure(apiKey: String, model: String, baseUrl: String) = runtime.model.configure(apiKey, model, baseUrl)
    fun selectModel(model: String) = runtime.model.selectModel(model)
    internal fun captureUiSendIdentity(): com.labteto.dshmobile.uicontract.UiSessionIdentity? = uiPorts.identitySnapshot()

    internal fun send(
        text: String,
        attachments: List<LocalImportedAttachment>,
        identity: com.labteto.dshmobile.uicontract.UiSessionIdentity?,
        mode: LocalUsageMode,
    ): LocalSendResult {
        val expected = identity ?: return LocalSendResult.rejected(com.labteto.dshmobile.local.send.LocalSendRejectReason.SESSION_TRANSITION)
        val receipt = when (mode) {
            LocalUsageMode.CHAT -> chatUiPort.submit(com.labteto.dshmobile.uicontract.ChatUiCommand.SendMessage(expected, text, attachments))
            LocalUsageMode.WORK -> workConversationUiPort.submit(com.labteto.dshmobile.uicontract.WorkConversationUiCommand.SendMessage(expected, text, attachments))
        }
        return receipt.toLegacySendResult()
    }
    internal fun sendWithTeam(text: String, attachments: List<LocalImportedAttachment>, identity: com.labteto.dshmobile.uicontract.UiSessionIdentity?): LocalSendResult {
        val expected = identity ?: return LocalSendResult.rejected(com.labteto.dshmobile.local.send.LocalSendRejectReason.SESSION_TRANSITION)
        return workConversationUiPort.submit(com.labteto.dshmobile.uicontract.WorkConversationUiCommand.SendWithTeam(expected, text, attachments)).toLegacySendResult()
    }

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
    fun createGroupChatSession(ids: List<String>, expected: com.labteto.dshmobile.uicontract.UiSessionIdentity?): Boolean {
        val identity = expected ?: return false
        return chatGroupUiPort.submit(com.labteto.dshmobile.uicontract.ChatGroupUiCommand.CreateGroupSession(identity, ids)) ==
            com.labteto.dshmobile.uicontract.UiCommandReceipt.Accepted
    }
    fun createSingleChatSession() = runtime.chat.createSingleChatSession()
    fun configureGroupChatMembers(ids: List<String>, expected: com.labteto.dshmobile.uicontract.UiSessionIdentity?): Boolean {
        // V6 and Next now submit the same closed intent to the original Chat Owner.
        val identity = expected ?: return false
        val receipt = chatGroupUiPort.submit(
            com.labteto.dshmobile.uicontract.ChatGroupUiCommand.ConfigureMembers(identity, ids)
        )
        return receipt == com.labteto.dshmobile.uicontract.UiCommandReceipt.Accepted ||
            receipt == com.labteto.dshmobile.uicontract.UiCommandReceipt.Queued
    }
    suspend fun setGroupChatAnnouncement(text: String, expected: com.labteto.dshmobile.uicontract.UiSessionIdentity?): Result<Unit> = withContext(Dispatchers.IO) {
        val identity = expected ?: return@withContext Result.failure(IllegalStateException("会话已改变，请重新打开群公告"))
        when (val receipt = chatGroupUiPort.submit(com.labteto.dshmobile.uicontract.ChatGroupUiCommand.SaveAnnouncement(identity, text))) {
            com.labteto.dshmobile.uicontract.UiCommandReceipt.Accepted -> Result.success(Unit)
            is com.labteto.dshmobile.uicontract.UiCommandReceipt.Failed -> Result.failure(IllegalStateException(receipt.message))
            else -> Result.failure(IllegalStateException("群公告未保存，请确认会话和运行状态后重试"))
        }
    }

    suspend fun editAndResendUserMessage(messageId: String, text: String, expected: com.labteto.dshmobile.uicontract.UiSessionIdentity?, mode: LocalUsageMode): LocalChatUserEditResult {
        val identity = expected ?: return LocalChatUserEditResult.UNAVAILABLE
        val result = withContext(Dispatchers.IO) {
            val receipt = when (mode) {
                LocalUsageMode.WORK -> workConversationUiPort.submit(com.labteto.dshmobile.uicontract.WorkConversationUiCommand.EditUserMessage(identity, messageId, text))
                LocalUsageMode.CHAT -> chatUiPort.submit(com.labteto.dshmobile.uicontract.ChatUiCommand.EditUserMessage(identity, messageId, text))
            }
            when (receipt) {
                is com.labteto.dshmobile.uicontract.UiCommandReceipt.MessageEdited -> receipt.result.toLegacyMessageEditResult()
                else -> LocalChatUserEditResult.UNAVAILABLE
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

    suspend fun selectChatMessageVariant(messageId: String, targetIndex: Int, identity: com.labteto.dshmobile.uicontract.UiSessionIdentity?): Boolean {
        val expected = identity ?: return false
        val selected = withContext(Dispatchers.IO) {
            chatUiPort.submit(com.labteto.dshmobile.uicontract.ChatUiCommand.SelectVariant(expected, messageId, targetIndex)) == com.labteto.dshmobile.uicontract.UiCommandReceipt.Accepted
        }
        if (selected) refreshTranscriptHistoryAfterTimelineRewrite()
        return selected
    }

    private fun refreshTranscriptHistoryAfterTimelineRewrite() =
        transcriptHistoryController.refreshAfterTimelineRewrite()
    fun regenerateReply(messageId: String, expected: com.labteto.dshmobile.uicontract.UiSessionIdentity?, mode: LocalUsageMode): Boolean {
        val identity = expected ?: return false
        val receipt = when (mode) {
            LocalUsageMode.WORK -> workConversationUiPort.submit(com.labteto.dshmobile.uicontract.WorkConversationUiCommand.RegenerateReply(identity, messageId))
            LocalUsageMode.CHAT -> chatUiPort.submit(com.labteto.dshmobile.uicontract.ChatUiCommand.RegenerateReply(identity, messageId))
        }
        return receipt == com.labteto.dshmobile.uicontract.UiCommandReceipt.Accepted
    }
    val conversationShellUiPort: com.labteto.dshmobile.uicontract.ConversationShellUiPort get() = conversationUiState
    fun toggleSessionPinned(sessionId: String) {
        conversationShellUiPort.submit(com.labteto.dshmobile.uicontract.ConversationShellUiCommand.SetPinned(sessionId,
            sessionId !in conversationShellUiPort.pinnedSessionIds.value))
    }
    fun renameSession(sessionId: String, title: String): Boolean = conversationShellUiPort.submit(
        com.labteto.dshmobile.uicontract.ConversationShellUiCommand.Rename(sessionId, title)) == com.labteto.dshmobile.uicontract.UiCommandReceipt.Accepted
    suspend fun deleteSessions(ids: Set<String>): Int {
        val receipt = sessionUiPort.delete(ids)
        val deleted = when (receipt) {
            is com.labteto.dshmobile.uicontract.UiSessionDeletionReceipt.Deleted -> receipt.sessionIds
            is com.labteto.dshmobile.uicontract.UiSessionDeletionReceipt.Failed -> error(receipt.message ?: "会话删除未完成，请重新读取列表")
        }
        return deleted.size
    }
    suspend fun importAttachment(uri: Uri, identity: com.labteto.dshmobile.uicontract.UiSessionIdentity?): LocalImportedAttachment {
        val expected = identity ?: error("会话已改变，请重新选择附件")
        return when (val receipt = attachmentUiPort.import(com.labteto.dshmobile.uicontract.ImportUiAttachmentCommand(expected, uri.toString()))) {
            is com.labteto.dshmobile.uicontract.UiAttachmentReceipt.Imported -> receipt.attachment
            is com.labteto.dshmobile.uicontract.UiAttachmentReceipt.Rejected -> error("会话已改变，请重新选择附件")
            is com.labteto.dshmobile.uicontract.UiAttachmentReceipt.Failed -> error(receipt.message ?: "附件导入失败")
        }
    }
    suspend fun workspaceFiles() = filesUiPort.workspaceFiles()
    suspend fun conversationFiles(sessionId: String) = filesUiPort.conversationFiles(sessionId)
    suspend fun previewWorkspaceFile(path: String) = filesUiPort.previewWorkspaceFile(path)
    fun artifactsForUi(sessionId: String) = workReadUiPort.artifactsForUi(sessionId)
    fun historyPageForUi(sessionId: String, cursor: com.labteto.dshmobile.local.presentation.LocalWorkHistoryCursor?) =
        workReadUiPort.historyPageForUi(sessionId, cursor)

    fun artifactHistoryForUi(sessionId: String, eventLimit: Int) =
        workReadUiPort.artifactsForUi(sessionId, eventLimit)
    fun toolActivitiesForUi(sessionId: String) = workReadUiPort.toolActivitiesForUi(sessionId)
    fun toolEvidenceForUi(sessionId: String, callId: String, sequence: Long) =
        workReadUiPort.toolEvidenceForUi(sessionId, callId, sequence)
    internal fun requirementEvidenceForUi(sessionId: String) = workReadUiPort.requirementEvidenceForUi(sessionId)
    fun eventSequenceForUi(sessionId: String) = workReadUiPort.eventSequenceForUi(sessionId)
    internal fun teamMemberOutput(jobId: String, expected: com.labteto.dshmobile.uicontract.UiSessionIdentity?, memberId: String?): String {
        if (expected == null || memberId == null) return "助手执行身份已改变"
        return workTeamUiPort.readOutput(expected, memberId, jobId).message
    }
    internal suspend fun sendTeamMemberMessage(memberId: String, message: String,
        expected: com.labteto.dshmobile.uicontract.UiSessionIdentity?, jobId: String?): LocalWorkUiActionResult {
        if (expected == null || jobId == null) return LocalWorkUiActionResult(false, "助手执行身份已改变")
        val result = workTeamUiPort.submit(com.labteto.dshmobile.uicontract.WorkTeamUiCommand.SendMessage(expected, memberId, jobId, message))
        return LocalWorkUiActionResult(result.receipt == com.labteto.dshmobile.uicontract.UiCommandReceipt.Accepted, result.message)
    }
    internal suspend fun stopTeamMember(memberId: String, expected: com.labteto.dshmobile.uicontract.UiSessionIdentity?, jobId: String?): LocalWorkUiActionResult {
        if (expected == null || jobId == null) return LocalWorkUiActionResult(false, "助手执行身份已改变")
        val result = workTeamUiPort.submit(com.labteto.dshmobile.uicontract.WorkTeamUiCommand.StopMember(expected, memberId, jobId))
        return LocalWorkUiActionResult(result.receipt == com.labteto.dshmobile.uicontract.UiCommandReceipt.Accepted, result.message)
    }
    internal suspend fun stopTeam(expected: com.labteto.dshmobile.uicontract.UiSessionIdentity?, jobs: Set<String>): LocalWorkUiActionResult {
        if (expected == null) return LocalWorkUiActionResult(false, "会话已改变")
        val result = workTeamUiPort.submit(com.labteto.dshmobile.uicontract.WorkTeamUiCommand.StopTeam(expected, jobs))
        return LocalWorkUiActionResult(result.receipt == com.labteto.dshmobile.uicontract.UiCommandReceipt.Accepted, result.message)
    }
    internal fun answerUiApproval(
        pendingId: String,
        identity: com.labteto.dshmobile.uicontract.UiSessionIdentity?,
        approved: Boolean,
    ): com.labteto.dshmobile.uicontract.UiCommandReceipt =
        identity?.let {
            workConversationUiPort.submit(com.labteto.dshmobile.uicontract.WorkConversationUiCommand.AnswerApproval(
                it, pendingId, approved,
            ))
        } ?: com.labteto.dshmobile.uicontract.UiCommandReceipt.Rejected(
            com.labteto.dshmobile.uicontract.UiCommandRejection.STALE_IDENTITY,
        )
    fun answerQuestion(pendingId: String, answer: String?, identity: com.labteto.dshmobile.uicontract.UiSessionIdentity?): Boolean =
        identity?.let { workConversationUiPort.submit(com.labteto.dshmobile.uicontract.WorkConversationUiCommand.AnswerQuestion(it, pendingId, answer)) } ==
            com.labteto.dshmobile.uicontract.UiCommandReceipt.Accepted
    fun stop(expected: com.labteto.dshmobile.uicontract.UiSessionIdentity?, mode: LocalUsageMode) {
        val identity = expected ?: return
        when (mode) {
            LocalUsageMode.CHAT -> chatUiPort.submit(com.labteto.dshmobile.uicontract.ChatUiCommand.Stop(identity))
            LocalUsageMode.WORK -> workConversationUiPort.submit(com.labteto.dshmobile.uicontract.WorkConversationUiCommand.Stop(identity))
        }
    }
    internal fun workHandoffSummary(): String = captureUiSendIdentity()?.let(sessionUiPort::handoffSummary).orEmpty()
    internal fun createWorkContinuation(sourceSessionId: String, summary: String, identity: com.labteto.dshmobile.uicontract.UiSessionIdentity?): Boolean {
        if (state.value.sessionId != sourceSessionId || state.value.usageMode != LocalUsageMode.CHAT) return false
        return sessionUiPort.submit(com.labteto.dshmobile.uicontract.SessionUiCommand.Create(
            identity, LocalConversationMode.CONTINUATION, LocalUsageMode.WORK, summary)) == com.labteto.dshmobile.uicontract.UiCommandReceipt.Accepted
    }
    fun newSession() = createSession(LocalConversationMode.INDEPENDENT, state.value.usageMode, captureUiSendIdentity())
    fun createSession(mode: LocalConversationMode, usageMode: LocalUsageMode, identity: com.labteto.dshmobile.uicontract.UiSessionIdentity?): Boolean = sessionUiPort.submit(com.labteto.dshmobile.uicontract.SessionUiCommand.Create(
        identity, mode, usageMode)) == com.labteto.dshmobile.uicontract.UiCommandReceipt.Accepted
    fun switchUsageMode(mode: LocalUsageMode) { sessionUiPort.submit(com.labteto.dshmobile.uicontract.SessionUiCommand.SelectMode(mode)) }
    suspend fun configureChatPersona(profile: PersonaProfile): Result<Unit> = personaGalleryController.configureChatPersona(profile)
    fun undoChatPersonaCorrection(noticeId: Long, personaId: String, correction: String) {
        viewModelScope.launch {
            runtime.chat.undoChatPersonaCorrection(noticeId, personaId, correction)
        }
    }

    suspend fun autoFillChatPersona(description: String): Result<PersonaProfile> =
        chatModelAssistController.autoFillCurrentPersona(description)
    fun switchSession(sessionId: String): Boolean = sessionUiPort.submit(com.labteto.dshmobile.uicontract.SessionUiCommand.Open(sessionId)) == com.labteto.dshmobile.uicontract.UiCommandReceipt.Queued
    fun clearCredential() = runtime.model.clearCredential()
}

/**
 * V6 compatibility only. Next uses the neutral UiMessageEditOutcome and does not import
 * the Session-domain enum. Exhaustive translation preserves durable-edit partial success.
 */
private fun com.labteto.dshmobile.uicontract.UiMessageEditOutcome.toLegacyMessageEditResult():
    com.labteto.dshmobile.local.session.LocalUserMessageEditResult = when (this) {
    com.labteto.dshmobile.uicontract.UiMessageEditOutcome.SENT -> com.labteto.dshmobile.local.session.LocalUserMessageEditResult.SENT
    com.labteto.dshmobile.uicontract.UiMessageEditOutcome.COMMITTED_NOT_STARTED -> com.labteto.dshmobile.local.session.LocalUserMessageEditResult.COMMITTED_NOT_STARTED
    com.labteto.dshmobile.uicontract.UiMessageEditOutcome.BUSY -> com.labteto.dshmobile.local.session.LocalUserMessageEditResult.BUSY
    com.labteto.dshmobile.uicontract.UiMessageEditOutcome.UNAVAILABLE -> com.labteto.dshmobile.local.session.LocalUserMessageEditResult.UNAVAILABLE
    com.labteto.dshmobile.uicontract.UiMessageEditOutcome.WRONG_MODE -> com.labteto.dshmobile.local.session.LocalUserMessageEditResult.WRONG_MODE
    com.labteto.dshmobile.uicontract.UiMessageEditOutcome.UNCONFIGURED -> com.labteto.dshmobile.local.session.LocalUserMessageEditResult.UNCONFIGURED
    com.labteto.dshmobile.uicontract.UiMessageEditOutcome.HISTORY_UNAVAILABLE -> com.labteto.dshmobile.local.session.LocalUserMessageEditResult.HISTORY_UNAVAILABLE
    com.labteto.dshmobile.uicontract.UiMessageEditOutcome.MESSAGE_MISSING -> com.labteto.dshmobile.local.session.LocalUserMessageEditResult.MESSAGE_MISSING
    com.labteto.dshmobile.uicontract.UiMessageEditOutcome.EMPTY -> com.labteto.dshmobile.local.session.LocalUserMessageEditResult.EMPTY
    com.labteto.dshmobile.uicontract.UiMessageEditOutcome.UNCHANGED -> com.labteto.dshmobile.local.session.LocalUserMessageEditResult.UNCHANGED
    com.labteto.dshmobile.uicontract.UiMessageEditOutcome.FAILED -> com.labteto.dshmobile.local.session.LocalUserMessageEditResult.FAILED
}

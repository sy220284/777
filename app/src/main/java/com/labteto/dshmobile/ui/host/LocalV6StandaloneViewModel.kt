package com.labteto.dshmobile.ui.host

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.labteto.dshmobile.local.LocalConversationUiPortComposition
import com.labteto.dshmobile.local.presentation.LocalToolsUiFacade
import com.labteto.dshmobile.local.presentation.LocalUiDocumentUiPortFactory
import com.labteto.dshmobile.uicontract.*
import com.labteto.dshmobile.uipack.v6.*
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * Standalone V6 composition root, independent of LocalHarnessViewModel,
 * LocalHarnessScreen and old Shell business callback dispatch. Hilt injects the
 * SAME feature Ports used by legacy, not parallel business state machines.
 * Retained UI-only draft, reader and form projections are owned here.
 */
@HiltViewModel
internal class LocalV6StandaloneViewModel @Inject constructor(
    private val documentsFactory: LocalUiDocumentUiPortFactory,
    private val savedStateHandle: SavedStateHandle,
    private val commands: LocalConversationUiPortComposition,
    private val businessSessions: SessionUiPort,
    private val toolsFacade: LocalToolsUiFacade,
    @ApplicationContext private val context: Context,
    val workEvidenceUiPort: com.labteto.dshmobile.uicontract.WorkEvidenceUiPort,
    val workTeamUiPort: com.labteto.dshmobile.uicontract.WorkTeamUiPort,
    val workReadUiPort: com.labteto.dshmobile.uicontract.WorkReadUiPort,
    val filesUiPort: com.labteto.dshmobile.uicontract.FilesUiPort,
    val automationUiPort: com.labteto.dshmobile.uicontract.AutomationUiPort,
    val mediaUiPort: com.labteto.dshmobile.uicontract.MediaUiPort,
    val diaryUiPort: com.labteto.dshmobile.uicontract.DiaryUiPort,
    val hostPreferencesUiPort: com.labteto.dshmobile.uicontract.HostPreferencesUiPort,
    val replySuggestionsUiPort: com.labteto.dshmobile.uicontract.ReplySuggestionsUiPort,
    val attachmentUiPort: com.labteto.dshmobile.uicontract.AttachmentUiPort,
    val chatGroupUiPort: com.labteto.dshmobile.uicontract.ChatGroupUiPort,
    val personaGalleryUiPort: com.labteto.dshmobile.uicontract.PersonaGalleryUiPort,
    val personaSelectionUiPort: com.labteto.dshmobile.uicontract.PersonaSelectionUiPort,
    val personaTransferUiPort: com.labteto.dshmobile.uicontract.PersonaTransferUiPort,
    val personaStoryUiPort: com.labteto.dshmobile.uicontract.PersonaStoryUiPort,
    val personaGalleryDeletionUiPort: com.labteto.dshmobile.uicontract.PersonaGalleryDeletionUiPort,
    val personaGallerySaveUiPort: com.labteto.dshmobile.uicontract.PersonaGallerySaveUiPort,
    val memoryUiPort: com.labteto.dshmobile.uicontract.MemoryUiPort,
    val settingsUiPort: com.labteto.dshmobile.uicontract.SettingsUiPort,
    val currentPersonaUiPort: com.labteto.dshmobile.uicontract.CurrentPersonaUiPort,
    val personaAuthoringUiPort: com.labteto.dshmobile.uicontract.PersonaAuthoringUiPort,
    val personaPortraitUiPort: com.labteto.dshmobile.uicontract.PersonaPortraitUiPort,
    val messageHistoryUiPort: com.labteto.dshmobile.uicontract.UiMessageHistoryPort,
    val usageUiPort: com.labteto.dshmobile.uicontract.UsageUiPort,
    val workPolicyUiPort: com.labteto.dshmobile.uicontract.WorkPolicyUiPort,
) : ViewModel() {
    private val secureDrafts = LocalEncryptedUiDraftStore(context)
    init {
        secureDrafts.migrateOldSavedStateOnce("conversation_drafts",
            savedStateHandle.remove<List<String>>("v6_conversation_drafts"))
        secureDrafts.migrateOldSavedStateOnce("attachment_drafts",
            savedStateHandle.remove<List<String>>("v6_attachment_drafts"))
        secureDrafts.migrateOldSavedStateOnce("ui_document_forms",
            savedStateHandle.remove<List<String>>("v6_document_forms"))
    }
    val drafts: ConversationDraftUiPort =
        LocalSessionDraftCache.restore(secureDrafts.read("conversation_drafts")) {
            secureDrafts.write("conversation_drafts", it)
        }.apply {
            restoreAttachments(secureDrafts.read("attachment_drafts")) {
                secureDrafts.write("attachment_drafts", it)
            }
        }
    private val readingState = LocalConversationReadingState(
        savedStateHandle.get<List<String>>("v6_reading").orEmpty()
    ) { savedStateHandle["v6_reading"] = ArrayList(it) }
    private val formState = LocalUiDocumentFormState(
        secureDrafts.read("ui_document_forms"),
    ) { secureDrafts.write("ui_document_forms", it) }
    private val shellPreferences = LocalConversationUiStateStore(context, businessSessions)
    val sessions: SessionUiPort = HostConversationSessionUiPort(
        businessSessions, drafts, shellPreferences, readingState, formState,
    )
    val documents: UiDocumentUiPort = documentsFactory.create(
        viewModelScope, drafts,
        LocalUiSceneCatalog.requirements.filter {
            it.kind == UiSceneKind.PAGE &&
                (it.id.value.startsWith("route.") || it.id.value.startsWith("settings."))
        }.map { it.id }.toSet(),
    ) { id -> savedStateHandle["v6_scene_request"] = id.value }

    val sceneRequest = savedStateHandle.getStateFlow<String?>("v6_scene_request", null)
    fun consumeSceneRequest(value: String) {
        if (sceneRequest.value == value) savedStateHandle["v6_scene_request"] = null
    }

    private val sessionBinding = LocalV6SessionBinding(sessions)
    val conversation = V6ConversationPorts(
        chat = commands.chat, work = commands.work,
        drafts = drafts, reading = readingState,
        attachments = attachmentUiPort, history = messageHistoryUiPort,
        documents = documents, forms = formState,
        media = mediaUiPort, evidence = workEvidenceUiPort,
        gallery = personaGalleryUiPort, selection = personaSelectionUiPort,
        groups = chatGroupUiPort, stories = personaStoryUiPort,
        galleryDeletion = personaGalleryDeletionUiPort,
        personaAuthoring = personaAuthoringUiPort,
        workReads = workReadUiPort, workPolicy = workPolicyUiPort,
        workTeam = workTeamUiPort, replySuggestions = replySuggestionsUiPort,
        files = filesUiPort,
        workMode = sessionBinding::isWork,
        workModeUpdates = sessionBinding.workModeUpdates,
    )

    val secondary = V6SecondaryPorts(
        tools = toolsFacade, files = filesUiPort,
        media = mediaUiPort, settings = settingsUiPort,
        sessionId = sessionBinding::sessionId,
        automation = automationUiPort, sessions = sessions,
        transcript = messageHistoryUiPort, memory = memoryUiPort,
        diary = diaryUiPort, authoring = personaAuthoringUiPort,
        currentPersona = currentPersonaUiPort,
        replySuggestions = replySuggestionsUiPort,
        gallery = personaGalleryUiPort, selection = personaSelectionUiPort,
        portraits = personaPortraitUiPort,
        modelPerformance = LocalModelPerformanceUiOwner,
        pricing = memoryUiPort as? PricingUiPort,
    )
    val connectedCapabilities = LocalUiConnectedCapabilities.from(
        chat = commands.chat, work = commands.work, drafts = drafts,
        documents = documents, forms = formState,
        attachments = attachmentUiPort, transcript = messageHistoryUiPort,
        groups = chatGroupUiPort, team = workTeamUiPort,
        workReads = workReadUiPort, workPolicy = workPolicyUiPort,
        shell = shellPreferences, evidence = workEvidenceUiPort,
        gallery = personaGalleryUiPort, personas = personaSelectionUiPort,
        personaTransfer = personaTransferUiPort, stories = personaStoryUiPort,
        galleryDeletion = personaGalleryDeletionUiPort,
        gallerySave = personaGallerySaveUiPort, portraits = personaPortraitUiPort,
        authoring = personaAuthoringUiPort, currentPersona = currentPersonaUiPort,
        files = filesUiPort, memory = memoryUiPort, settings = settingsUiPort,
        automation = automationUiPort, media = mediaUiPort,
        usage = usageUiPort, diary = diaryUiPort,
    )

    fun pack(
        updateStatus: kotlinx.coroutines.flow.StateFlow<String?>?,
        checkForUpdate: (() -> Unit)?,
        visualFrame: @androidx.compose.runtime.Composable (@androidx.compose.runtime.Composable () -> Unit) -> Unit = { it() },
    ) = V6ApplicationScenePack(
        hostPreferencesUiPort, settingsUiPort, conversation,
        usageOwner = usageUiPort, updateStatus = updateStatus,
        checkForUpdate = checkForUpdate, secondary = secondary,
        visualFrame = visualFrame,
    )
}

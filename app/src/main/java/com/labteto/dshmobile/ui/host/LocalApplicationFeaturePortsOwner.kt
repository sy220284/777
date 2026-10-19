package com.labteto.dshmobile.ui.host

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.labteto.dshmobile.local.LocalConversationUiPortComposition
import com.labteto.dshmobile.local.presentation.LocalToolsUiFacade
import com.labteto.dshmobile.local.presentation.LocalUiDocumentUiPortFactory
import com.labteto.dshmobile.local.LocalUsageMode
import kotlinx.coroutines.flow.map
import com.labteto.dshmobile.uicontract.*
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * Neutral application Feature Port owner for the next UI installation.
 * Hilt wires the EXISTING chat/work/models/tools/persona/session owners here.
 * This has no SceneFrame, renderer, theme, icon, composable or V6 implementation.
 *
 * It preserves persisted private drafts and existing business/session identities;
 * UI packs only receive typed ports from the app composition root.
 */
@HiltViewModel
internal class LocalApplicationFeaturePortsOwner @Inject constructor(
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
    val privateTextCipher: com.labteto.dshmobile.uihost.UiPrivateTextCipher get() = secureDrafts
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
        (savedStateHandle.get<List<String>>("reading_state")
            ?: savedStateHandle.remove<List<String>>("v6_reading")).orEmpty()
    ) { savedStateHandle["reading_state"] = ArrayList(it) }
    private val formState = LocalUiDocumentFormState(
        secureDrafts.read("ui_document_forms"),
    ) { secureDrafts.write("ui_document_forms", it) }
    private val shellPreferences = LocalConversationUiStateStore(context, businessSessions)
    val conversationShellUiPort: com.labteto.dshmobile.uicontract.ConversationShellUiPort = shellPreferences
    val conversationNavigation = kotlinx.coroutines.flow.MutableStateFlow<SessionUiCommand?>(null)
    val sessions: SessionUiPort = HostConversationSessionUiPort(
        businessSessions, drafts, shellPreferences, readingState, formState,
        onConversationRequested = { conversationNavigation.value = it },
    )
    val documents: UiDocumentUiPort = documentsFactory.create(
        viewModelScope, drafts,
        LocalUiSceneCatalog.requirements.filter {
            it.kind == UiSceneKind.PAGE &&
                (it.id.value.startsWith("route.") || it.id.value.startsWith("settings."))
        }.map { it.id }.toSet(),
    ) { id -> savedStateHandle["ui_scene_request"] = id.value }

    val sceneRequest = savedStateHandle.getStateFlow<String?>("ui_scene_request", null)
    fun consumeSceneRequest(value: String) {
        if (sceneRequest.value == value) savedStateHandle["ui_scene_request"] = null
    }

    // Typed, UI-neutral read/command surfaces available to a future pack.
    val chat: ChatUiPort = commands.chat
    val work: WorkConversationUiPort = commands.work
    val tools: ToolsUiPort = toolsFacade
    val reading: ConversationReadingUiPort = readingState
    val forms: UiDocumentFormUiPort = formState
    val shell: ConversationShellUiPort = shellPreferences
    val modelPerformance = LocalModelPerformanceUiOwner
    val pricing: PricingUiPort? = memoryUiPort as? PricingUiPort
    fun currentSessionId(): String = sessions.snapshot().identity?.sessionId.orEmpty()
    fun isWorkMode(): Boolean = sessions.snapshot().usageMode == LocalUsageMode.WORK
    val workModeUpdates = sessions.snapshots.map { it.usageMode == LocalUsageMode.WORK }

    val connectedCapabilities = LocalUiConnectedCapabilities.from(
        chat = commands.chat, work = commands.work, drafts = drafts,
        documents = documents, forms = formState,
        attachments = attachmentUiPort, transcript = messageHistoryUiPort,
        groups = chatGroupUiPort, team = workTeamUiPort,
        workReads = workReadUiPort, workPolicy = workPolicyUiPort,
        shell = shellPreferences,
        gallery = personaGalleryUiPort, personas = personaSelectionUiPort,
        personaTransfer = personaTransferUiPort, stories = personaStoryUiPort,
        galleryDeletion = personaGalleryDeletionUiPort,
        gallerySave = personaGallerySaveUiPort, portraits = personaPortraitUiPort,
        authoring = personaAuthoringUiPort, currentPersona = currentPersonaUiPort,
        files = filesUiPort, memory = memoryUiPort, settings = settingsUiPort,
        automation = automationUiPort, media = mediaUiPort,
        usage = usageUiPort, diary = diaryUiPort,
    )

}

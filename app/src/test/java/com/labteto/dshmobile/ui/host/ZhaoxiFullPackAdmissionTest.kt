package com.labteto.dshmobile.ui.host

import com.labteto.dshmobile.uicontract.*
import com.labteto.dshmobile.uipack.zhaoxi.*
import com.labteto.dshmobile.uihost.*
import java.lang.reflect.Proxy
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every scene must be admitted when REAL port types are connected; mock ports never execute commands. */
class ZhaoxiFullPackAdmissionTest {
    @Suppress("UNCHECKED_CAST")
    private inline fun <reified T : Any> unreachablePort(): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) {
                _, method, _ -> error("Pack initialization must not invoke business owner: ${method.name}")
        } as T

    @Test fun completeCompositionAdmitsAllRequiredScenesAndCapabilities() {
        val preferences = unreachablePort<HostPreferencesUiPort>()
        val settings = unreachablePort<SettingsUiPort>()
        val chat = unreachablePort<ChatUiPort>()
        val work = unreachablePort<WorkConversationUiPort>()
        val drafts = unreachablePort<ConversationDraftUiPort>()
        val reading = unreachablePort<ConversationReadingUiPort>()
        val attachments = unreachablePort<AttachmentUiPort>()
        val history = unreachablePort<UiMessageHistoryPort>()
        val documents = unreachablePort<UiDocumentUiPort>()
        val forms = unreachablePort<UiDocumentFormUiPort>()
        val media = unreachablePort<MediaUiPort>()
        val gallery = unreachablePort<PersonaGalleryUiPort>()
        val selection = unreachablePort<PersonaSelectionUiPort>()
        val groups = unreachablePort<ChatGroupUiPort>()
        val stories = unreachablePort<PersonaStoryUiPort>()
        val galleryDeletion = unreachablePort<PersonaGalleryDeletionUiPort>()
        val gallerySave = unreachablePort<PersonaGallerySaveUiPort>()
        val transfer = unreachablePort<PersonaTransferUiPort>()
        val authoring = unreachablePort<PersonaAuthoringUiPort>()
        val workReads = unreachablePort<WorkReadUiPort>()
        val policy = unreachablePort<WorkPolicyUiPort>()
        val team = unreachablePort<WorkTeamUiPort>()
        val replies = unreachablePort<ReplySuggestionsUiPort>()
        val files = unreachablePort<FilesUiPort>()
        val sessions = unreachablePort<SessionUiPort>()
        val automation = unreachablePort<AutomationUiPort>()
        val memory = unreachablePort<MemoryUiPort>()
        val diary = unreachablePort<DiaryUiPort>()
        val currentPersona = unreachablePort<CurrentPersonaUiPort>()
        val portraits = unreachablePort<PersonaPortraitUiPort>()
        val tools = unreachablePort<ToolsUiPort>()
        val usage = unreachablePort<UsageUiPort>()
        val shell = unreachablePort<ConversationShellUiPort>()
        val pack = ZhaoxiApplicationScenePack(
            preferences = preferences,
            settings = settings,
            conversations = ZhaoxiConversationPorts(
                chat = chat, work = work, drafts = drafts, reading = reading,
                attachments = attachments, history = history, documents = documents,
                forms = forms, media = media,
                gallery = gallery, selection = selection, groups = groups,
                stories = stories, galleryDeletion = galleryDeletion,
                personaAuthoring = authoring, workReads = workReads,
                workPolicy = policy, workTeam = team, replySuggestions = replies,
                files = files, workMode = { false },
                workModeUpdates = MutableStateFlow(false),
            ),
            usageOwner = usage,
            updateStatus = MutableStateFlow<String?>(null),
            checkForUpdate = {},
            secondary = ZhaoxiSecondaryPorts(
                tools = tools, files = files, media = media, settings = settings,
                sessionId = { null }, automation = automation, sessions = sessions,
                transcript = history, memory = memory, diary = diary, authoring = authoring,
                currentPersona = currentPersona, replySuggestions = replies,
                gallery = gallery, selection = selection, portraits = portraits,
                modelPerformance = unreachablePort<ModelPerformanceUiPort>(),
                pricing = unreachablePort<PricingUiPort>(),
            ),
        )
        val required = LocalUiSceneCatalog.requirements.filterNot { it.hostProvided }.map { it.id }.toSet()
        assertEquals("Every Host scene must be declared", required, pack.manifest.scenes)
        assertEquals("Every manifest scene must have a renderer", required, pack.renderers.keys)
        val capabilities = LocalUiConnectedCapabilities.from(
            chat = chat, work = work, drafts = drafts, documents = documents, forms = forms,
            attachments = attachments, transcript = history, groups = groups, team = team,
            workReads = workReads, workPolicy = policy, shell = shell,
            gallery = gallery, personas = selection, personaTransfer = transfer,
            stories = stories, galleryDeletion = galleryDeletion, gallerySave = gallerySave,
            portraits = portraits, authoring = authoring, currentPersona = currentPersona,
            files = files, memory = memory, settings = settings,
            automation = automation, media = media, usage = usage, diary = diary,
        )
        val admission = UiApplicationPackRegistry(listOf(pack)).select(
            "zhaoxi",
            UiPackAdmissionRequirements(
                contractVersion = UI_CONTRACT_VERSION,
                requiredScenes = LocalUiSceneCatalog.requirements,
                availableCapabilities = capabilities,
                windowClass = UiWindowClass.COMPACT,
                requiredAccessibility = setOf(UiAccessibility.SCREEN_READER),
                dark = false,
            ),
        )
        assertTrue("All 75 scenes with connected owners must be admitted: $admission",
            admission is UiApplicationPackAdmission.Admitted)
    }
}

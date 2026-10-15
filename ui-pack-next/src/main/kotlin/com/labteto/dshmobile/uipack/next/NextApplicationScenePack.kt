package com.labteto.dshmobile.uipack.next

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.Flow
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.labteto.dshmobile.uicontract.*
import com.labteto.dshmobile.uihost.UiApplicationScenePack
import com.labteto.dshmobile.uihost.UiSceneRenderer
import com.labteto.dshmobile.uihost.UiSceneRenderScope
import com.labteto.dshmobile.uihost.UiSceneNavigationResult

/** Feature Owners are injected by the App, never recreated inside the UI bundle. */
data class NextApplicationFeaturePorts(
    val chat: ChatUiPort,
    val work: WorkConversationUiPort,
    val drafts: ConversationDraftUiPort,
    val reading: ConversationReadingUiPort,
    val gallery: PersonaGalleryUiPort,
    val selection: PersonaSelectionUiPort,
    val workMode: () -> Boolean,
    val workModeUpdates: Flow<Boolean>? = null,
    val documents: UiDocumentUiPort? = null,
    val forms: UiDocumentFormUiPort? = null,
    val attachments: AttachmentUiPort? = null,
    val sessions: SessionUiPort? = null,
    val transcript: UiMessageHistoryPort? = null,
    val policy: WorkPolicyUiPort? = null,
    val workReads: WorkReadUiPort? = null,
    val files: FilesUiPort? = null,
    val evidence: WorkEvidenceUiPort? = null,
    val shell: ConversationShellUiPort? = null,
    val team: WorkTeamUiPort? = null,
    val groups: ChatGroupUiPort? = null,
    val personaTransfer: PersonaTransferUiPort? = null,
    val stories: PersonaStoryUiPort? = null,
    val galleryDeletion: PersonaGalleryDeletionUiPort? = null,
    val gallerySave: PersonaGallerySaveUiPort? = null,
    val portraits: PersonaPortraitUiPort? = null,
)

/**
 * Five real executable scenes, NOT a full R4 application pack.
 * The production Host must reject this incomplete manifest for 78-scene admission.
 */
class NextApplicationScenePack(private val ports: NextApplicationFeaturePorts) : UiApplicationScenePack {
    private val home = UiSceneId("route.home")
    private val galleryRoute = UiSceneId("route.persona_gallery")
    private val chat = UiSceneId("conversation.chat")
    private val work = UiSceneId("conversation.work")
    private val galleryPage = UiSceneId("chat.gallery")

    override val manifest = UiPackManifest(
        id = "next-application-incremental", version = 1,
        minContractVersion = UI_CONTRACT_VERSION, maxContractVersion = UI_CONTRACT_VERSION,
        scenes = setOf(home, galleryRoute, chat, work, galleryPage),
        requiredCapabilities = setOf("chat.send_text", "work.send_text",
            "chat.gallery_archive", "chat.persona_selection").map(::UiCapabilityId).toSet(),
        windowClasses = UiWindowClass.entries.toSet(),
        accessibility = UiAccessibility.entries.toSet(),
        supportsDark = true,
    )
    override val renderers: Map<UiSceneId, UiSceneRenderer> = mapOf(
        home to UiSceneRenderer { scope -> HomeRoute(scope) },
        chat to UiSceneRenderer { scope -> ConversationRoute(scope, false) },
        work to UiSceneRenderer { scope -> ConversationRoute(scope, true) },
        galleryRoute to UiSceneRenderer { scope -> GalleryRoute(scope) },
        galleryPage to UiSceneRenderer { scope -> GalleryRoute(scope) },
    )

    @Composable
    private fun HomeRoute(scope: UiSceneRenderScope) {
        val liveMode = ports.workModeUpdates
            ?.collectAsStateWithLifecycle(initialValue = ports.workMode())?.value ?: ports.workMode()
        ConversationRoute(scope, liveMode)
    }

    @Composable
    private fun ConversationRoute(scope: UiSceneRenderScope, isWork: Boolean) {
        Column(Modifier.fillMaxSize()) {
            TextButton(onClick = { scope.onNavigate(galleryRoute) }) {
                Text(stringResource(R.string.next_gallery_archive))
            }
            Box(Modifier.weight(1f)) {
                NextConversationUiPack.Render(
                    documents = ports.documents, forms = ports.forms,
                    reading = ports.reading, drafts = ports.drafts,
                    chat = ports.chat, work = ports.work, workMode = isWork,
                    onClose = { scope.onBack() }, attachments = ports.attachments,
                    sessions = ports.sessions, transcript = ports.transcript,
                    policy = ports.policy, workReads = ports.workReads,
                    files = ports.files, evidence = ports.evidence,
                    shell = ports.shell, team = ports.team, groups = ports.groups,
                    gallery = ports.gallery, personas = ports.selection,
                    personaTransfer = ports.personaTransfer, stories = ports.stories,
                    galleryDeletion = ports.galleryDeletion, gallerySave = ports.gallerySave,
                    portraits = ports.portraits,
                )
            }
        }
    }

    @Composable
    private fun GalleryRoute(scope: UiSceneRenderScope) {
        NextPersonaGalleryScene(
            gallery = ports.gallery, selection = ports.selection,
            onBack = { scope.onBack() },
            onStoryOpened = { scope.onNavigate(home) is UiSceneNavigationResult.Changed },
        )
    }
}

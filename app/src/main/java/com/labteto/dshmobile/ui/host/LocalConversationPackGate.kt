package com.labteto.dshmobile.ui.host

import com.labteto.dshmobile.uicontract.AttachmentUiPort
import com.labteto.dshmobile.uicontract.ChatUiPort
import com.labteto.dshmobile.uicontract.ConversationDraftUiPort
import com.labteto.dshmobile.uicontract.UiMessageHistoryPort
import com.labteto.dshmobile.uicontract.UI_CONTRACT_VERSION
import com.labteto.dshmobile.uicontract.UiAccessibility
import com.labteto.dshmobile.uicontract.UiCapabilityId
import com.labteto.dshmobile.uicontract.UiDocumentFormUiPort
import com.labteto.dshmobile.uicontract.UiDocumentUiPort
import com.labteto.dshmobile.uicontract.UiSceneId
import com.labteto.dshmobile.uicontract.UiWindowClass
import com.labteto.dshmobile.uicontract.WorkConversationUiPort
import com.labteto.dshmobile.uihost.UiPackAdmission
import com.labteto.dshmobile.uihost.UiPackAdmissionRequirements
import com.labteto.dshmobile.uihost.UiPackRegistry
import com.labteto.dshmobile.uipack.next.NextConversationUiPack

/**
 * Debug-only conversation slice admission. The required scene list belongs to the Host,
 * never to a pack's own manifest, so a pack cannot weaken its own acceptance criteria.
 * This does not authorize an entire application UI replacement.
 */
internal object LocalConversationPackGate {
    private val registry = UiPackRegistry(listOf(NextConversationUiPack))
    private val requiredSceneNames = setOf(
        "conversation.chat", "conversation.work", "conversation.timeline",
        "conversation.composer", "conversation.ui_document",
        "conversation.ui_document_generate", "work.approval", "work.question",
        "chat.group_members", "work.team", "work.requirement_evidence",
    )
    private val requiredSliceScenes = LocalUiSceneCatalog.requirements.filter {
        it.id.value in requiredSceneNames
    }.also { requirements ->
        require(requirements.map { it.id.value }.toSet() == requiredSceneNames) {
            "UI scene catalog does not contain the complete required conversation slice"
        }
    }

    /**
     * Capabilities here mean connected *UI operations*, not current permission.
     * The real Feature Owner always rechecks identity, authorization and side effects.
     */
    fun capabilities(
        chat: ChatUiPort?,
        work: WorkConversationUiPort?,
        drafts: ConversationDraftUiPort?,
        documents: UiDocumentUiPort?,
        forms: UiDocumentFormUiPort?,
        attachments: AttachmentUiPort?,
        transcript: UiMessageHistoryPort?,
        groups: com.labteto.dshmobile.uicontract.ChatGroupUiPort? = null,
        team: com.labteto.dshmobile.uicontract.WorkTeamUiPort? = null,
        shell: com.labteto.dshmobile.uicontract.ConversationShellUiPort? = null,
        evidence: com.labteto.dshmobile.uicontract.WorkEvidenceUiPort? = null,
        gallery: com.labteto.dshmobile.uicontract.PersonaGalleryUiPort? = null,
        personas: com.labteto.dshmobile.uicontract.PersonaSelectionUiPort? = null,
        personaTransfer: com.labteto.dshmobile.uicontract.PersonaTransferUiPort? = null,
        stories: com.labteto.dshmobile.uicontract.PersonaStoryUiPort? = null,
        galleryDeletion: com.labteto.dshmobile.uicontract.PersonaGalleryDeletionUiPort? = null,
        gallerySave: com.labteto.dshmobile.uicontract.PersonaGallerySaveUiPort? = null,
        portraits: com.labteto.dshmobile.uicontract.PersonaPortraitUiPort? = null,
        ): Set<UiCapabilityId> = LocalUiConnectedCapabilities.from(
        chat = chat,
        work = work,
        drafts = drafts,
        documents = documents,
        forms = forms,
        attachments = attachments,
        transcript = transcript,
        groups = groups,
        team = team,
        shell = shell,
        evidence = evidence,
        gallery = gallery,
        personas = personas,
        personaTransfer = personaTransfer,
        stories = stories,
        galleryDeletion = galleryDeletion,
        gallerySave = gallerySave,
        portraits = portraits,
    )
    fun admit(
        packId: String,
        availableCapabilities: Set<UiCapabilityId>,
        windowClass: UiWindowClass,
        dark: Boolean,
    ): UiPackAdmission = registry.select(packId, UiPackAdmissionRequirements(
        contractVersion = UI_CONTRACT_VERSION,
        requiredScenes = requiredSliceScenes,
        availableCapabilities = availableCapabilities,
        windowClass = windowClass,
        requiredAccessibility = UiAccessibility.entries.toSet(),
        dark = dark,
    ))
}

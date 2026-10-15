package com.labteto.dshmobile.uihost

import androidx.compose.runtime.Composable
import com.labteto.dshmobile.uicontract.ChatUiPort
import com.labteto.dshmobile.uicontract.UiPackManifest
import com.labteto.dshmobile.uicontract.WorkConversationUiPort

/** First migration slice. Full navigation and non-conversation renderers remain separate work. */
interface ConversationUiPack {
    val manifest: UiPackManifest

    @Composable
    fun Render(
        documents: com.labteto.dshmobile.uicontract.UiDocumentUiPort?,
        forms: com.labteto.dshmobile.uicontract.UiDocumentFormUiPort?,
        reading: com.labteto.dshmobile.uicontract.ConversationReadingUiPort,
        drafts: com.labteto.dshmobile.uicontract.ConversationDraftUiPort,
        chat: ChatUiPort,
        work: WorkConversationUiPort,
        workMode: Boolean,
        onClose: () -> Unit,
        attachments: com.labteto.dshmobile.uicontract.AttachmentUiPort? = null,
        sessions: com.labteto.dshmobile.uicontract.SessionUiPort? = null,
        transcript: com.labteto.dshmobile.uicontract.UiMessageHistoryPort? = null,
        policy: com.labteto.dshmobile.uicontract.WorkPolicyUiPort? = null,
        workReads: com.labteto.dshmobile.uicontract.WorkReadUiPort? = null,
        files: com.labteto.dshmobile.uicontract.FilesUiPort? = null,
        evidence: com.labteto.dshmobile.uicontract.WorkEvidenceUiPort? = null,
        shell: com.labteto.dshmobile.uicontract.ConversationShellUiPort? = null,
        team: com.labteto.dshmobile.uicontract.WorkTeamUiPort? = null,
        groups: com.labteto.dshmobile.uicontract.ChatGroupUiPort? = null,
        gallery: com.labteto.dshmobile.uicontract.PersonaGalleryUiPort? = null,
        personas: com.labteto.dshmobile.uicontract.PersonaSelectionUiPort? = null,
        personaTransfer: com.labteto.dshmobile.uicontract.PersonaTransferUiPort? = null,
        stories: com.labteto.dshmobile.uicontract.PersonaStoryUiPort? = null,
        galleryDeletion: com.labteto.dshmobile.uicontract.PersonaGalleryDeletionUiPort? = null,
        gallerySave: com.labteto.dshmobile.uicontract.PersonaGallerySaveUiPort? = null,
        portraits: com.labteto.dshmobile.uicontract.PersonaPortraitUiPort? = null,
    )
}

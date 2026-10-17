package com.labteto.dshmobile.ui.host

import com.labteto.dshmobile.uicontract.AttachmentUiPort
import com.labteto.dshmobile.uicontract.ChatUiPort
import com.labteto.dshmobile.uicontract.ConversationDraftUiPort
import com.labteto.dshmobile.uicontract.UiMessageHistoryPort
import com.labteto.dshmobile.uicontract.UiCapabilityId
import com.labteto.dshmobile.uicontract.UiDocumentFormUiPort
import com.labteto.dshmobile.uicontract.UiDocumentUiPort
import com.labteto.dshmobile.uicontract.WorkConversationUiPort

/**
 * Shared Host-owned inventory of connected Feature operations.
 * Both stable production admission and debug slice use the SAME projection.
 * A connected Port is not current authorization; each Owner checks on submit.
 */
internal object LocalUiConnectedCapabilities {
    fun from(
        chat: ChatUiPort?,
        work: WorkConversationUiPort?,
        drafts: ConversationDraftUiPort?,
        documents: UiDocumentUiPort?,
        forms: UiDocumentFormUiPort?,
        attachments: AttachmentUiPort?,
        transcript: UiMessageHistoryPort?,
        groups: com.labteto.dshmobile.uicontract.ChatGroupUiPort? = null,
        team: com.labteto.dshmobile.uicontract.WorkTeamUiPort? = null,
        workReads: com.labteto.dshmobile.uicontract.WorkReadUiPort? = null,
        workPolicy: com.labteto.dshmobile.uicontract.WorkPolicyUiPort? = null,
        shell: com.labteto.dshmobile.uicontract.ConversationShellUiPort? = null,
        evidence: com.labteto.dshmobile.uicontract.WorkEvidenceUiPort? = null,
        gallery: com.labteto.dshmobile.uicontract.PersonaGalleryUiPort? = null,
        personas: com.labteto.dshmobile.uicontract.PersonaSelectionUiPort? = null,
        personaTransfer: com.labteto.dshmobile.uicontract.PersonaTransferUiPort? = null,
        stories: com.labteto.dshmobile.uicontract.PersonaStoryUiPort? = null,
        galleryDeletion: com.labteto.dshmobile.uicontract.PersonaGalleryDeletionUiPort? = null,
        gallerySave: com.labteto.dshmobile.uicontract.PersonaGallerySaveUiPort? = null,
        portraits: com.labteto.dshmobile.uicontract.PersonaPortraitUiPort? = null,
        authoring: com.labteto.dshmobile.uicontract.PersonaAuthoringUiPort? = null,
        currentPersona: com.labteto.dshmobile.uicontract.CurrentPersonaUiPort? = null,
        files: com.labteto.dshmobile.uicontract.FilesUiPort? = null,
        memory: com.labteto.dshmobile.uicontract.MemoryUiPort? = null,
        settings: com.labteto.dshmobile.uicontract.SettingsUiPort? = null,
        automation: com.labteto.dshmobile.uicontract.AutomationUiPort? = null,
        media: com.labteto.dshmobile.uicontract.MediaUiPort? = null,
        usage: com.labteto.dshmobile.uicontract.UsageUiPort? = null,
        diary: com.labteto.dshmobile.uicontract.DiaryUiPort? = null,
    ): Set<UiCapabilityId> = buildSet {
        if (chat != null && drafts != null) {
            listOf("chat.send_text", "chat.edit", "chat.regenerate",
                "chat.select_variant", "chat.stop").forEach { add(UiCapabilityId(it)) }
        }
        if (work != null && drafts != null) {
            listOf("work.send_text", "work.edit", "work.regenerate", "work.stop",
                "work.answer_approval", "work.answer_question").forEach { add(UiCapabilityId(it)) }
        }
        if (documents != null && forms != null && drafts != null) {
            listOf("ui_document.generate", "ui_document.generate_local",
                "ui_document.prepare_input").forEach { add(UiCapabilityId(it)) }
        }
        if (attachments != null) add(UiCapabilityId("attachment.import"))
        if (transcript != null) add(UiCapabilityId("session.transcript_read"))
        if (groups != null) add(UiCapabilityId("chat.group_members"))
        if (team != null) {
            add(UiCapabilityId("work.team_control"))
            // Scene manifests require the actual Work Team action, not only a
            // legacy control hint. WorkTeamUiPort remains the admission owner.
            add(UiCapabilityId("work.team"))
        }
        if (workReads != null && workPolicy != null)
            add(UiCapabilityId("work.plan_review"))
        if (shell != null) add(UiCapabilityId("shell.session_preferences"))
        if (evidence != null) add(UiCapabilityId("work.evidence_reference"))
        if (gallery != null) add(UiCapabilityId("chat.gallery_archive"))
        if (personas != null) add(UiCapabilityId("chat.persona_selection"))
        if (personaTransfer != null) add(UiCapabilityId("chat.persona_transfer"))
        if (stories != null) add(UiCapabilityId("chat.gallery_story"))
        if (galleryDeletion != null) add(UiCapabilityId("chat.gallery_delete"))
        if (gallerySave != null) add(UiCapabilityId("chat.gallery_save"))
        if (portraits != null) add(UiCapabilityId("chat.gallery_portrait"))
        if (authoring != null) add(UiCapabilityId("chat.persona_authoring"))
        if (currentPersona != null) add(UiCapabilityId("chat.current_persona"))
        if (files != null) add(UiCapabilityId("files.read"))
        if (memory != null && settings != null) add(UiCapabilityId("memory.manage"))
        if (automation != null) add(UiCapabilityId("automation.manage"))
        if (media != null) add(UiCapabilityId("media.create"))
        if (usage != null) add(UiCapabilityId("usage.read"))
        if (diary != null) add(UiCapabilityId("chat.diary_manage"))
    }

}

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
        transcript: com.labteto.dshmobile.uicontract.TranscriptUiPort? = null,
    )
}

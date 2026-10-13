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
        chat: ChatUiPort,
        work: WorkConversationUiPort,
        workMode: Boolean,
        onClose: () -> Unit,
    )
}

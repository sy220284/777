package com.labteto.dshmobile.ui.screens.local

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.R
import com.labteto.dshmobile.uicontract.UiCommandReceipt
import com.labteto.dshmobile.uicontract.UiCommandRejection
import com.labteto.dshmobile.uicontract.UiDocumentReceipt
import org.junit.Rule
import org.junit.Test

class LocalDynamicDocumentFeedbackTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun rejectionFailureQueueAndUnknownNeverLookAccepted() {
        val receipt = mutableStateOf<UiDocumentReceipt>(UiDocumentReceipt.OwnerReceipt(UiCommandReceipt.Accepted))
        compose.setContent { LocalDocumentActionFeedback(receipt.value) }
        val accepted = context.getString(R.string.ui_document_owner_receipt)
        compose.onNodeWithText(accepted).assertExists()
        val cases = listOf(
            UiCommandReceipt.Rejected(UiCommandRejection.APPROVAL_EXPIRED) to context.getString(R.string.local_ui_approval_expired),
            UiCommandReceipt.Rejected(UiCommandRejection.STALE_IDENTITY) to context.getString(R.string.local_ui_context_changed),
            UiCommandReceipt.Failed("实际操作未成功") to "实际操作未成功",
            UiCommandReceipt.Queued to context.getString(R.string.ui_document_enqueued),
            UiCommandReceipt.OutcomeUnknown to context.getString(R.string.local_ui_result_unknown),
            UiCommandReceipt.NotAdmitted to context.getString(R.string.ui_document_not_admitted),
        )
        cases.forEach { (owner, expected) ->
            compose.runOnIdle { receipt.value = UiDocumentReceipt.OwnerReceipt(owner) }
            compose.onNodeWithText(expected).assertExists()
            compose.onNodeWithText(accepted).assertDoesNotExist()
        }
    }

    @Test fun unreadableAudioShowsFailureAndExplicitReloadInsteadOfEndlessPreparation() {
        compose.setContent { LocalDocumentMediaAudio("/missing/r4-audio.mp3") }
        compose.onNodeWithText(context.getString(R.string.ui_document_media_playback_failed)).assertExists()
        compose.onNodeWithText(context.getString(R.string.ui_document_media_audio_preparing)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.ui_document_media_retry)).performClick()
        compose.onNodeWithText(context.getString(R.string.ui_document_media_playback_failed)).assertExists()
    }
}

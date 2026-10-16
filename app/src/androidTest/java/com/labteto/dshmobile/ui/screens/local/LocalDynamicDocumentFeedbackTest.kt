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
import com.labteto.dshmobile.uicontract.*
import com.labteto.dshmobile.ui.host.LocalUiDocumentFormState
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
    @Test fun cardSubmissionRequiresExplicitConfirmationAndCapturesExactFields() {
        val source = UiDocumentSource("s", "assistant", 0, null, null)
        val action = UiDocumentAction.SendUserInput("send", "Continue", listOf("choice"), submit = true)
        val document = UiDocument(1, "doc", source, 0, "Choose",
            listOf(UiDocumentNode.Field("choice", "Choice", "actual input", true, false),
                UiDocumentNode.Button("button", "Submit choice", "send")), listOf(action), "Choose")
        val snapshot = UiDocumentSnapshot(document, UiDocumentProvenance(null, "model", false),
            mapOf("send" to UiActionAvailability(true)))
        val sent = mutableListOf<UiDocumentActionRequest>()
        val port = object : UiDocumentUiPort {
            override val snapshots = MutableStateFlow(UiDocumentsSceneSnapshot("s", listOf(snapshot), false, null))
            override fun snapshot() = snapshots.value
            override suspend fun generateLocal(command: GenerateLocalUiDocumentCommand) = UiDocumentReceipt.Rejected(UiCommandRejection.UNSUPPORTED)
            override suspend fun generate(command: GenerateUiDocumentCommand) = UiDocumentReceipt.Rejected(UiCommandRejection.UNSUPPORTED)
            override suspend fun delete(command: DeleteUiDocumentCommand) = UiDocumentReceipt.Rejected(UiCommandRejection.UNSUPPORTED)
            override fun submit(identity: UiSessionIdentity, request: UiDocumentActionRequest): UiDocumentReceipt {
                sent += request
                return UiDocumentReceipt.OwnerReceipt(UiCommandReceipt.Queued)
            }
        }
        // This card contains no media; a call would expose an unexpected renderer dependency.
        val media = java.lang.reflect.Proxy.newProxyInstance(MediaUiPort::class.java.classLoader,
            arrayOf(MediaUiPort::class.java)) { _, _, _ -> error("No media should be read") } as MediaUiPort
        val forms = LocalUiDocumentFormState(emptyList()) {}
        compose.setContent { LocalDynamicDocument(snapshot, UiSessionIdentity("s", "activation", 0, null, null), port, forms, media) }
        compose.onNodeWithText("Submit choice").performClick()
        compose.onNodeWithText("Continue\nchoice: actual input").assertExists()
        compose.runOnIdle { assertTrue(sent.isEmpty()) }
        compose.onNodeWithText(context.getString(R.string.ui_document_send_confirm)).performClick()
        compose.runOnIdle {
            assertEquals(1, sent.size)
            assertTrue(sent.single().confirmed)
            assertEquals("actual input", sent.single().fields["choice"])
        }
        compose.onNodeWithText(context.getString(R.string.ui_document_enqueued)).assertExists()
    }

}

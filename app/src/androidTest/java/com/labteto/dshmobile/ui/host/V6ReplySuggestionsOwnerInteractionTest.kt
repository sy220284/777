package com.labteto.dshmobile.ui.host

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.labteto.dshmobile.local.attachment.LocalImportedAttachment
import com.labteto.dshmobile.uicontract.*
import com.labteto.dshmobile.uipack.v6.V6ReplySuggestionsScene
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Real V6 Compose interaction against exact Owner identities.
 * This proves admission/command routing, not successful remote model execution.
 */
class V6ReplySuggestionsOwnerInteractionTest {
    @get:Rule val compose = createComposeRule()
    private val identity = UiSessionIdentity("session-a", "activation-a", 3L, "model-1", null)
    private val other = UiSessionIdentity("session-b", "activation-b", 4L, "model-2", null)

    private class Chat(identity: UiSessionIdentity) : ChatUiPort {
        override val snapshots = MutableStateFlow(ChatSceneSnapshot(
            identity, UiSceneStatus.CONTENT, UiExecutionPhase.IDLE, emptyList(),
            0, UiActionAvailability(true), null
        ))
        val commands = mutableListOf<ChatUiCommand>()
        override fun snapshot() = snapshots.value
        override fun submit(command: ChatUiCommand): UiCommandReceipt {
            commands.add(command)
            return UiCommandReceipt.Accepted
        }
    }

    private class Suggestions(identity: UiSessionIdentity) : ReplySuggestionsUiPort {
        override val snapshots = MutableStateFlow(UiReplySuggestionsSnapshot(identity,
            listOf(UiChatReplySuggestion("简短回应", "我会认真考虑", "gentle", false))))
        val requested = mutableListOf<UiSessionIdentity>()
        override suspend fun generate(identity: UiSessionIdentity): UiCommandReceipt {
            requested.add(identity)
            return UiCommandReceipt.Accepted
        }
    }

    private class Drafts : ConversationDraftUiPort {
        override val drafts = MutableStateFlow<Map<String, String>>(emptyMap())
        override val attachments =
            MutableStateFlow<Map<String, List<LocalImportedAttachment>>>(emptyMap())
        override fun updateDraft(sessionId: String, text: String) {
            drafts.value = drafts.value + (sessionId to text)
        }
        override fun addAttachment(sessionId: String, attachment: LocalImportedAttachment) {}
        override fun removeAttachment(sessionId: String, relativePath: String) {}
        override fun clearAttachments(sessionId: String) {}
    }

    private fun show(suggestions: Suggestions, drafts: Drafts, chat: Chat) {
        compose.setContent {
            MaterialTheme {
                V6ReplySuggestionsScene(suggestions, drafts, chat, onBack = {})
            }
        }
        compose.onNodeWithText("我会认真考虑").assertExists()
    }

    @Test fun directSendUsesOwnerIssuedIdentityAndPreservesOriginalComposerDraft() {
        val suggestions = Suggestions(identity)
        val drafts = Drafts()
        val chat = Chat(identity)
        drafts.updateDraft(identity.sessionId, "用户正在编辑的另一段内容")
        show(suggestions, drafts, chat)

        compose.onNodeWithText("直接发送此建议").performClick()
        compose.runOnIdle {
            assertEquals(listOf(ChatUiCommand.SendText(identity, "我会认真考虑")),
                chat.commands)
            assertEquals("用户正在编辑的另一段内容",
                drafts.drafts.value[identity.sessionId])
        }
    }

    @Test fun staleSessionPreventsCrossSessionSendAndDoesNotMutateOtherDraft() {
        val suggestions = Suggestions(identity)
        val drafts = Drafts()
        val chat = Chat(identity)
        show(suggestions, drafts, chat)
        compose.runOnIdle {
            chat.snapshots.value = chat.snapshots.value.copy(identity = other)
        }
        compose.onNodeWithText("直接发送此建议").performClick()
        compose.runOnIdle {
            assertTrue(chat.commands.isEmpty())
            assertTrue(drafts.drafts.value.isEmpty())
        }
    }

    @Test fun deniedOwnerSendCapabilityPreventsSubmission() {
        val suggestions = Suggestions(identity)
        val drafts = Drafts()
        val chat = Chat(identity)
        show(suggestions, drafts, chat)
        compose.runOnIdle {
            chat.snapshots.value = chat.snapshots.value.copy(send = UiActionAvailability(false,
                UiCommandRejection.QUEUE_FULL))
        }
        compose.onNodeWithText("直接发送此建议").performClick()
        compose.runOnIdle { assertTrue(chat.commands.isEmpty()) }
    }

    @Test fun draftOnlyActionNeverSubmitsOrInventsModelExecution() {
        val suggestions = Suggestions(identity)
        val drafts = Drafts()
        val chat = Chat(identity)
        show(suggestions, drafts, chat)
        compose.onNodeWithText("填写到普通草稿").performClick()
        compose.runOnIdle {
            assertEquals("我会认真考虑", drafts.drafts.value[identity.sessionId])
            assertTrue(chat.commands.isEmpty())
            assertTrue(suggestions.requested.isEmpty())
        }
    }
}

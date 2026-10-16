package com.labteto.dshmobile.uipack.v6

import com.labteto.dshmobile.local.attachment.LocalImportedAttachment
import com.labteto.dshmobile.uicontract.ChatSceneSnapshot
import com.labteto.dshmobile.uicontract.ChatUiCommand
import com.labteto.dshmobile.uicontract.ChatUiPort
import com.labteto.dshmobile.uicontract.ConversationDraftUiPort
import com.labteto.dshmobile.uicontract.ConversationReadingUiPort
import com.labteto.dshmobile.uicontract.UiActionAvailability
import com.labteto.dshmobile.uicontract.UiCommandReceipt
import com.labteto.dshmobile.uicontract.UiCommandRejection
import com.labteto.dshmobile.uicontract.UiExecutionPhase
import com.labteto.dshmobile.uicontract.UiReadingAnchor
import com.labteto.dshmobile.uicontract.UiSceneStatus
import com.labteto.dshmobile.uicontract.UiSessionIdentity
import com.labteto.dshmobile.uicontract.WorkConversationSceneSnapshot
import com.labteto.dshmobile.uicontract.WorkConversationUiCommand
import com.labteto.dshmobile.uicontract.WorkConversationUiPort
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests actual Owner command admission, not fictional renderer-count fixtures. */
class V6ConversationSubmissionTest {
    private val identity = UiSessionIdentity("s1", "activation-1", 7, null, null)
    private class ChatOwner : ChatUiPort {
        private val current = ChatSceneSnapshot(null, UiSceneStatus.EMPTY, UiExecutionPhase.IDLE,
            emptyList(), 0, UiActionAvailability(true), null)
        override val snapshots = MutableStateFlow(current)
        override fun snapshot() = current
        var calls = 0
        var last: ChatUiCommand? = null
        var receipt: UiCommandReceipt = UiCommandReceipt.Accepted
        override fun submit(command: ChatUiCommand): UiCommandReceipt {
            calls++
            last = command
            return receipt
        }
    }
    private class WorkOwner : WorkConversationUiPort {
        private val current = WorkConversationSceneSnapshot(null, UiSceneStatus.EMPTY,
            UiExecutionPhase.IDLE, emptyList(), 0, UiActionAvailability(true), null, null)
        override val snapshots = MutableStateFlow(current)
        override fun snapshot() = current
        var calls = 0
        var last: WorkConversationUiCommand? = null
        var receipt: UiCommandReceipt = UiCommandReceipt.Accepted
        override fun submit(command: WorkConversationUiCommand): UiCommandReceipt {
            calls++
            last = command
            return receipt
        }
    }
    private val drafts = object : ConversationDraftUiPort {
        override val drafts = MutableStateFlow<Map<String, String>>(emptyMap())
        override val attachments = MutableStateFlow<Map<String, List<LocalImportedAttachment>>>(emptyMap())
        override fun updateDraft(sessionId: String, text: String) {}
        override fun addAttachment(sessionId: String, attachment: LocalImportedAttachment) {}
        override fun removeAttachment(sessionId: String, relativePath: String) {}
        override fun clearAttachments(sessionId: String) {}
    }
    private val reading = object : ConversationReadingUiPort {
        override fun anchor(sessionId: String): UiReadingAnchor? = null
        override fun rememberAnchor(sessionId: String, anchor: UiReadingAnchor) {}
    }
    private val chat = ChatOwner()
    private val work = WorkOwner()
    private fun ports() = V6ConversationPorts(chat, work, drafts, reading, workMode = { false })

    @Test fun anAttachmentWithoutTextStillGoesToTheExistingChatOwner() {
        val item = LocalImportedAttachment("photo.png", "session/photo.png", "image/png", 42)
        val result = submitV6ConversationDraft(ports(), false, identity, "", listOf(item))
        assertEquals(UiCommandReceipt.Accepted, result)
        assertEquals(1, chat.calls)
        assertEquals(0, work.calls)
        val command = chat.last as ChatUiCommand.SendMessage
        assertEquals(identity, command.identity)
        assertEquals("", command.text)
        assertEquals(listOf(item), command.attachments)
    }

    @Test fun workMessagesCannotAccidentallyBeSentToChatOwner() {
        val result = submitV6ConversationDraft(ports(), true, identity, "inspect this", emptyList())
        assertEquals(UiCommandReceipt.Accepted, result)
        assertEquals(0, chat.calls)
        assertEquals(1, work.calls)
        val command = work.last as WorkConversationUiCommand.SendMessage
        assertEquals(identity, command.identity)
        assertEquals("inspect this", command.text)
    }

    @Test fun trulyEmptyInputIsRefusedBeforeAnyOwnerMutation() {
        val result = submitV6ConversationDraft(ports(), false, identity, "  ", emptyList())
        assertEquals(UiCommandReceipt.Rejected(UiCommandRejection.EMPTY_INPUT), result)
        assertEquals(0, chat.calls)
        assertEquals(0, work.calls)
        assertFalse(acceptedV6ConversationReceipt(result))
    }

    @Test fun rejectionAndUnknownOutcomesNeverClearTheUsersDraft() {
        chat.receipt = UiCommandReceipt.Rejected(UiCommandRejection.QUEUE_FULL)
        val rejected = submitV6ConversationDraft(ports(), false, identity, "hello", emptyList())
        assertSame(chat.receipt, rejected)
        assertFalse(acceptedV6ConversationReceipt(rejected))
        assertFalse(acceptedV6ConversationReceipt(UiCommandReceipt.NotAdmitted))
        assertFalse(acceptedV6ConversationReceipt(UiCommandReceipt.OutcomeUnknown))
        assertTrue(acceptedV6ConversationReceipt(UiCommandReceipt.Accepted))
        assertTrue(acceptedV6ConversationReceipt(UiCommandReceipt.Queued))
    }
}

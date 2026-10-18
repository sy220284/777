package com.labteto.dshmobile.uipack.zhaoxi

import com.labteto.dshmobile.uicontract.*
import com.labteto.dshmobile.local.attachment.LocalImportedAttachment
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

class ZhaoxiDraftCommandsTest {
    private val identity = UiSessionIdentity("A", "activation", 1, null, null)
    private class Drafts : ConversationDraftUiPort {
        override val drafts = MutableStateFlow(mapOf("A" to "原始草稿", "B" to "另一会话"))
        override val attachments = MutableStateFlow(mapOf("A" to listOf(LocalImportedAttachment("a", "a", "text/plain", 1))))
        override fun updateDraft(sessionId: String, text: String) { drafts.value += sessionId to text }
        override fun addAttachment(sessionId: String, attachment: LocalImportedAttachment) { attachments.value += sessionId to (attachments.value[sessionId].orEmpty() + attachment) }
        override fun removeAttachment(sessionId: String, relativePath: String) { attachments.value += sessionId to attachments.value[sessionId].orEmpty().filterNot { it.relativePath == relativePath } }
        override fun clearAttachments(sessionId: String) { attachments.value += sessionId to emptyList() }
    }
    @Test fun rejectedFailedAndUnknownRequestsKeepTextAndAttachments() {
        listOf(UiCommandReceipt.Rejected(UiCommandRejection.QUEUE_FULL), UiCommandReceipt.Failed("offline"), UiCommandReceipt.OutcomeUnknown).forEach { receipt ->
            val drafts = Drafts()
            assertEquals(receipt, sendZhaoxiDraft(drafts, identity) { _, _ -> receipt })
            assertEquals("原始草稿", drafts.drafts.value["A"])
            assertEquals(1, drafts.attachments.value["A"]?.size)
        }
    }
    @Test fun queuedSendClearsOnlyTheCapturedSession() {
        val drafts = Drafts()
        sendZhaoxiDraft(drafts, identity) { text, files ->
            assertEquals("原始草稿", text); assertEquals("a", files.single().relativePath)
            UiCommandReceipt.Queued
        }
        assertEquals("", drafts.drafts.value["A"])
        assertEquals("另一会话", drafts.drafts.value["B"])
        assertTrue(drafts.attachments.value["A"].orEmpty().isEmpty())
    }
    @Test fun admissionCannotEraseNewInputOrNewAttachments() {
        val drafts = Drafts()
        sendZhaoxiDraft(drafts, identity) { _, _ ->
            drafts.updateDraft("A", "随后输入")
            drafts.addAttachment("A", LocalImportedAttachment("b", "b", "text/plain", 1))
            UiCommandReceipt.Accepted
        }
        assertEquals("随后输入", drafts.drafts.value["A"])
        assertEquals("b", drafts.attachments.value["A"].orEmpty().single().relativePath)
    }
}

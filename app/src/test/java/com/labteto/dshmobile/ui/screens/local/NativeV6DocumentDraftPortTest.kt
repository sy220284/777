package com.labteto.dshmobile.ui.screens.local

import com.labteto.dshmobile.local.attachment.LocalImportedAttachment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class NativeV6DocumentDraftPortTest {
    @Test fun preparedDraftIsConsumedOnlyOnceAndNeverReplayedOnSessionReturn() {
        val drafts = NativeV6DocumentDraftPort()
        drafts.updateDraft("chat-a", "从文档准备的输入")
        drafts.updateDraft("chat-b", "第二个会话的输入")

        assertEquals("从文档准备的输入", drafts.consumeDraft("chat-a"))
        assertNull(drafts.consumeDraft("chat-a"))
        assertFalse(drafts.drafts.value.containsKey("chat-a"))
        // Simulate switching back after the user has edited the real composer.
        assertNull(drafts.consumeDraft("chat-a"))
        assertEquals("第二个会话的输入", drafts.consumeDraft("chat-b"))
        assertNull(drafts.consumeDraft("chat-b"))
    }

    @Test fun consumingOneDraftDoesNotEraseAnotherSessionOrItsAttachments() {
        val drafts = NativeV6DocumentDraftPort()
        val attachment = LocalImportedAttachment("image.jpg", "inputs/image.jpg", "image/jpeg", 12L)
        drafts.updateDraft("chat-a", "提交到输入框")
        drafts.updateDraft("chat-b", "保留")
        drafts.addAttachment("chat-a", attachment)

        assertEquals("提交到输入框", drafts.consumeDraft("chat-a"))
        assertEquals("保留", drafts.drafts.value["chat-b"])
        assertEquals(listOf(attachment), drafts.attachments.value["chat-a"])
    }

    @Test fun newDocumentActionCanPrepareAnotherDraftAfterThePreviousOneWasConsumed() {
        val drafts = NativeV6DocumentDraftPort()
        drafts.updateDraft("chat-a", "第一次")
        assertEquals("第一次", drafts.consumeDraft("chat-a"))
        drafts.updateDraft("chat-a", "第二次")
        assertEquals("第二次", drafts.consumeDraft("chat-a"))
        assertNull(drafts.consumeDraft("chat-a"))
        drafts.updateDraft("chat-a", " ")
        assertNull(drafts.consumeDraft("chat-a"))
    }
}

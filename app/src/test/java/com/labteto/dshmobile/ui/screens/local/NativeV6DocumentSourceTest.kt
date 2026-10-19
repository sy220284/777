package com.labteto.dshmobile.ui.screens.local

import com.labteto.dshmobile.local.session.LocalHarnessMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NativeV6DocumentSourceTest {
    private fun row(id: String, role: String) =
        LocalHarnessMessage(id = id, role = role, content = id, createdAt = 0L)

    @Test fun latestUserAndToolMessagesCannotBecomeDocumentSource() {
        assertEquals("assistant-2", nativeV6SourceAssistantMessageId(listOf(
            row("assistant-1", "assistant"), row("assistant-2", "assistant"),
            row("user-last", "user"), row("tool-last", "tool"),
        )))
    }
    @Test fun noAssistantReplyMeansNoAvailableSource() {
        assertNull(nativeV6SourceAssistantMessageId(listOf(row("user-last", "user"))))
    }
}

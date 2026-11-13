package com.labteto.dshmobile.ui.screens.local

import com.labteto.dshmobile.uicontract.UiDocument
import com.labteto.dshmobile.uicontract.UiDocumentOrigin
import com.labteto.dshmobile.uicontract.UiDocumentProvenance
import com.labteto.dshmobile.uicontract.UiDocumentSnapshot
import com.labteto.dshmobile.uicontract.UiDocumentSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeV6DocumentPlacementTest {
    private fun snapshot(messageId: String, documentId: String) = UiDocumentSnapshot(
        UiDocument(1, documentId, UiDocumentSource("session", messageId, 0L, null, null, true),
            0L, "阶段成果", emptyList(), emptyList(), "已保存"),
        UiDocumentProvenance(null, "", false, UiDocumentOrigin.LOCAL), emptyMap()
    )

    @Test fun chatHidesAllDocumentsWhileWorkKeepsReplyCardsAtTheirSource() {
        val docs = listOf(snapshot("user-1", "work-live:user-1"),
            snapshot("user-1", "reply-ui:user-1"), snapshot("assistant-2", "reply-ui:assistant-2"))
        val user = nativeV6DocumentsForMessage("user-1", "user", true, docs)
        assertEquals(listOf("reply-ui:user-1"), user.map { it.document.documentId })
        assertEquals(listOf("reply-ui:assistant-2"),
            nativeV6DocumentsForMessage("assistant-2", "assistant", true, docs)
                .map { it.document.documentId })
        assertTrue(nativeV6DocumentsForMessage("user-1", "user", false, docs).isEmpty())
        assertTrue(nativeV6DocumentsForMessage("assistant-2", "assistant", false, docs).isEmpty())
        assertTrue(nativeV6DocumentsForMessage("tool-3", "tool", true, docs).isEmpty())
        assertTrue(nativeV6DocumentsForMessage("other", "user", true, docs).isEmpty())
    }
}

package com.labteto.dshmobile.uipack.zhaoxi.scenes

import com.labteto.dshmobile.uicontract.UiDocument
import com.labteto.dshmobile.uicontract.UiDocumentSource
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Work event ledger remains intact, while the conversation uses progressive reply text. */
class ZhaoxiConversationDocumentPolicyTest {
    private val source = UiDocumentSource(
        sessionId = "work-session",
        messageId = "assistant-message",
        contextGeneration = 1,
        profileId = null,
        projectId = null,
        workMode = true,
    )
    private fun document(id: String, owner: UiDocumentSource = source) = UiDocument(
        schemaVersion = 1,
        documentId = id,
        source = owner,
        revision = 1,
        title = "Result",
        nodes = emptyList(),
        actions = emptyList(),
        fallbackText = "",
    )

    @Test fun autoPublishedWorkTaskProjectionIsHiddenFromReplyTimeline() {
        assertTrue(document("work-live:assistant-message").isInlineWorkTaskCard())
    }

    @Test fun otherResultsRemainVisibleAndCannotBeHiddenByAnIdPrefixAlone() {
        assertFalse(document("generated-result").isInlineWorkTaskCard())
        assertFalse(document("work-live:unrelated-message").isInlineWorkTaskCard())
        assertFalse(document("work-live:assistant-message", source.copy(workMode = false))
            .isInlineWorkTaskCard())
    }
}

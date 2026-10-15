package com.labteto.dshmobile.local.presentation

import com.labteto.dshmobile.local.session.LocalHarnessMessage
import com.labteto.dshmobile.uicontract.UiMessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalSessionUiHistoryProjectionTest {
    @Test fun historicOwnerRowsBecomeReadOnlySemanticMessages() {
        val cases = listOf(
            "user" to UiMessageRole.USER,
            "assistant" to UiMessageRole.ASSISTANT,
            "tool" to UiMessageRole.TOOL,
            "system" to UiMessageRole.SYSTEM,
            "unexpected-role" to UiMessageRole.SYSTEM,
        )
        cases.forEachIndexed { index, (role, expectedRole) ->
            val ownerMessage = LocalHarnessMessage("historical-$index", role, "older content", createdAt = 42L)
            val projected = projectTranscriptHistoryMessage(ownerMessage)
            assertEquals(ownerMessage.id, projected.id)
            assertEquals(expectedRole, projected.role)
            assertEquals(ownerMessage.content, projected.text)
            assertEquals(42L, projected.createdAt)
            assertNull(projected.editableText)
            assertNull(projected.branch)
            assertEquals(ownerMessage.proactive, projected.proactive)
        }
    }
}

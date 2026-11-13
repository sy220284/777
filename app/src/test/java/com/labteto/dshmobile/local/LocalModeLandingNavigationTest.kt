package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.session.localPendingModeTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalModeLandingNavigationTest {
    @Test fun modeSelectionResumesOnlyPendingEditorNotAnyHistoricalConversation() {
        val pending = linkedMapOf(
            "chat-draft-old" to LocalUsageMode.CHAT,
            "work-draft" to LocalUsageMode.WORK,
            "chat-draft-new" to LocalUsageMode.CHAT,
        )
        assertEquals("chat-draft-new", localPendingModeTarget(LocalUsageMode.CHAT, pending))
        assertEquals("work-draft", localPendingModeTarget(LocalUsageMode.WORK, pending))
        assertNull(localPendingModeTarget(LocalUsageMode.CHAT, emptyMap()))
        assertNull(localPendingModeTarget(LocalUsageMode.WORK, emptyMap()))
    }
}

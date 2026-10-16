package com.labteto.dshmobile.ui.screens.local

import com.labteto.dshmobile.uicontract.UiReadingAnchor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalConversationReadingStateTest {
    @Test fun anchorsUseMessageIdentityAndRestoreWithoutWritingOrMixingSessions() {
        var saved = emptyList<String>()
        val owner = LocalConversationReadingState(onSave = { saved = it })
        val chat = UiReadingAnchor("message-chat", 73, false)
        val work = UiReadingAnchor("message-work", 0, true)
        owner.rememberAnchor("chat", chat)
        owner.rememberAnchor("work", work)
        val writes = mutableListOf<List<String>>()
        val restored = LocalConversationReadingState(saved, writes::add)
        assertEquals(chat, restored.anchor("chat"))
        assertEquals(work, restored.anchor("work"))
        assertNull(restored.anchor("other"))
        assertTrue(writes.isEmpty())
        restored.rememberAnchor("chat", chat.copy(followingTail = true))
        assertEquals(work, restored.anchor("work"))
        assertEquals(1, writes.size)
    }

    @Test fun processRestoredSearchTargetRemainsStableAndUntrustedOffsetsAreRejected() {
        var saved = emptyList<String>()
        val previous = LocalConversationReadingState(onSave = { saved = it })
        val target = UiReadingAnchor("very-old-message", 12, false)
        previous.rememberAnchor("session-a", target)
        val restarted = LocalConversationReadingState(saved)
        assertEquals(target, restarted.anchor("session-a"))
        assertNull(restarted.anchor("session-b"))

        val poisoned = LocalConversationReadingState(listOf(
            "session-a", "very-old-message", "2147483647", "false",
            "session-b", "message", "-2", "false",
            "session-c", "message", "30", "invalid",
            "session-valid", "known", "80", "false",
        ))
        assertNull(poisoned.anchor("session-a"))
        assertNull(poisoned.anchor("session-b"))
        assertNull(poisoned.anchor("session-c"))
        assertEquals(UiReadingAnchor("known", 80, false), poisoned.anchor("session-valid"))
    }

    @Test fun activeReadAnchorsClampOffscreenOffsetsAndRejectOversizedKeys() {
        val owner = LocalConversationReadingState()
        owner.rememberAnchor("session", UiReadingAnchor("target", Int.MAX_VALUE, false))
        assertEquals(1_000_000, owner.anchor("session")?.offsetPx)
        owner.rememberAnchor("session", UiReadingAnchor("target", -1, false))
        assertEquals(0, owner.anchor("session")?.offsetPx)
        owner.rememberAnchor("x".repeat(257), UiReadingAnchor("target", 0, false))
        owner.rememberAnchor("session", UiReadingAnchor("x".repeat(257), 0, false))
        assertNull(owner.anchor("x".repeat(257)))
        assertEquals("target", owner.anchor("session")?.messageId)
    }

    @Test fun oldSessionPositionsReleaseTheirEntryAtTheExistingUiRetentionBudget() {
        val owner = LocalConversationReadingState()
        repeat(MAX_LOCAL_SESSION_PRESENTATION_STATES) {
            owner.rememberAnchor("session-$it", UiReadingAnchor("message-$it", 0, false))
        }
        owner.rememberAnchor("session-0", UiReadingAnchor("message-0", 10, false))
        owner.rememberAnchor("new", UiReadingAnchor("message-new", 0, false))
        assertNull(owner.anchor("session-1"))
        assertEquals(10, owner.anchor("session-0")?.offsetPx)
    }
}

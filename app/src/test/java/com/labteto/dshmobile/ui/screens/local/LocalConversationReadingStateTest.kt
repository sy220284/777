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

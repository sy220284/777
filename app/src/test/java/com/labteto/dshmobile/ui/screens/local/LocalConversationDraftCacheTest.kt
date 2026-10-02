package com.labteto.dshmobile.ui.screens.local

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalConversationDraftCacheTest {
    @Test
    fun keepsOnlyTwelveMostRecentlyTouchedSessionDrafts() {
        val drafts = linkedMapOf<String, String>()
        repeat(13) { index ->
            drafts.putBoundedLocalDraft("session-$index", "draft-$index")
        }

        assertEquals(12, drafts.size)
        assertFalse("session-0" in drafts)
        assertEquals("draft-12", drafts["session-12"])
    }

    @Test
    fun touchingExistingDraftMovesItToRecentEndBeforeEviction() {
        val drafts = linkedMapOf<String, String>()
        repeat(12) { index ->
            drafts.putBoundedLocalDraft("session-$index", "draft-$index")
        }
        drafts.putBoundedLocalDraft("session-0", "updated")
        drafts.putBoundedLocalDraft("session-12", "new")

        assertTrue("session-0" in drafts)
        assertFalse("session-1" in drafts)
        assertEquals("updated", drafts["session-0"])
    }

    @Test
    fun blankDraftReleasesCachedSessionEntry() {
        val drafts = linkedMapOf("session" to "draft")

        drafts.putBoundedLocalDraft("session", "   ")

        assertTrue(drafts.isEmpty())
    }
}

package com.labteto.dshmobile.ui.screens.local

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalConversationDraftCacheTest {
    @Test
    fun keepsOnlyTwelveMostRecentlyTouchedSessionDrafts() {
        val drafts = LocalSessionDraftCache()
        repeat(13) { index ->
            drafts.putBoundedLocalDraft("session-$index", "draft-$index")
        }

        val saved = drafts.save()
        assertEquals(24, saved.size)
        assertFalse("session-0" in saved)
        assertEquals("draft-12", drafts["session-12"])
    }

    @Test
    fun touchingExistingDraftMovesItToRecentEndBeforeEviction() {
        val drafts = LocalSessionDraftCache()
        repeat(12) { index ->
            drafts.putBoundedLocalDraft("session-$index", "draft-$index")
        }
        drafts.putBoundedLocalDraft("session-0", "updated")
        drafts.putBoundedLocalDraft("session-12", "new")

        val saved = drafts.save()
        assertTrue("session-0" in saved)
        assertFalse("session-1" in saved)
        assertEquals("updated", drafts["session-0"])
    }

    @Test
    fun blankDraftReleasesCachedSessionEntry() {
        val drafts = LocalSessionDraftCache()
        drafts.putBoundedLocalDraft("session", "draft")

        drafts.putBoundedLocalDraft("session", "   ")

        assertTrue(drafts.save().isEmpty())
    }
}

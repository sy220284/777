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
    @Test
    fun renderersShareOneOwnerAndSessionDraftsStaySeparate() {
        val owner = LocalSessionDraftCache()
        val v6: com.labteto.dshmobile.uicontract.ConversationDraftUiPort = owner
        val next: com.labteto.dshmobile.uicontract.ConversationDraftUiPort = owner
        v6.updateDraft("chat", "unfinished question")
        next.updateDraft("work", "unfinished task")

        assertEquals("unfinished question", next.drafts.value["chat"])
        assertEquals("unfinished task", v6.drafts.value["work"])
        next.updateDraft("chat", "")
        assertFalse(v6.drafts.value.containsKey("chat"))
        assertEquals("unfinished task", v6.drafts.value["work"])
    }

    @Test
    fun savedStateRestoresRecencyAndOnlyPersistsSubsequentEdits() {
        val owner = LocalSessionDraftCache()
        repeat(12) { owner.updateDraft("session-$it", "draft-$it") }
        owner.updateDraft("session-0", "edited")
        val writes = mutableListOf<List<String>>()
        val recreated = LocalSessionDraftCache.restore(owner.save(), writes::add)
        assertTrue(writes.isEmpty())
        assertEquals(owner.drafts.value, recreated.drafts.value)

        recreated.updateDraft("new-session", "new draft")
        assertEquals("edited", recreated.drafts.value["session-0"])
        assertFalse(recreated.drafts.value.containsKey("session-1"))
        assertEquals(recreated.save(), writes.single())
    }

}

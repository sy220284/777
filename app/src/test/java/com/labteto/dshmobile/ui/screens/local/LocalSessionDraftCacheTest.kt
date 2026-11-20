package com.labteto.dshmobile.ui.screens.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalSessionDraftCacheTest {
    @Test fun textDraftsKeepMoreThanTwelveSessionsWithoutEviction() {
        val drafts = LocalSessionDraftCache()
        repeat(22) { index -> drafts.putLocalDraft("chat-$index", "第 $index 个故事的草稿") }
        assertEquals("第 0 个故事的草稿", drafts["chat-0"])
        assertEquals("第 21 个故事的草稿", LocalSessionDraftCache.restore(drafts.save())["chat-21"])
        assertEquals(44, drafts.save().size)
    }

    @Test fun deletingOneDraftDoesNotDeleteOtherSessions() {
        val drafts = LocalSessionDraftCache()
        drafts.putLocalDraft("a", "alpha")
        drafts.putLocalDraft("b", "beta")
        drafts.putLocalDraft("a", "")
        assertNull(drafts["a"])
        assertEquals("beta", drafts["b"])
    }
}

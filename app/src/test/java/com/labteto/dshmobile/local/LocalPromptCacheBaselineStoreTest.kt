package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.model.LocalPromptCacheBaselineStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalPromptCacheBaselineStoreTest {
    @Test
    fun baselinesAreIsolatedBySessionAndProfileAndBounded() {
        val store = LocalPromptCacheBaselineStore(maxEntries = 2)
        store.put("s1", "p1", "chat_foreground", "r1")
        store.put("s1", "p2", "work", "r2")

        assertEquals("r1", store.get("s1", "p1", "chat_foreground"))
        assertEquals("r2", store.get("s1", "p2", "work"))

        store.put("s2", "p1", "chat_foreground", "r3")

        assertEquals(2, store.size())
        assertNull(store.get("s1", "p1", "chat_foreground"))
        assertEquals("r2", store.get("s1", "p2", "work"))
        assertEquals("r3", store.get("s2", "p1", "chat_foreground"))
    }

    @Test
    fun chatReplyAndBackgroundPlannerKeepSeparateResponseIdBaselines() {
        val store = LocalPromptCacheBaselineStore(maxEntries = 4)
        store.put("chat", "route", "chat_foreground", "reply-1")
        store.put("chat", "route", "chat_auxiliary_10000", "planner-1")
        store.put("chat", "route", "chat_foreground", "reply-2")
        assertEquals("reply-2", store.get("chat", "route", "chat_foreground"))
        assertEquals("planner-1", store.get("chat", "route", "chat_auxiliary_10000"))
        assertNull(store.get("chat", "route", "work"))
        assertEquals(2, store.size())
        store.clearSession("chat")
        assertEquals(0, store.size())
    }

    @Test
    fun clearingOneSessionDoesNotDropOtherProfiles() {
        val store = LocalPromptCacheBaselineStore(maxEntries = 4)
        store.put("s1", "p1", "chat_foreground", "r1")
        store.put("s1", "p2", "work", "r2")
        store.put("s2", "p1", "chat_foreground", "r3")

        store.clearSession("s1")

        assertNull(store.get("s1", "p1", "chat_foreground"))
        assertNull(store.get("s1", "p2", "work"))
        assertEquals("r3", store.get("s2", "p1", "chat_foreground"))
    }
}

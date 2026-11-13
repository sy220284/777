package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.model.LocalPromptCacheBaselineStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalPromptCacheBaselineStoreTest {
    @Test
    fun baselinesAreIsolatedBySessionAndProfileAndBounded() {
        val store = LocalPromptCacheBaselineStore(maxEntries = 2)
        store.put("s1", "p1", "r1")
        store.put("s1", "p2", "r2")

        assertEquals("r1", store.get("s1", "p1"))
        assertEquals("r2", store.get("s1", "p2"))

        store.put("s2", "p1", "r3")

        assertEquals(2, store.size())
        assertNull(store.get("s1", "p1"))
        assertEquals("r2", store.get("s1", "p2"))
        assertEquals("r3", store.get("s2", "p1"))
    }

    @Test
    fun clearingOneSessionDoesNotDropOtherProfiles() {
        val store = LocalPromptCacheBaselineStore(maxEntries = 4)
        store.put("s1", "p1", "r1")
        store.put("s1", "p2", "r2")
        store.put("s2", "p1", "r3")

        store.clearSession("s1")

        assertNull(store.get("s1", "p1"))
        assertNull(store.get("s1", "p2"))
        assertEquals("r3", store.get("s2", "p1"))
    }
}

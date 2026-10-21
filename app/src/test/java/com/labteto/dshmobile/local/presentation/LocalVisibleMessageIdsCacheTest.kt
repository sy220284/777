package com.labteto.dshmobile.local.presentation

import com.labteto.dshmobile.local.session.LocalHarnessMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class LocalVisibleMessageIdsCacheTest {
    private fun msg(id: String) = LocalHarnessMessage(id = id, role = "assistant", content = id, createdAt = 1)

    @Test fun unchangedListReusesIdsButSameLengthEditAndSessionABAInvalidate() {
        val cache = LocalVisibleMessageIdsCache()
        val old = listOf(msg("a"), msg("b"))
        val initial = cache.get("session-a", old)
        assertEquals(setOf("a", "b"), initial)
        assertSame(initial, cache.get("session-a", old))

        val changed = cache.get("session-a", listOf(msg("a"), msg("c")))
        assertEquals(setOf("a", "c"), changed)
        assertNotSame(initial, changed)

        val other = cache.get("session-b", old)
        assertEquals(setOf("a", "b"), other)
        assertNotSame(other, cache.get("session-a", old))
    }

    @Test fun longHistoryDeduplicatesIdsAndReusesOnlyStableInput() {
        val cache = LocalVisibleMessageIdsCache()
        val messages = (0..2_000).map { msg("m$it") } + msg("m100")
        val first = cache.get("session-1", messages)
        assertEquals(2_001, first.size)
        repeat(500) { assertSame(first, cache.get("session-1", messages)) }
        assertEquals(2_000, cache.get("session-1", messages.drop(1)).size)
    }
}

package com.labteto.dshmobile.ui.screens.local

import com.labteto.dshmobile.local.session.LocalHarnessMessage
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalTranscriptVisibleWindowTest {
    private fun messages(count: Int) = (1..count).map { index ->
        LocalHarnessMessage("m$index", "assistant", "reply $index", createdAt = index.toLong())
    }

    @Test fun openingLongRuntimeHistoryOnlyComposesLatestTwelve() {
        val window = localTranscriptVisibleLiveWindow(messages(200), null, 12)
        assertEquals((189..200).map { "m$it" }, window.map { it.id })
    }

    @Test fun newMessagesDoNotEraseTheOriginalReadingAnchor() {
        val window = localTranscriptVisibleLiveWindow(messages(205), "m189", 12)
        assertEquals("m189", window.first().id)
        assertEquals("m205", window.last().id)
    }

    @Test fun rewrittenOrEvictedAnchorFallsBackToBoundedTail() {
        val window = localTranscriptVisibleLiveWindow(messages(200), "missing", 12)
        assertEquals(12, window.size)
        assertEquals("m189", window.first().id)
    }
}

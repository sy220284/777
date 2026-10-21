package com.labteto.dshmobile.ui.screens.local

import com.labteto.dshmobile.local.LocalUsageMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalConversationEmptyStateTest {
    @Test fun chatNeverShowsWorkQuickStartSuggestions() {
        // This also covers the unbound-persona empty chat in the screenshot.
        assertFalse(shouldShowEmptyLocalHarness(LocalUsageMode.CHAT, transcriptEmpty = true))
        assertFalse(shouldShowEmptyLocalHarness(LocalUsageMode.CHAT, transcriptEmpty = false))
    }

    @Test fun workRetainsQuickStartOnlyWhenTranscriptIsEmpty() {
        assertTrue(shouldShowEmptyLocalHarness(LocalUsageMode.WORK, transcriptEmpty = true))
        assertFalse(shouldShowEmptyLocalHarness(LocalUsageMode.WORK, transcriptEmpty = false))
    }
}

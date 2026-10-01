package com.labteto.dshmobile.ui.screens.settings

import com.labteto.dshmobile.local.TokenUsageGroupKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UsageDetailSelectionStateTest {
    @Test
    fun groupSelectionSurvivesSavedStateRoundTrip() {
        val original = UsageDetailSelection.Group(
            kind = TokenUsageGroupKind.SESSION,
            key = "session-42",
            title = "测试会话",
        )

        assertEquals(original, decodeUsageDetailSelection(encodeUsageDetailSelection(original)))
    }

    @Test
    fun requestSelectionSurvivesSavedStateRoundTrip() {
        val original = UsageDetailSelection.Request("req-42")

        assertEquals(original, decodeUsageDetailSelection(encodeUsageDetailSelection(original)))
    }

    @Test
    fun malformedSavedSelectionFailsClosed() {
        assertNull(decodeUsageDetailSelection(listOf("group", "UNKNOWN", "key", "title")))
        assertNull(decodeUsageDetailSelection(listOf("request", "")))
        assertNull(decodeUsageDetailSelection(emptyList()))
    }
}

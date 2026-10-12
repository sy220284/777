package com.labteto.dshmobile.ui.screens.local

import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.session.LocalSessionSummary
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalDrawerCrossModeSearchTest {
    private val sessions = listOf(
        LocalSessionSummary("work", "构建流水线", 2L, usageMode = LocalUsageMode.WORK),
        LocalSessionSummary("chat", "构建人物", 3L, usageMode = LocalUsageMode.CHAT),
        LocalSessionSummary("hidden", "构建草稿", 4L, usageMode = LocalUsageMode.CHAT, blank = true),
    )

    @Test fun ordinaryDrawerStaysWithinSelectedMode() {
        val result = localDrawerSessionSections(
            sessions, LocalUsageMode.WORK, emptySet(), "", emptyMap(),
        )
        assertEquals(listOf("work"), result.recent.map { it.id })
    }

    @Test fun activeQueryFindsSessionsInBothModesButNeverBlankOnes() {
        val result = localDrawerSessionSections(
            sessions, LocalUsageMode.WORK, emptySet(), "构建", emptyMap(),
            includeOtherModes = true,
        )
        assertEquals(listOf("chat", "work"), result.recent.map { it.id })
    }
}

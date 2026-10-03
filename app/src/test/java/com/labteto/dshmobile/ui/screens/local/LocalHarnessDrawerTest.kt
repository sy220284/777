package com.labteto.dshmobile.ui.screens.local

import com.labteto.dshmobile.local.LocalSessionSummary
import com.labteto.dshmobile.local.LocalUsageMode
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalHarnessDrawerTest {
    @Test
    fun sessionSectionsSplitPinnedAndRecentWithinActiveMode() {
        val sessions = listOf(
            LocalSessionSummary(
                id = "chat-new",
                title = "最近聊天",
                updatedAt = 30L,
                usageMode = LocalUsageMode.CHAT,
            ),
            LocalSessionSummary(
                id = "chat-pin",
                title = "置顶聊天",
                updatedAt = 10L,
                usageMode = LocalUsageMode.CHAT,
            ),
            LocalSessionSummary(
                id = "work",
                title = "工作任务",
                updatedAt = 40L,
                usageMode = LocalUsageMode.WORK,
            ),
        )

        val sections = localDrawerSessionSections(
            sessions = sessions,
            usageMode = LocalUsageMode.CHAT,
            pinnedSessionIds = setOf("chat-pin"),
            query = "",
            sessionTitleOverrides = emptyMap(),
        )

        assertEquals(listOf("chat-pin"), sections.pinned.map { it.id })
        assertEquals(listOf("chat-new"), sections.recent.map { it.id })
    }

    @Test
    fun sessionSectionsSearchesDisplayTitleOverrides() {
        val sessions = listOf(
            LocalSessionSummary(
                id = "chat",
                title = "旧标题",
                updatedAt = 10L,
                usageMode = LocalUsageMode.CHAT,
            ),
        )

        val sections = localDrawerSessionSections(
            sessions = sessions,
            usageMode = LocalUsageMode.CHAT,
            pinnedSessionIds = emptySet(),
            query = "新标题",
            sessionTitleOverrides = mapOf("chat" to "新标题"),
        )

        assertEquals(listOf("chat"), sections.recent.map { it.id })
    }
}

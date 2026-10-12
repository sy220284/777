package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.chat.LocalChatMode
import com.labteto.dshmobile.local.session.LocalSessionSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalModeLandingNavigationTest {
    private fun summary(
        id: String,
        mode: LocalUsageMode,
        blank: Boolean,
        chatMode: String? = null,
        projectId: String? = null,
    ) = LocalSessionSummary(
        id = id,
        title = id,
        updatedAt = 1L,
        usageMode = mode,
        blank = blank,
        chatMode = chatMode,
        projectId = projectId,
    )

    @Test
    fun selectingChatModeDoesNotAutomaticallyOpenOldHistoryOrGroup() {
        val sessions = listOf(
            summary("old-chat", LocalUsageMode.CHAT, false, LocalChatMode.SINGLE.name),
            summary("group", LocalUsageMode.CHAT, true, LocalChatMode.GROUP.name),
            summary("empty-chat", LocalUsageMode.CHAT, true, LocalChatMode.SINGLE.name),
        )
        assertEquals("empty-chat", localModeLandingSession(LocalUsageMode.CHAT, sessions)?.id)
        assertNull(localModeLandingSession(LocalUsageMode.CHAT, sessions.take(2)))
    }

    @Test
    fun selectingWorkModeDoesNotAutomaticallyOpenOldJobsOrProjects() {
        val sessions = listOf(
            summary("old-work", LocalUsageMode.WORK, false),
            summary("project", LocalUsageMode.WORK, true, projectId = "project-1"),
            summary("empty-work", LocalUsageMode.WORK, true),
        )
        assertEquals("empty-work", localModeLandingSession(LocalUsageMode.WORK, sessions)?.id)
        assertNull(localModeLandingSession(LocalUsageMode.WORK, sessions.take(2)))
    }
}

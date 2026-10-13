package com.labteto.dshmobile.ui.screens.local

import com.labteto.dshmobile.automation.AutomationMode
import com.labteto.dshmobile.automation.AutomationTask
import com.labteto.dshmobile.local.tools.LocalWorkspaceFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalDrawerUnifiedSearchTest {
    @Test fun taskSearchFindsRealSchedulesAcrossModes() {
        val tasks = listOf(
            AutomationTask("one", "整理昨天的文章", 1L, 2L, mode = AutomationMode.WORK),
            AutomationTask("two", "提醒绫华写日记", 1L, 2L, mode = AutomationMode.CHAT),
        )
        assertEquals(listOf("one"), localDrawerMatchingTasks(tasks, "文章").map { it.id })
        assertEquals(listOf("two"), localDrawerMatchingTasks(tasks, "绫华").map { it.id })
        assertEquals(2, localDrawerMatchingTasks(tasks, " ").let { 2 - it.size })
    }

    @Test fun workspaceSearchKeepsRelativeSafePathsAndNoDuplicates() {
        val files = listOf(
            LocalWorkspaceFile("drafts/chapter-01.md", 1L, 1L),
            LocalWorkspaceFile("drafts/chapter-01.md", 1L, 1L),
            LocalWorkspaceFile("../secret/chapter-02.md", 1L, 1L),
            LocalWorkspaceFile(".adsh/chapter-03.md", 1L, 1L),
        )
        assertEquals(listOf("drafts/chapter-01.md"),
            localDrawerMatchingFiles(files, "chapter").map { it.path })
        assertTrue(localDrawerMatchingFiles(files, "").isEmpty())
    }
}

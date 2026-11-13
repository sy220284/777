package com.labteto.dshmobile.ui.screens.local

import com.labteto.dshmobile.local.tools.LocalWorkspaceFile
import org.junit.Assert.assertEquals
import org.junit.Test

class WorkspaceFileSectionsTest {
    @Test fun groupsPhysicalFilesWithoutMovingThem() {
        val files = listOf(
            LocalWorkspaceFile("任务/task/draft.md", 1L, 1L),
            LocalWorkspaceFile("交付成果/task/result.pdf", 2L, 2L),
            LocalWorkspaceFile("src/main.kt", 3L, 3L),
            LocalWorkspaceFile(".dsh/tools/README.md", 4L, 4L),
        )
        assertEquals(listOf(files[0]), workspaceSectionFiles(files, 0))
        assertEquals(listOf(files[1]), workspaceSectionFiles(files, 1))
        assertEquals(listOf(files[2], files[3]), workspaceSectionFiles(files, 2))
        assertEquals(emptyList<LocalWorkspaceFile>(), workspaceSectionFiles(files, -1))
    }
}

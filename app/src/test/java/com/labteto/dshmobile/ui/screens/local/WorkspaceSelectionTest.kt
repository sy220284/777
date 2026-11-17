package com.labteto.dshmobile.ui.screens.local

import org.junit.Assert.*
import org.junit.Test

class WorkspaceSelectionTest {
    @Test fun refreshingCompleteDirectoryKeepsExistingSelectionAndDropsRemovedFiles() {
        assertEquals(listOf("a.md"), reconcileWorkspaceSelection(listOf("a.md", "gone.md"), setOf("a.md", "new.md"), true))
    }
    @Test fun firstPageMustNotDiscardSelectedFilesFromLaterPages() {
        assertEquals(listOf("a.md", "page3.md"), reconcileWorkspaceSelection(listOf("a.md", "page3.md"), setOf("a.md"), false))
    }
    @Test fun restoredSelectionBelongsOnlyToItsOriginalProject() {
        val canSave = object : androidx.compose.runtime.saveable.SaverScope {
            override fun canBeSaved(value: Any): Boolean = true
        }
        val original = workspaceSelectionSaver(listOf("WORKSPACE", "project-a", "files", ".", "1"))
        val saved = with(original) { canSave.save(WorkspaceSelectionState(true, listOf("report.md"))) }!!
        assertEquals(listOf("report.md"), original.restore(saved)?.paths?.toList())
        assertTrue(original.restore(saved)!!.active.value)
        val other = workspaceSelectionSaver(listOf("WORKSPACE", "project-b", "files", ".", "1"))
        assertTrue(other.restore(saved)!!.paths.isEmpty())
        assertFalse(other.restore(saved)!!.active.value)
    }
}

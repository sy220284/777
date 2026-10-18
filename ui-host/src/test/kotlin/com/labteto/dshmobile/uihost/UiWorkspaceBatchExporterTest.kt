package com.labteto.dshmobile.uihost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UiWorkspaceBatchExporterTest {
    @Test fun preservesOneNamePerDistinctFile() {
        val names = uiWorkspaceZipMemberNames(listOf("work/a.txt", "work/b.md"))
        assertEquals(listOf("a.txt", "b.md"), names)
    }

    @Test fun sameFileNameInDifferentDirectoriesGetsUniqueZipEntry() {
        val names = uiWorkspaceZipMemberNames(listOf("a/report.txt", "b/report.txt", "c/report.TXT"))
        assertEquals(listOf("report.txt", "report (2).txt", "report (3).TXT"), names)
    }

    @Test fun handlesWindowsPathsAndControlCharacters() {
        val names = uiWorkspaceZipMemberNames(listOf("c:\\doc\\note.txt", "data/invalid\u0000name.txt"))
        assertEquals("note.txt", names.first())
        assertTrue(names.last().contains("_"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsEmptySelection() { uiWorkspaceZipMemberNames(emptyList()) }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsDuplicateSourcePath() {
        uiWorkspaceZipMemberNames(listOf("a/a.txt", "a/a.txt"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOverHundredEntries() {
        uiWorkspaceZipMemberNames((0..100).map { "file$it.txt" })
    }
}

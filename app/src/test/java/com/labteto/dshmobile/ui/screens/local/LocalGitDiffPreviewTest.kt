package com.labteto.dshmobile.ui.screens.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalGitDiffPreviewTest {
    @Test fun groupsHunksByFileAndDoesNotConfuseFileHeadersWithAddedLines() {
        val patch = """
            diff --git a/app/src/main.kt b/app/src/main.kt
            index abc123..def456 100644
            --- a/app/src/main.kt
            +++ b/app/src/main.kt
            @@ -1,2 +1,3 @@
            -old
            +new
             context
            diff --git a/readme.md b/readme.md
            --- a/readme.md
            +++ b/readme.md
            @@ -1 +1 @@
            -before
            +after
        """.trimIndent()
        val result = parseGitDiffPreview(patch)
        assertEquals(2, result.files.size)
        assertEquals("app/src/main.kt", result.files[0].path)
        assertEquals(1, result.files[0].added)
        assertEquals(1, result.files[0].removed)
        assertEquals("readme.md", result.files[1].path)
        assertEquals(1, result.files[1].added)
        assertTrue(result.files[0].lines.any { it.kind == GitDiffLineKind.HUNK })
        assertTrue(result.files[0].lines.any { it.kind == GitDiffLineKind.META })
    }

    @Test fun decodesGitQuotedPathsWithoutLosingUnicode() {
        val chinese = "diff --git \"a/\\345\\220\\253 \\347\\251\\272 \\346\\240\\274.txt\" \"b/\\345\\220\\253 \\347\\251\\272 \\346\\240\\274.txt\""
        val result = parseGitDiffPreview("$chinese\n@@ -1 +1 @@\n-before\n+after")
        assertEquals("含 空 格.txt", result.files.single().path)
        assertEquals(1, result.files.single().added)
        assertEquals(1, result.files.single().removed)

        val backslash = "diff --git \"a/a\\\\b.kt\" \"b/a\\\\b.kt\""
        assertEquals("a\\b.kt", gitDiffTargetPath(backslash))
        val tab = "diff --git \"a/a\\tb.kt\" \"b/a\\tb.kt\""
        assertEquals("a\tb.kt", gitDiffTargetPath(tab))
        assertEquals("main.kt", gitDiffTargetPath("diff --git a/main.kt b/main.kt"))
        assertEquals(null, gitDiffTargetPath("not a git diff header"))
    }

    @Test fun capsUiRowsAndFilesWithoutChangingTheUnderlyingPatch() {
        val patch = (1..6).joinToString("\n") { index ->
            "diff --git a/f$index.kt b/f$index.kt\n@@ -1 +1 @@\n-old\n+new"
        }
        val result = parseGitDiffPreview(patch, maxFiles = 2, maxRows = 3)
        assertEquals(2, result.files.size)
        assertEquals(4, result.omittedFiles)
        assertTrue(result.omittedLines > 0)
        assertEquals(6, Regex("diff --git").findAll(patch).count())
    }

    @Test fun aHunkWithoutGitHeaderStillRendersAsDiffContent() {
        val result = parseGitDiffPreview("@@ -1 +1 @@\n-before\n+after")
        assertEquals("差异内容", result.files.single().path)
        assertEquals(1, result.files.single().added)
        assertEquals(1, result.files.single().removed)
    }
}

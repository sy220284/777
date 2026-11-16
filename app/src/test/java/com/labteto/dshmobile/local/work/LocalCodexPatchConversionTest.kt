package com.labteto.dshmobile.local.work

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalCodexPatchConversionTest {
    private fun git(root: File, patch: String, check: Boolean): Pair<Int, String> {
        val args = mutableListOf("git", "apply")
        if (check) args += "--check"
        args += listOf("--whitespace=nowarn", "-")
        val process = ProcessBuilder(args).directory(root).redirectErrorStream(true).start()
        process.outputStream.bufferedWriter().use { it.write(patch) }
        val output = process.inputStream.bufferedReader().readText()
        return process.waitFor() to output
    }

    @Test fun addUpdateDeleteAndMoveAreAppliedAsOneCheckedGitPatch() {
        val root = Files.createTempDirectory("codex-native-patch-").toFile()
        try {
            File(root, "update.txt").writeText("alpha\nbeta\ngamma\n")
            File(root, "delete.txt").writeText("remove\n")
            File(root, "old.txt").writeText("old\n")
            val native = """
                *** Begin Patch
                *** Update File: update.txt
                @@
                 alpha
                -beta
                +new
                 gamma
                *** Add File: added.txt
                +created
                *** Delete File: delete.txt
                *** Update File: old.txt
                *** Move to: moved.txt
                @@
                -old
                +moved
                *** End Patch
            """.trimIndent()
            val patch = normalizeWorkspacePatch(native, root)
            validateWorkspacePatchPaths(patch)
            val preflight = git(root, patch, true)
            assertEquals(preflight.second, 0, preflight.first)
            val applied = git(root, patch, false)
            assertEquals(applied.second, 0, applied.first)
            assertEquals("alpha\nnew\ngamma\n", File(root, "update.txt").readText())
            assertEquals("created\n", File(root, "added.txt").readText())
            assertFalse(File(root, "delete.txt").exists())
            assertFalse(File(root, "old.txt").exists())
            assertEquals("moved\n", File(root, "moved.txt").readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun relativePathsAreCheckedBeforeAnyFileMutation() {
        val root = Files.createTempDirectory("codex-patch-invalid-").toFile()
        try {
            File(root, "current.txt").writeText("old\n")
            val cases = listOf(
                "*** Begin Patch\n*** Add File: ../escape.txt\n+bad\n*** End Patch",
                "*** Begin Patch\n*** Add File: /absolute.txt\n+bad\n*** End Patch",
                "*** Begin Patch\n*** Delete File: current.txt\n*** Delete File: current.txt\n*** End Patch",
                "*** Begin Patch\n*** Update File: current.txt\n@@\n-wrong\n+bad\n*** End Patch",
            )
            for (patch in cases) {
                assertThrows(RuntimeException::class.java) { normalizeWorkspacePatch(patch, root) }
            }
            assertEquals("old\n", File(root, "current.txt").readText())
            assertFalse(File(root.parentFile, "escape.txt").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun ordinaryUnifiedDiffKeepsItsOriginalBytesAndEndOfFileMarkerWorks() {
        val root = Files.createTempDirectory("codex-patch-eof-").toFile()
        try {
            val ordinary = "diff --git a/a.txt b/a.txt\n--- a/a.txt\n+++ b/a.txt\n"
            assertEquals(ordinary, normalizeWorkspacePatch(ordinary, root))
            File(root, "bottom.txt").writeText("top\nend\n")
            val native = "*** Begin Patch\n*** Update File: bottom.txt\n@@\n-end\n+finish\n*** End of File\n*** End Patch"
            val patch = normalizeWorkspacePatch(native, root)
            val preflight = git(root, patch, true)
            assertEquals(preflight.second, 0, preflight.first)
            assertEquals(git(root, patch, false).second, 0, git(root, patch, true).first)
            assertEquals("top\nfinish\n", File(root, "bottom.txt").readText())
        } finally {
            root.deleteRecursively()
        }
    }
}

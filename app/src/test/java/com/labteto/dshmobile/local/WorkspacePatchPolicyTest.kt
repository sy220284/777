package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.work.validateWorkspacePatchPaths
import org.junit.Assert.assertThrows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.nio.file.Files
import org.junit.Test

class WorkspacePatchPolicyTest {
    @Test
    fun oneVerboseGitApplyValidatesEveryFileBeforeApplyingAnyChange() {
        val directory = Files.createTempDirectory("git-apply-atomicity").toFile()
        try {
            val init = ProcessBuilder("git", "init", "-q")
                .directory(directory).start()
            assertEquals(0, init.waitFor())
            directory.resolve("a.txt").writeText("alpha\\n".replace("\\n", "\n"))
            directory.resolve("b.txt").writeText("beta\\n".replace("\\n", "\n"))
            fun apply(patch: String): Pair<Int, String> {
                val process = ProcessBuilder(
                    "git", "apply", "--verbose", "--whitespace=nowarn", "-",
                ).directory(directory).start()
                process.outputStream.writer().use { it.write(patch) }
                val detail = process.errorStream.bufferedReader().use { it.readText() }
                return process.waitFor() to detail
            }
            val template = """
                diff --git a/a.txt b/a.txt
                --- a/a.txt
                +++ b/a.txt
                @@ -1 +1 @@
                -alpha
                +changed
                diff --git a/b.txt b/b.txt
                --- a/b.txt
                +++ b/b.txt
                @@ -1 +1 @@
                -%OLD%
                +changed
            """.trimIndent() + "\n"
            val rejected = apply(template.replace("%OLD%", "missing"))
            assertTrue(rejected.first != 0)
            assertEquals("alpha\n", directory.resolve("a.txt").readText())
            assertEquals("beta\n", directory.resolve("b.txt").readText())
            val accepted = apply(template.replace("%OLD%", "beta"))
            assertEquals(0, accepted.first)
            assertTrue(accepted.second.contains("Applied patch a.txt cleanly."))
            assertTrue(accepted.second.contains("Applied patch b.txt cleanly."))
            assertEquals("changed\n", directory.resolve("a.txt").readText())
            assertEquals("changed\n", directory.resolve("b.txt").readText())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun acceptsOrdinaryWorkspacePatchAndDeletion() {
        validateWorkspacePatchPaths(
            """
            diff --git a/src/old.txt b/src/old.txt
            deleted file mode 100644
            --- a/src/old.txt
            +++ /dev/null
            @@ -1 +0,0 @@
            -old
            """.trimIndent(),
        )
    }

    @Test
    fun acceptsTraditionalSpaceSeparatedTimestampsWithoutTreatingThemAsPathText() {
        validateWorkspacePatchPaths(
            """
            diff --git a/src/file.txt b/src/file.txt
            --- a/src/file.txt 2026-10-02 12:34:56.123456789 +0900
            +++ b/src/file.txt 2026-10-02 12:35:01 +0900
            @@ -1 +1 @@
            -old
            +new
            """.trimIndent(),
        )
    }

    @Test
    fun rejectsTraversalAbsoluteAndAmbiguousPaths() {
        for (patch in listOf(
            """
            diff --git a/src/a.txt b/../outside.txt
            --- a/src/a.txt
            +++ b/../outside.txt
            """.trimIndent(),
            """
            --- /etc/hosts
            +++ /etc/hosts
            """.trimIndent(),
            """
            rename from src/a.txt
            rename to ../outside.txt
            """.trimIndent(),
            """
            --- "a/path with spaces.txt"
            +++ "b/path with spaces.txt"
            """.trimIndent(),
        )) {
            assertThrows(IllegalArgumentException::class.java) {
                validateWorkspacePatchPaths(patch)
            }
        }
    }
}

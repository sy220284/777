package com.labteto.dshmobile.local.files

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalWorkspaceFileChangesTest {
    @Test fun plansWatchersForMissingIntermediateParentsAndExistingFiles() {
        val dir = Files.createTempDirectory("workspace-observer-").toFile()
        try {
            val workspace = dir.resolve("root").apply { mkdirs() }
            val folder = workspace.resolve("existing").apply { mkdirs() }
            folder.resolve("task.md").writeText("draft")
            val plan = workspaceFileWatchTargets(
                workspace,
                listOf("existing/task.md", "future/missing.txt"),
            )
            assertEquals(setOf("existing", "future"), plan.getValue(workspace.canonicalFile))
            assertEquals(setOf("task.md"), plan.getValue(folder.canonicalFile))
            workspace.resolve("future").mkdirs()
            val restarted = workspaceFileWatchTargets(workspace, listOf("future/missing.txt"))
            assertEquals(setOf("missing.txt"), restarted.getValue(
                workspace.resolve("future").canonicalFile))
        } finally { dir.deleteRecursively() }
    }

    @Test fun rejectsSymlinksAndTraversalWithoutWatchingOutsideFiles() {
        val dir = Files.createTempDirectory("workspace-observer-security-").toFile()
        try {
            val workspace = dir.resolve("root").apply { mkdirs() }
            val outside = dir.resolve("outside").apply { mkdirs() }
            val link = workspace.resolve("shortcut")
            Files.createSymbolicLink(link.toPath(), outside.toPath())
            val planned = workspaceFileWatchTargets(
                workspace,
                listOf("../outside/secret.txt", "/etc/passwd", "shortcut/secret.txt",
                    "valid/../../outside", "C:\\outside", "", "safe.md"),
            )
            assertEquals(setOf("safe.md"), planned.getValue(workspace.canonicalFile))
            assertEquals(1, planned.size)
            assertFalse(planned.keys.any { it == outside.canonicalFile })
        } finally { dir.deleteRecursively() }
    }

    @Test fun rootWatchReceivesAnyDirectChildAndBoundsResourceCount() {
        val dir = Files.createTempDirectory("workspace-observer-root-").toFile()
        try {
            val workspace = dir.resolve("root").apply { mkdirs() }
            assertEquals(setOf("*"), workspaceFileWatchTargets(workspace, listOf("."))
                .getValue(workspace.canonicalFile))
            // Hundreds of files in one directory share one native observer.
            assertEquals(129, workspaceFileWatchTargets(workspace,
                List(129) { "f-$it.txt" }).getValue(workspace.canonicalFile).size)
            val directories = List(129) { index ->
                workspace.resolve("directory-$index").apply { mkdirs() }
                "directory-$index/file.txt"
            }
            assertTrue(runCatching {
                workspaceFileWatchTargets(workspace, directories)
            }.isFailure)
        } finally { dir.deleteRecursively() }
    }
}

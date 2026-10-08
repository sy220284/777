package com.labteto.dshmobile.ui.screens.local

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalWorkspaceShareTest {
    @Test
    fun oneAndManyFilesKeepOrderAndFilename() {
        val root = Files.createTempDirectory("workspace-share").toFile()
        try {
            root.resolve("reports").mkdirs()
            root.resolve("reports/first.pdf").writeText("pdf")
            root.resolve("reports/second.txt").writeText("text")
            assertEquals(
                listOf("first.pdf", "second.txt"),
                resolveLocalWorkspaceShareFiles(
                    root, listOf("reports/first.pdf", "reports/second.txt", "reports/first.pdf"),
                ).map { it.name },
            )
            assertEquals("application/pdf", localWorkspaceShareMime(listOf("application/pdf")))
            assertEquals("image/*", localWorkspaceShareMime(listOf("image/png", "image/jpeg")))
            assertEquals("*/*", localWorkspaceShareMime(listOf("image/png", "application/pdf")))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsTraversalInternalDataAndInvalidPaths() {
        val root = Files.createTempDirectory("workspace-share-deny").toFile()
        try {
            root.resolve(".adsh").mkdirs()
            root.resolve(".adsh/secrets.json").writeText("private")
            val badPaths = listOf(
                "", "../outside.txt", "/etc/passwd", "a/../b", "a//b",
                "a/./b", ".adsh/secrets.json", "a\\b",
            )
            badPaths.forEach { path ->
                assertFalse("must reject $path", isShareableWorkspacePath(path))
                assertThrows(IllegalArgumentException::class.java) {
                    resolveLocalWorkspaceShareFiles(root, listOf(path))
                }
            }
            assertThrows(IllegalArgumentException::class.java) {
                resolveLocalWorkspaceShareFiles(root, emptyList())
            }
            assertThrows(IllegalArgumentException::class.java) {
                resolveLocalWorkspaceShareFiles(root, List(MAX_LOCAL_SHARE_FILES + 1) { "test$it.txt" })
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun refusesSymlinkToOutsideOrAnotherWorkspaceFile() {
        val root = Files.createTempDirectory("workspace-share-links").toFile()
        val outside = Files.createTempFile("outside-share", ".txt").toFile()
        try {
            root.resolve("inside.txt").writeText("inside")
            Files.createSymbolicLink(root.resolve("link.txt").toPath(), outside.toPath())
            Files.createSymbolicLink(root.resolve("alias.txt").toPath(), root.resolve("inside.txt").toPath())
            for (path in listOf("link.txt", "alias.txt")) {
                assertThrows(IllegalArgumentException::class.java) {
                    resolveLocalWorkspaceShareFiles(root, listOf(path))
                }
            }
            assertTrue(resolveLocalWorkspaceShareFiles(root, listOf("inside.txt")).single().isFile)
        } finally {
            root.deleteRecursively()
            outside.delete()
        }
    }

    @Test
    fun rejectsMissingFilesAndDirectories() {
        val root = Files.createTempDirectory("workspace-share-missing").toFile()
        try {
            root.resolve("folder").mkdirs()
            for (path in listOf("folder", "absent.pdf")) {
                assertThrows(IllegalArgumentException::class.java) {
                    resolveLocalWorkspaceShareFiles(root, listOf(path))
                }
            }
        } finally {
            root.deleteRecursively()
        }
    }
}

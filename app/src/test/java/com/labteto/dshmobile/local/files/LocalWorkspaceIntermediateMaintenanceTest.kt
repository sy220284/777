package com.labteto.dshmobile.local.files

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalWorkspaceIntermediateMaintenanceTest {
    private val day = 24L * 60 * 60 * 1000

    @Test fun startupSweepRemovesOnlyOldReproducibleIntermediates() {
        val root = Files.createTempDirectory("cache-maintenance-").toFile()
        try {
            val now = System.currentTimeMillis()
            fun fixture(relative: String, ageDays: Long): File = File(root, relative).also {
                assertTrue(it.parentFile.mkdirs() || it.parentFile.isDirectory)
                it.writeText("generated data")
                assertTrue(it.setLastModified(now - ageDays * day))
            }
            val staleIndex = fixture(".dsh/text-index/" + "a".repeat(64) + ".index", 16)
            val recentIndex = fixture(".dsh/archive-index/" + "b".repeat(64) + ".index", 3)
            val staleIndexTemp = fixture(".dsh/text-index/." + "c".repeat(64) +
                ".index-12345678-1234-1234-1234-123456789abc.tmp", 3)
            val staleImport = fixture(".dsh/attachments/.incoming-12345678-1234-1234-1234-123456789abc", 3)
            val publishedAttachment = fixture(".dsh/attachments/attachment.jpg", 365)
            val nestedTemp = fixture(".dsh/write-temp/.write-abcd1234567890.tmp", 3)
            val activeTemp = fixture(".dsh/write-temp/.write-123abc7890.tmp", 0)
            val userLookalike = fixture("repository/src/.write-unsafe123.tmp", 200)
            val pendingResume = fixture("repository/src/.module.kt.write-in-progress", 400)
            val userSource = fixture("repository/src/module.kt", 500)
            val output = fixture("repository/src/notes.tmp", 500)
            val staleFetch = fixture(".dsh/fetches/fetch-1794574067369-24d64294.txt", 31)
            val freshFetch = fixture(".dsh/fetches/fetch-1794574067370-24d64295.txt", 2)
            val oldUserFile = fixture(".dsh/fetches/notes.txt", 200)
            val staleQuery = fixture(".dsh/queries/query-1794574067369-24d64294.json", 40)
            val lockMarker = fixture(".dsh/write-locks/a1.lck", 365)
            val report = LocalWorkspaceIntermediateMaintenance(root).cleanup(now)
            assertEquals(6, report.deletedFiles)
            listOf(staleIndex, staleIndexTemp, staleImport, nestedTemp, staleFetch, staleQuery)
                .forEach { assertFalse(it.exists()) }
            listOf(recentIndex, publishedAttachment, activeTemp, pendingResume,
                userSource, output, userLookalike, freshFetch, oldUserFile, lockMarker)
                .forEach { assertTrue(it.exists()) }
            assertTrue(report.deletedBytes > 0)
            assertEquals(recentIndex.length(), report.remainingCacheBytes)
            assertEquals(0, LocalWorkspaceIntermediateMaintenance(root).cleanup(now).deletedFiles)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun symlinkedGeneratedResponseDirectoryIsNeverTraversed() {
        val root = Files.createTempDirectory("fetch-maintenance-links-").toFile()
        val outside = Files.createTempDirectory("fetch-outside-").toFile()
        try {
            val secret = File(outside, "fetch-1794574067369-24d64294.txt")
                .apply { writeText("user content"); setLastModified(1L) }
            val dsh = File(root, ".dsh").apply { mkdirs() }
            Files.createSymbolicLink(File(dsh, "fetches").toPath(), outside.toPath())
            try {
                LocalWorkspaceIntermediateMaintenance(root).cleanup()
                throw AssertionError("symbolic link must be refused")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message.orEmpty().contains("工具缓存目录不安全"))
            }
            assertTrue(secret.isFile)
        } finally {
            root.deleteRecursively()
            outside.deleteRecursively()
        }
    }

    @Test fun symlinkedIndexIsNeverTraversedOrDeleted() {
        val root = Files.createTempDirectory("cache-links-").toFile()
        val outside = Files.createTempDirectory("cache-outside-").toFile()
        try {
            val secret = File(outside, "keep.txt").apply { writeText("do not delete") }
            val dsh = File(root, ".dsh").apply { mkdirs() }
            Files.createSymbolicLink(File(dsh, "text-index").toPath(), outside.toPath())
            try {
                LocalWorkspaceIntermediateMaintenance(root).cleanup()
                throw AssertionError("symbolic link must be refused")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message.orEmpty().contains("缓存目录不安全"))
            }
            assertTrue(secret.isFile)
        } finally {
            root.deleteRecursively()
            outside.deleteRecursively()
        }
    }
}

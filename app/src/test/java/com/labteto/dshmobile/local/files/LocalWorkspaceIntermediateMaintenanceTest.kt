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
            val nestedTemp = fixture("repository/src/.write-abcd1234567890.tmp", 3)
            val activeTemp = fixture("repository/src/.write-123abc7890.tmp", 0)
            val pendingResume = fixture("repository/src/.module.kt.write-in-progress", 400)
            val userSource = fixture("repository/src/module.kt", 500)
            val output = fixture("repository/src/notes.tmp", 500)
            val report = LocalWorkspaceIntermediateMaintenance(root).cleanup(now)
            assertEquals(4, report.deletedFiles)
            listOf(staleIndex, staleIndexTemp, staleImport, nestedTemp).forEach { assertFalse(it.exists()) }
            listOf(recentIndex, publishedAttachment, activeTemp, pendingResume,
                userSource, output).forEach { assertTrue(it.exists()) }
            assertTrue(report.deletedBytes > 0)
            assertEquals(recentIndex.length(), report.remainingCacheBytes)
            assertEquals(0, LocalWorkspaceIntermediateMaintenance(root).cleanup(now).deletedFiles)
        } finally {
            root.deleteRecursively()
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

package com.labteto.dshmobile.local.attachment

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercise attachment publication on the Android application's real private filesystem. */
@RunWith(AndroidJUnit4::class)
class LocalAttachmentPublishAndroidTest {
    @Test
    fun completedFileSurvivesTemporaryCleanupAndSameDigestReuse() {
        val root = File(
            InstrumentationRegistry.getInstrumentation().targetContext.filesDir,
            "attachment-publish-${UUID.randomUUID()}",
        ).apply { check(mkdirs()) }
        try {
            val bytes = "android private filesystem upload".toByteArray()
            val staged = File(root, ".incoming-first").apply { writeBytes(bytes) }
            val published = File(root, "digest")
            commitImportedAttachmentFile(staged, published)
            assertFalse("rename must consume staging file", staged.exists())
            assertArrayEquals(bytes, published.readBytes())

            val duplicate = File(root, ".incoming-duplicate").apply { writeBytes(bytes) }
            commitImportedAttachmentFile(duplicate, published)
            duplicate.delete() // Matches the importer's unconditional finally cleanup.
            assertArrayEquals(bytes, published.readBytes())
            assertTrue(published.isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun existingDifferentContentIsNeverOverwritten() {
        val root = File(
            InstrumentationRegistry.getInstrumentation().targetContext.filesDir,
            "attachment-collision-${UUID.randomUUID()}",
        ).apply { check(mkdirs()) }
        try {
            val staged = File(root, ".incoming").apply { writeText("new content") }
            val published = File(root, "digest").apply { writeText("old content") }
            val refused = runCatching { commitImportedAttachmentFile(staged, published) }.isFailure
            assertTrue("digest collision must be rejected", refused)
            assertTrue("old content" == published.readText())
        } finally {
            root.deleteRecursively()
        }
    }
}

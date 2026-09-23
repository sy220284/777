package com.labteto.dshmobile.local

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAttachmentMaintenanceTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun referenceCollectorFindsImageRefsInsideDurableUserMessages() {
        val root = createTempDir(prefix = "attachment-ref-")
        try {
            val log = LocalSessionEventLog(File(root, "s.events.jsonl"), json)
            log.append(
                "user/message",
                buildJsonObject {
                    put(
                        "model_message",
                        buildJsonObject {
                            put("role", "user")
                            put(
                                "content",
                                buildJsonArray {
                                    add(buildJsonObject {
                                        put("type", LOCAL_IMAGE_REF)
                                        put("attachmentId", "digest-a")
                                        put("path", ".dsh/attachments/digest-a.png")
                                    })
                                },
                            )
                        },
                    )
                },
            )

            val refs = collectLocalImageAttachmentReferences(log.events())

            assertEquals(setOf("digest-a"), refs.attachmentIds)
            assertEquals(setOf(".dsh/attachments/digest-a.png"), refs.paths)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun cleanupDeletesOnlyStaleUnreferencedImagesAndLeavesOtherFilesAlone() {
        val root = createTempDir(prefix = "attachment-gc-")
        try {
            val dir = File(root, ".dsh/attachments").apply { mkdirs() }
            val referenced = png(File(dir, "keep.png"), 32)
            val stale = png(File(dir, "stale.png"), 32)
            val fresh = png(File(dir, "fresh.png"), 32)
            val text = File(dir, "notes.txt").apply { writeText("保留普通文件") }
            val now = 10_000_000L
            referenced.setLastModified(1L)
            stale.setLastModified(1L)
            fresh.setLastModified(now - 100L)

            val result = cleanupLocalImageAttachments(
                workspaceRoot = root,
                references = LocalImageAttachmentReferences(
                    attachmentIds = emptySet(),
                    paths = setOf(".dsh/attachments/keep.png"),
                ),
                nowMillis = now,
                graceMillis = 1_000L,
                maxImageBytes = 1_024L * 1_024L,
            )

            assertTrue(referenced.exists())
            assertFalse(stale.exists())
            assertTrue(fresh.exists())
            assertTrue(text.exists())
            assertEquals(1, result.deletedFiles)
            assertTrue(result.deletedBytes > 0)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun quotaPressureDeletesOldestUnreferencedImageWithoutTouchingReferencedImage() {
        val root = createTempDir(prefix = "attachment-quota-")
        try {
            val dir = File(root, ".dsh/attachments").apply { mkdirs() }
            val keep = png(File(dir, "keep.png"), 40)
            val oldest = png(File(dir, "oldest.png"), 40)
            val newer = png(File(dir, "newer.png"), 40)
            keep.setLastModified(1L)
            oldest.setLastModified(2L)
            newer.setLastModified(3L)

            val result = cleanupLocalImageAttachments(
                workspaceRoot = root,
                references = LocalImageAttachmentReferences(
                    attachmentIds = setOf("keep"),
                    paths = emptySet(),
                ),
                nowMillis = 100L,
                graceMillis = Long.MAX_VALUE,
                maxImageBytes = keep.length() + newer.length(),
            )

            assertTrue(keep.exists())
            assertFalse(oldest.exists())
            assertTrue(newer.exists())
            assertEquals(1, result.deletedFiles)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun png(file: File, payloadBytes: Int): File = file.apply {
        parentFile?.mkdirs()
        writeBytes(
            byteArrayOf(
                0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
            ) + ByteArray(payloadBytes) { 1 },
        )
    }
}

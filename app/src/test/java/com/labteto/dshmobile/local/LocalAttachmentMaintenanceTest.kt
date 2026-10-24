package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.attachment.LocalImageAttachmentReferences
import com.labteto.dshmobile.local.attachment.cleanupLocalImageAttachments
import com.labteto.dshmobile.local.attachment.collectAuthoritativeLocalImageAttachmentReferences
import com.labteto.dshmobile.local.attachment.collectLocalImageAttachmentReferences
import com.labteto.dshmobile.local.model.LOCAL_IMAGE_REF
import com.labteto.dshmobile.local.session.LocalSessionEventLog
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
        val root = kotlin.io.path.createTempDirectory("attachment-ref-").toFile()
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

            val refs = log.withEvents { collectLocalImageAttachmentReferences(it) }

            assertEquals(setOf("digest-a"), refs.attachmentIds)
            assertEquals(setOf(".dsh/attachments/digest-a.png"), refs.paths)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun cleanupDeletesOnlyStaleUnreferencedImagesAndLeavesOtherFilesAlone() {
        val root = kotlin.io.path.createTempDirectory("attachment-gc-").toFile()
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
        val root = kotlin.io.path.createTempDirectory("attachment-quota-").toFile()
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
                nowMillis = 4L * 60L * 60L * 1000L,
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

    @Test
    fun authoritativeReferencesProtectImagesFromOtherSessionsAndSnapshots() {
        val root = kotlin.io.path.createTempDirectory("attachment-sql-refs-").toFile()
        try {
            val dir = File(root, ".dsh/attachments").apply { mkdirs() }
            val fromEvents = png(File(dir, "event.png"), 32)
            val fromSnapshot = png(File(dir, "snapshot.png"), 32)
            val obsolete = png(File(dir, "obsolete.png"), 32)
            listOf(fromEvents, fromSnapshot, obsolete).forEach { it.setLastModified(1L) }
            val refs = collectAuthoritativeLocalImageAttachmentReferences(
                sessionIds = setOf("current", "sql-only", "snapshot-only"),
                currentSessionId = "current",
                readEventReferences = { id ->
                    if (id == "sql-only") LocalImageAttachmentReferences(
                        setOf("event"), emptySet(),
                    ) else LocalImageAttachmentReferences(emptySet(), emptySet())
                },
                readSnapshot = { id ->
                    if (id != "snapshot-only") null else buildJsonObject {
                        put("content", buildJsonArray {
                            add(buildJsonObject {
                                put("type", LOCAL_IMAGE_REF)
                                put("path", ".dsh/attachments/snapshot.png")
                            })
                        })
                    }
                },
            )
            val result = cleanupLocalImageAttachments(
                workspaceRoot = root,
                references = refs,
                nowMillis = 20_000_000L,
                graceMillis = 1_000L,
                maxImageBytes = 10_000L,
            )
            assertTrue(fromEvents.exists())
            assertTrue(fromSnapshot.exists())
            assertFalse(obsolete.exists())
            assertEquals(1, result.deletedFiles)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun failedAuthoritativeScanDoesNotStartDestructiveCleanup() {
        val root = kotlin.io.path.createTempDirectory("attachment-scan-fails-").toFile()
        try {
            val image = png(File(root, ".dsh/attachments/protected.png"), 32)
            image.setLastModified(1L)
            val attempt = runCatching {
                val refs = collectAuthoritativeLocalImageAttachmentReferences(
                    sessionIds = setOf("current", "unreadable"),
                    currentSessionId = "current",
                    readEventReferences = { id ->
                        if (id == "unreadable") error("simulated SQLite failure")
                        LocalImageAttachmentReferences(emptySet(), emptySet())
                    },
                    readSnapshot = { null },
                )
                cleanupLocalImageAttachments(root, refs, 20_000_000L, 1_000L, 1L)
            }
            assertTrue(attempt.isFailure)
            assertTrue(image.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun quotaPressureCannotDeleteJustCreatedUnreferencedImages() {
        val root = kotlin.io.path.createTempDirectory("attachment-inflight-").toFile()
        try {
            val image = png(File(root, ".dsh/attachments/inflight.png"), 32)
            val now = 20_000_000L
            image.setLastModified(now - 1_000L)
            val result = cleanupLocalImageAttachments(
                root, LocalImageAttachmentReferences(emptySet(), emptySet()),
                nowMillis = now, graceMillis = 7L * 24 * 60 * 60 * 1_000,
                maxImageBytes = 1L,
            )
            assertTrue(image.exists())
            assertEquals(0, result.deletedFiles)
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

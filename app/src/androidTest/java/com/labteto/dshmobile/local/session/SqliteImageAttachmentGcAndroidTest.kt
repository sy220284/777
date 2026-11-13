package com.labteto.dshmobile.local.session

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.local.attachment.cleanupLocalImageAttachments
import com.labteto.dshmobile.local.attachment.collectAuthoritativeLocalImageAttachmentReferences
import com.labteto.dshmobile.local.attachment.collectLocalImageAttachmentReferences
import com.labteto.dshmobile.local.model.LOCAL_IMAGE_REF
import java.io.File
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** SQL-only conversations keep their attachments even without a .events.jsonl file. */
@RunWith(AndroidJUnit4::class)
class SqliteImageAttachmentGcAndroidTest {
    @Test fun sqliteOnlySessionKeepsImageReferencedByAnotherConversation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val unique = UUID.randomUUID().toString()
        val databaseName = "image-gc-$unique.db"
        val root = File(context.cacheDir, "sqlite-image-gc-$unique").apply { mkdirs() }
        try {
            val images = File(root, ".dsh/attachments").apply { mkdirs() }
            val keep = png(File(images, "used.png"))
            val keepBlock = png(File(images, "block.png"))
            val delete = png(File(images, "unused.png"))
            keep.setLastModified(1L)
            keepBlock.setLastModified(1L)
            delete.setLastModified(1L)

            SqliteSessionEventDatabase(context, Json, databaseName).use { sql ->
                val registry = LocalSessionEventLogRegistry(root, Json, sqlite = sql)
                registry.get("other-session").append("user/message", buildJsonObject {
                    put("model_message", buildJsonObject {
                        put("content", buildJsonArray {
                            add(buildJsonObject {
                                put("type", LOCAL_IMAGE_REF)
                                put("attachmentId", "used")
                                put("path", ".dsh/attachments/used.png")
                            })
                        })
                    })
                })

                // Durable transcripts use kind=image / attachment_id, not
                // local_image_ref. Verify this independent SQLite-only session.
                registry.get("block-session").append("user/message", buildJsonObject {
                    put("transcript", buildJsonArray {
                        add(buildJsonObject {
                            put("blocks", buildJsonArray {
                                add(buildJsonObject {
                                    put("kind", "image")
                                    put("path", ".dsh/attachments/block.png")
                                    put("attachment_id", "block")
                                    put("media_type", "image/png")
                                })
                            })
                        })
                    })
                })
                assertFalse(File(root, "block-session.events.jsonl").exists())
                assertFalse(File(root, "other-session.events.jsonl").exists())
                val ids = registry.historicalSources().map { it.id }.toSet() + "current-session"
                val references = collectAuthoritativeLocalImageAttachmentReferences(
                    sessionIds = ids,
                    currentSessionId = "current-session",
                    readEventReferences = { id ->
                        registry.readHistorical(id) { eventLog ->
                            eventLog.withEvents { events ->
                                collectLocalImageAttachmentReferences(events)
                            }
                        }
                    },
                    readSnapshot = { null },
                )
                val result = cleanupLocalImageAttachments(
                    workspaceRoot = root,
                    references = references,
                    nowMillis = 20_000_000L,
                    graceMillis = 1_000L,
                    maxImageBytes = 1_000_000L,
                )
                assertTrue(keep.exists())
                assertTrue(keepBlock.exists())
                assertFalse(delete.exists())
                assertEquals(1, result.deletedFiles)
            }
        } finally {
            context.deleteDatabase(databaseName)
            root.deleteRecursively()
        }
    }

    private fun png(file: File): File = file.apply {
        parentFile?.mkdirs()
        writeBytes(byteArrayOf(
            0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
        ) + ByteArray(32) { 1 })
    }
}

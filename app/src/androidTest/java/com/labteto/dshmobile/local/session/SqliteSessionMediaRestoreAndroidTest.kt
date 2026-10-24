package com.labteto.dshmobile.local.session

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.local.attachment.LocalImportedAttachment
import com.labteto.dshmobile.local.attachment.SqliteLocalAssetCatalog
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Full archive round-trip on Android, including original media after process-style reopen. */
@RunWith(AndroidJUnit4::class)
class SqliteSessionMediaRestoreAndroidTest {
    @Test fun backupStagesAndRestoresBothDatabasesAndOriginalImage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val unique = UUID.randomUUID().toString().replace("-", "")
        val sourceSnap = "media-source-snap-$unique.db"
        val sourceEvents = "media-source-events-$unique.db"
        val targetSnap = "media-target-snap-$unique.db"
        val targetEvents = "media-target-events-$unique.db"
        val root = File(context.filesDir, "local-harness/workspace/.dsh/attachments")
        val bytes = byteArrayOf(3, 1, 4, 1, 5, 9, 2, 6)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val image = File(root, "$digest.png")
        val pending = File(
            context.filesDir, "local-harness/.sqlite-restore-pending-$unique",
        )
        var archive = byteArrayOf()
        try {
            root.mkdirs()
            check(!image.exists()) { "测试图片资源哈希碰撞" }
            image.writeBytes(bytes)
            SqliteSessionSnapshotStore(context, Json, sourceSnap).use { snapshots ->
                SqliteSessionEventDatabase(context, Json, sourceEvents).use { events ->
                    val asset = SqliteLocalAssetCatalog(events)
                    asset.register(LocalImportedAttachment(
                        name = "test.png", relativePath = ".dsh/attachments/$digest.png",
                        mediaType = "image/png", bytes = bytes.size.toLong(), attachmentId = digest,
                    ))
                    events.append("media-session", "user/message", buildJsonObject {})
                    val manager = SqliteSessionStorageManager(
                        context, snapshots, events, sourceSnap, sourceEvents, unique,
                    )
                    val output = ByteArrayOutputStream()
                    manager.exportAll(output)
                    archive = output.toByteArray()
                }
            }
            val names = linkedSetOf<String>()
            ZipInputStream(ByteArrayInputStream(archive)).use { zip ->
                while (true) {
                    val member = zip.nextEntry ?: break
                    names.add(member.name)
                    zip.closeEntry()
                }
            }
            assertTrue("snapshot.sqlite" in names)
            assertTrue("events.sqlite" in names)
            assertTrue("attachments.manifest" in names)
            assertTrue("attachments/$digest.png" in names)

            SqliteSessionSnapshotStore(context, Json, targetSnap).use { snapshots ->
                SqliteSessionEventDatabase(context, Json, targetEvents).use { events ->
                    val destination = SqliteSessionStorageManager(
                        context, snapshots, events, targetSnap, targetEvents, unique,
                    )
                    // Deleted last Session can leave a new empty editor model selection
                    // and globally indexed, unreferenced media. Neither is a live chat.
                    events.append("empty-editor", "session/model-selection",
                        buildJsonObject { put("profile_id", "test-profile") })
                    val catalog = SqliteLocalAssetCatalog(events)
                    catalog.register(LocalImportedAttachment(
                        name = "test.png", relativePath = ".dsh/attachments/$digest.png",
                        mediaType = "image/png", bytes = bytes.size.toLong(), attachmentId = digest,
                    ))
                    assertEquals(0, destination.stageRestore(ByteArrayInputStream(archive)))
                    assertTrue(File(pending, "READY").isFile)
                }
            }
            // Simulate loss of local media after export. The staged copy must
            // supply the original content before imported DB rows become live.
            assertTrue(image.delete())
            val reopened = SqliteSessionStorageManager(
                context,
                SqliteSessionSnapshotStore(context, Json, targetSnap),
                SqliteSessionEventDatabase(context, Json, targetEvents),
                targetSnap, targetEvents, unique,
            )
            assertEquals(0, reopened.applyPendingRestore())
            assertTrue(image.exists())
            assertEquals(bytes.toList(), image.readBytes().toList())
            assertTrue(!pending.exists())
            SqliteSessionEventDatabase(context, Json, targetEvents).use { db ->
                assertEquals(bytes.size.toLong(), SqliteLocalAssetCatalog(db).asset(digest)?.bytes)
                assertEquals(0L, db.latestSequence("media-session"))
            }
        } finally {
            image.delete()
            pending.deleteRecursively()
            listOf(sourceSnap, sourceEvents, targetSnap, targetEvents).forEach(context::deleteDatabase)
        }
    }
}

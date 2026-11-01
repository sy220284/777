package com.labteto.dshmobile.local.session

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import com.labteto.dshmobile.local.model.LocalMediaDatabase
import com.labteto.dshmobile.local.model.LocalMediaRecord
import com.labteto.dshmobile.local.model.LocalMediaResult
import com.labteto.dshmobile.local.model.LocalMediaStore
import com.labteto.dshmobile.local.model.LocalMediaStudioBackupInstaller
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
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Full archive round-trip on Android, including original media after process-style reopen. */
@RunWith(AndroidJUnit4::class)
class SqliteSessionMediaRestoreAndroidTest {
    @Test fun restorePlanCanBeCancelledBeforeInstallButNotAfterInstallationStarts() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val unique = UUID.randomUUID().toString().replace("-", "")
        val sourceSnap = "cancel-source-snap-$unique.db"
        val sourceEvents = "cancel-source-event-$unique.db"
        val targetSnap = "cancel-target-snap-$unique.db"
        val targetEvents = "cancel-target-event-$unique.db"
        val pending = File(context.filesDir, "local-harness/.sqlite-restore-pending-$unique")
        try {
            val output = ByteArrayOutputStream()
            SqliteSessionSnapshotStore(context, Json, sourceSnap).use { ss ->
                SqliteSessionEventDatabase(context, Json, sourceEvents).use { ev ->
                    SqliteSessionStorageManager(context, ss, ev,
                        sourceSnap, sourceEvents, unique).exportAll(output)
                }
            }
            SqliteSessionSnapshotStore(context, Json, targetSnap).use { ss ->
                SqliteSessionEventDatabase(context, Json, targetEvents).use { ev ->
                    val manager = SqliteSessionStorageManager(context, ss, ev,
                        targetSnap, targetEvents, unique)
                    assertEquals(0, manager.stageRestore(ByteArrayInputStream(output.toByteArray())))
                    assertTrue(manager.status().pendingRestore)
                    assertTrue(manager.cancelPendingRestore())
                    assertTrue(!manager.status().pendingRestore)
                    assertTrue(!pending.exists())
                    manager.stageRestore(ByteArrayInputStream(output.toByteArray()))
                    File(pending, "INSTALLING").writeText("1")
                    org.junit.Assert.assertThrows(IllegalStateException::class.java) {
                        manager.cancelPendingRestore()
                    }
                    assertTrue(pending.exists())
                }
            }
        } finally {
            pending.deleteRecursively()
            listOf(sourceSnap, sourceEvents, targetSnap, targetEvents).forEach(context::deleteDatabase)
        }
    }

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

    /** Distinct app-private roots simulate moving a full archive to another device. */
    private fun isolatedContext(base: Context, root: File): Context = object : ContextWrapper(base) {
        override fun getFilesDir(): File = root
        override fun getDatabasePath(name: String): File =
            File(root, "databases/$name").also { check(it.parentFile!!.isDirectory || it.parentFile!!.mkdirs()) }
        override fun openOrCreateDatabase(
            name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?,
        ): SQLiteDatabase = openOrCreateDatabase(name, mode, factory, null)
        override fun openOrCreateDatabase(
            name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?,
            handler: DatabaseErrorHandler?,
        ): SQLiteDatabase {
            val file = getDatabasePath(name)
            return SQLiteDatabase.openOrCreateDatabase(file.path, factory, handler)
        }
    }

    @Test fun completeBackupRestoresStudioDatabaseAndPaidOriginalAlongsideSessionFacts() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.cacheDir, "complete-backup-" + UUID.randomUUID()).apply { mkdirs() }
        try {
            val source = isolatedContext(base, File(root, "source").apply { mkdirs() })
            val destination = isolatedContext(base, File(root, "destination").apply { mkdirs() })
            val original = byteArrayOf(2, 7, 1, 8, 2, 8)
            val media = File(source.filesDir, "media-studio/paid.png")
            assertTrue(media.parentFile!!.mkdirs())
            media.writeBytes(original)
            LocalMediaDatabase(source, Json).use { db ->
                db.migrate(listOf(LocalMediaRecord(
                    id = "paid-result",
                    result = LocalMediaResult("paid-result", "test-model", "test", "IMAGE", 1L,
                        fileName = "paid.png", sessionId = "full-chat"),
                )))
            }
            val archive = ByteArrayOutputStream()
            val sessionOnly = ByteArrayOutputStream()
            SqliteSessionSnapshotStore(source, Json).use { snapshots ->
                SqliteSessionEventDatabase(source, Json).use { events ->
                    events.append("full-chat", "user/message", buildJsonObject { put("message", "hello") })
                    val manager = SqliteSessionStorageManager(
                        source, snapshots, events, studioJson = Json,
                        stageStudioSnapshot = { target ->
                            LocalMediaStore(source, Json).stageMediaStudioBackup(target)
                        },
                    )
                    manager.exportAll(sessionOnly)
                    manager.exportAll(archive, includeMediaStudio = true)
                }
            }
            fun zipNames(bytes: ByteArray): Set<String> = buildSet {
                ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        add(entry.name)
                        zip.closeEntry()
                    }
                }
            }
            assertTrue(zipNames(sessionOnly.toByteArray()).none { it.startsWith("media-studio/") })
            assertTrue("media-studio/media-studio.sqlite" in zipNames(archive.toByteArray()))
            assertTrue("media-studio/media/paid.png" in zipNames(archive.toByteArray()))

            val pending = File(destination.filesDir, "local-harness/.sqlite-restore-pending-default")
            SqliteSessionSnapshotStore(destination, Json).use { snapshots ->
                SqliteSessionEventDatabase(destination, Json).use { events ->
                    val manager = SqliteSessionStorageManager(destination, snapshots, events,
                        studioJson = Json)
                    assertEquals(0, manager.stageRestore(ByteArrayInputStream(archive.toByteArray())))
                    assertEquals("sqlite-v3-studio", File(pending, "READY").readText())
                }
            }
            // A real restart closes all SQLite owners before startup installation.
            // Installing while test-owned connections remain open leaves a live WAL
            // reader and correctly triggers SQLITE_BUSY during checkpoint.
            assertEquals(1, LocalMediaStudioBackupInstaller(destination, Json).install(
                File(pending, "media-studio-stage"), retainStaging = true,
            ))
            assertTrue(pending.isDirectory)
            SqliteSessionSnapshotStore(destination, Json).use { snapshots ->
                SqliteSessionEventDatabase(destination, Json).use { events ->
                    val restarted = SqliteSessionStorageManager(destination, snapshots, events,
                        studioJson = Json)
                    assertEquals(0, restarted.applyPendingRestore())
                }
            }
            assertTrue(!pending.exists())
            LocalMediaDatabase(destination, Json).use { db ->
                assertEquals("paid.png", db.get("paid-result")?.result?.fileName)
            }
            assertEquals(original.toList(),
                File(destination.filesDir, "media-studio/paid.png").readBytes().toList())
            SqliteSessionEventDatabase(destination, Json).use { db ->
                assertEquals(0L, db.latestSequence("full-chat"))
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun completeBackupWithMissingStudioSectionCannotCreateRestorePlan() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.cacheDir, "complete-backup-invalid-" + UUID.randomUUID()).apply { mkdirs() }
        try {
            val source = isolatedContext(base, File(root, "source").apply { mkdirs() })
            val destination = isolatedContext(base, File(root, "destination").apply { mkdirs() })
            val fullArchive = ByteArrayOutputStream()
            SqliteSessionSnapshotStore(source, Json).use { snapshots ->
                SqliteSessionEventDatabase(source, Json).use { events ->
                    LocalMediaDatabase(source, Json).use { it.migrate(emptyList()) }
                    SqliteSessionStorageManager(source, snapshots, events,
                        studioJson = Json,
                        stageStudioSnapshot = { LocalMediaStore(source, Json).stageMediaStudioBackup(it) },
                    ).exportAll(fullArchive, includeMediaStudio = true)
                }
            }
            val truncated = ByteArrayOutputStream()
            ZipInputStream(ByteArrayInputStream(fullArchive.toByteArray())).use { input ->
                java.util.zip.ZipOutputStream(truncated).use { output ->
                    while (true) {
                        val entry = input.nextEntry ?: break
                        if (!entry.name.startsWith("media-studio/")) {
                            output.putNextEntry(java.util.zip.ZipEntry(entry.name))
                            input.copyTo(output)
                            output.closeEntry()
                        }
                        input.closeEntry()
                    }
                }
            }
            SqliteSessionSnapshotStore(destination, Json).use { snapshots ->
                SqliteSessionEventDatabase(destination, Json).use { events ->
                    val manager = SqliteSessionStorageManager(destination, snapshots, events,
                        studioJson = Json)
                    org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
                        manager.stageRestore(ByteArrayInputStream(truncated.toByteArray()))
                    }
                    assertTrue(!manager.status().pendingRestore)
                    assertTrue(!destination.getDatabasePath("local_media_v1.db").exists())
                }
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun completeBackupRestoresOntoInitializedEmptyStudioWithoutErasingReceipts() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.cacheDir, "complete-empty-studio-" + UUID.randomUUID()).apply { mkdirs() }
        try {
            val source = isolatedContext(base, File(root, "source").apply { mkdirs() })
            val destination = isolatedContext(base, File(root, "destination").apply { mkdirs() })
            val bytes = byteArrayOf(5, 8, 13, 21)
            val paid = File(source.filesDir, "media-studio/paid.png")
            check(paid.parentFile!!.mkdirs())
            paid.writeBytes(bytes)
            LocalMediaDatabase(source, Json).use { db ->
                db.migrate(listOf(LocalMediaRecord(id = "paid",
                    result = LocalMediaResult("paid", "test", "test", "IMAGE", 1L,
                        fileName = "paid.png", sessionId = "archived"))))
            }
            val archive = ByteArrayOutputStream()
            SqliteSessionSnapshotStore(source, Json).use { snapshots ->
                SqliteSessionEventDatabase(source, Json).use { events ->
                    events.append("archived", "user/message", buildJsonObject { put("content", "archived") })
                    SqliteSessionStorageManager(source, snapshots, events,
                        studioJson = Json,
                        stageStudioSnapshot = { LocalMediaStore(source, Json).stageMediaStudioBackup(it) },
                    ).exportAll(archive, includeMediaStudio = true)
                }
            }
            LocalMediaDatabase(destination, Json).use { db ->
                db.migrate(emptyList())
                assertEquals(null, db.get("paid"))
            }
            SqliteSessionSnapshotStore(destination, Json).use { snapshots ->
                SqliteSessionEventDatabase(destination, Json).use { events ->
                    val manager = SqliteSessionStorageManager(destination, snapshots, events,
                        studioJson = Json)
                    manager.stageRestore(ByteArrayInputStream(archive.toByteArray()))
                    assertTrue(manager.status().pendingRestore)
                }
            }
            SqliteSessionSnapshotStore(destination, Json).use { snapshots ->
                SqliteSessionEventDatabase(destination, Json).use { events ->
                    val restarted = SqliteSessionStorageManager(destination, snapshots, events,
                        studioJson = Json)
                    assertEquals(0, restarted.applyPendingRestore())
                }
            }
            LocalMediaDatabase(destination, Json).use { db ->
                assertEquals("paid.png", db.get("paid")?.result?.fileName)
            }
            assertEquals(bytes.toList(),
                File(destination.filesDir, "media-studio/paid.png").readBytes().toList())
            SqliteSessionEventDatabase(destination, Json).use { events ->
                assertEquals(0L, events.latestSequence("archived"))
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun stagedPristineStudioRestoreRefusesReceiptsCreatedBeforeInstallation() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.cacheDir, "late-media-conflict-" + UUID.randomUUID()).apply { mkdirs() }
        try {
            val source = isolatedContext(base, File(root, "source").apply { mkdirs() })
            val destination = isolatedContext(base, File(root, "destination").apply { mkdirs() })
            LocalMediaDatabase(source, Json).use { db ->
                db.migrate(listOf(LocalMediaRecord(id = "backed-up",
                    result = LocalMediaResult("backed-up", "test", "test", "TRANSCRIBE", 1L,
                        textOutput = "from backup", sessionId = "archived"))))
            }
            val stage = File(root, "staged")
            LocalMediaStore(source, Json).stageMediaStudioBackup(stage)
            LocalMediaDatabase(destination, Json).use { it.migrate(emptyList()) }
            val installer = LocalMediaStudioBackupInstaller(destination, Json)
            installer.validateDestination(stage)
            // A receipt added after staging is not disposable or authorized to overwrite.
            LocalMediaDatabase(destination, Json).use { db ->
                db.put(LocalMediaRecord(id = "created-later",
                    result = LocalMediaResult("created-later", "test", "test",
                        "TRANSCRIBE", 2L, textOutput = "must survive", sessionId = "current")))
            }
            org.junit.Assert.assertThrows(IllegalStateException::class.java) {
                installer.install(stage)
            }
            LocalMediaDatabase(destination, Json).use { db ->
                assertEquals("must survive", db.get("created-later")?.result?.textOutput)
                assertEquals(null, db.get("backed-up"))
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun completeBackupRejectsExistingStudioDataBeforeStagingAnyRestore() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.cacheDir, "complete-backup-conflict-" + UUID.randomUUID()).apply { mkdirs() }
        try {
            val source = isolatedContext(base, File(root, "source").apply { mkdirs() })
            val destination = isolatedContext(base, File(root, "destination").apply { mkdirs() })
            val sourceFile = File(source.filesDir, "media-studio/original.png")
            check(sourceFile.parentFile!!.mkdirs())
            sourceFile.writeBytes(byteArrayOf(1, 2, 3))
            LocalMediaDatabase(source, Json).use { db ->
                db.migrate(listOf(LocalMediaRecord(id = "original",
                    result = LocalMediaResult("original", "test", "test", "IMAGE", 1L,
                        fileName = "original.png", sessionId = "chat"))))
            }
            val archive = ByteArrayOutputStream()
            SqliteSessionSnapshotStore(source, Json).use { snapshots ->
                SqliteSessionEventDatabase(source, Json).use { events ->
                    SqliteSessionStorageManager(source, snapshots, events,
                        studioJson = Json,
                        stageStudioSnapshot = { LocalMediaStore(source, Json).stageMediaStudioBackup(it) },
                    ).exportAll(archive, includeMediaStudio = true)
                }
            }
            LocalMediaDatabase(destination, Json).use { db ->
                db.migrate(listOf(LocalMediaRecord(id = "existing",
                    result = LocalMediaResult("existing", "test", "test", "TRANSCRIBE", 1L,
                        textOutput = "必须保留的历史记录", sessionId = "other"))))
            }
            SqliteSessionSnapshotStore(destination, Json).use { snapshots ->
                SqliteSessionEventDatabase(destination, Json).use { events ->
                    val manager = SqliteSessionStorageManager(destination, snapshots, events,
                        studioJson = Json)
                    org.junit.Assert.assertThrows(IllegalStateException::class.java) {
                        manager.stageRestore(ByteArrayInputStream(archive.toByteArray()))
                    }
                    assertTrue(!manager.status().pendingRestore)
                }
            }
            LocalMediaDatabase(destination, Json).use { db ->
                assertEquals("必须保留的历史记录", db.get("existing")?.result?.textOutput)
                assertEquals(null, db.get("original"))
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun interruptedCleanupOfCommittedRestoreNeverReplaysInstalledDatabases() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.cacheDir, "committed-cleanup-" + UUID.randomUUID()).apply { mkdirs() }
        try {
            val context = isolatedContext(base, File(root, "destination").apply { mkdirs() })
            val committed = File(context.filesDir, "local-harness/.sqlite-restore-committed-default")
            check(committed.mkdirs())
            File(committed, "READY").writeText("sqlite-v3-studio")
            File(committed, "incomplete-garbage").writeText("already-installed")
            SqliteSessionSnapshotStore(context, Json).use { snapshots ->
                SqliteSessionEventDatabase(context, Json).use { events ->
                    events.append("preserve", "user/message", buildJsonObject { put("value", "existing") })
                    val manager = SqliteSessionStorageManager(context, snapshots, events)
                    assertEquals(0, manager.applyPendingRestore())
                    assertTrue(!committed.exists())
                    assertEquals(0L, events.latestSequence("preserve"))
                    assertTrue(!manager.status().pendingRestore)
                }
            }
        } finally { root.deleteRecursively() }
    }
}

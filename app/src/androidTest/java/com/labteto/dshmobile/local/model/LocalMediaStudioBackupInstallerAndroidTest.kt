package com.labteto.dshmobile.local.model

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated app-private databases and real SQLite; no paid accounts or external providers. */
@RunWith(AndroidJUnit4::class)
class LocalMediaStudioBackupInstallerAndroidTest {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun sandbox(root: File): Context = object : ContextWrapper(base) {
        override fun getFilesDir(): File = root
        override fun getDatabasePath(name: String): File = File(root, "databases/$name")
        override fun openOrCreateDatabase(
            name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?,
        ): SQLiteDatabase = openOrCreateDatabase(name, mode, factory, null)
        override fun openOrCreateDatabase(
            name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?,
            handler: DatabaseErrorHandler?,
        ): SQLiteDatabase {
            val file = getDatabasePath(name)
            file.parentFile?.mkdirs()
            return SQLiteDatabase.openOrCreateDatabase(file.path, factory, handler)
        }
    }

    private fun record(id: String, name: String): LocalMediaRecord = LocalMediaRecord(
        id = id,
        result = LocalMediaResult(
            id, "test-model", "test", "IMAGE", 1L,
            fileName = name, sessionId = "a",
        ),
    )

    private fun fixture(root: File, mediaName: String = "shared-paid.png"): Pair<File, Context> {
        val src = sandbox(File(root, "source").apply { mkdirs() })
        val media = File(src.filesDir, "media-studio").apply { mkdirs() }
        File(media, mediaName).writeBytes(byteArrayOf(1, 3, 5, 7))
        val staging = File(root, "staging")
        LocalMediaDatabase(src, json).use { db ->
            db.migrate(listOf(record("a", mediaName), record("b", mediaName)))
            val prepared = LocalMediaStudioBackupSnapshot(src, json).prepare(db, staging)
            assertEquals(1, prepared.mediaFiles.size)
        }
        return staging to sandbox(File(root, "destination").apply { mkdirs() })
    }

    @Test fun zipMediaSectionRoundTripsWithUnrelatedSessionEntries() {
        val root = File(base.cacheDir, "media-zip-roundtrip-"+UUID.randomUUID()).apply { mkdirs() }
        try {
            val (staging, destination) = fixture(root)
            val codec = LocalMediaStudioZipEntries(destination, json)
            val buffer = java.io.ByteArrayOutputStream()
            java.util.zip.ZipOutputStream(buffer).use { zip ->
                zip.putNextEntry(java.util.zip.ZipEntry("snapshot.sqlite"))
                zip.write(byteArrayOf(3, 1, 4))
                zip.closeEntry()
                assertTrue(codec.writeInto(zip,
                    LocalMediaStudioBackupSnapshot(destination, json).validate(staging)) > 0)
            }
            val extracted = File(root, "extracted")
            val receiver = codec.Receiver(extracted)
            var unrelated = 0
            java.util.zip.ZipInputStream(buffer.toByteArray().inputStream()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.name.startsWith(LocalMediaStudioZipEntries.PREFIX)) {
                        receiver.accept(entry, zip)
                    } else {
                        unrelated++
                    }
                    zip.closeEntry()
                }
            }
            assertEquals(1, unrelated)
            assertEquals(1, receiver.validateStaged().mediaFiles.size)
            assertEquals(2, LocalMediaStudioBackupInstaller(destination, json).install(extracted))
            assertEquals(byteArrayOf(1, 3, 5, 7).toList(),
                File(destination.filesDir, "media-studio/shared-paid.png").readBytes().toList())
            assertTrue(staging.isDirectory) // Export must not mutate its source snapshot.
        } finally { root.deleteRecursively() }
    }

    @Test fun zipReceiverRejectsTraversalAndDuplicateMediaEntries() {
        val root = File(base.cacheDir, "media-zip-reject-"+UUID.randomUUID()).apply { mkdirs() }
        try {
            val destination = sandbox(File(root, "destination").apply { mkdirs() })
            val codec = LocalMediaStudioZipEntries(destination, json)
            val traversal = java.io.ByteArrayOutputStream()
            java.util.zip.ZipOutputStream(traversal).use { zip ->
                zip.putNextEntry(java.util.zip.ZipEntry("media-studio/media/../../stolen.db"))
                zip.write(byteArrayOf(1))
                zip.closeEntry()
            }
            val receiver = codec.Receiver(File(root, "import-traversal"))
            java.util.zip.ZipInputStream(traversal.toByteArray().inputStream()).use { zip ->
                val entry = requireNotNull(zip.nextEntry)
                assertThrows(IllegalArgumentException::class.java) {
                    receiver.accept(entry, zip)
                }
            }
            assertFalse(File(root, "stolen.db").exists())

            // ZipOutputStream itself rejects duplicate names, so feed two legal
            // single-entry archives into the same receive transaction.
            val repeated = java.io.ByteArrayOutputStream()
            java.util.zip.ZipOutputStream(repeated).use { zip ->
                zip.putNextEntry(java.util.zip.ZipEntry("media-studio/media-studio.sqlite"))
                zip.write(byteArrayOf(1))
                zip.closeEntry()
            }
            val repeatedReceiver = codec.Receiver(File(root, "import-duplicate"))
            java.util.zip.ZipInputStream(repeated.toByteArray().inputStream()).use { zip ->
                repeatedReceiver.accept(requireNotNull(zip.nextEntry), zip)
            }
            java.util.zip.ZipInputStream(repeated.toByteArray().inputStream()).use { zip ->
                assertThrows(IllegalArgumentException::class.java) {
                    repeatedReceiver.accept(requireNotNull(zip.nextEntry), zip)
                }
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun emptyInstallRestoresAllRecordsAndSharedOriginalOnlyOnce() {
        val root = File(base.cacheDir, "restore-"+UUID.randomUUID()).apply { mkdirs() }
        try {
            val (staging, destination) = fixture(root)
            assertEquals(2, LocalMediaStudioBackupInstaller(destination, json).install(staging))
            assertFalse(staging.exists())
            assertEquals(byteArrayOf(1, 3, 5, 7).toList(),
                File(destination.filesDir, "media-studio/shared-paid.png").readBytes().toList())
            LocalMediaDatabase(destination, json).use { db ->
                assertEquals("a", db.get("a")?.result?.id)
                assertEquals("b", db.get("b")?.result?.id)
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun populatedTargetDatabaseRejectsRestoreWithoutReplacingUserReceipts() {
        val root = File(base.cacheDir, "restore-conflict-"+UUID.randomUUID()).apply { mkdirs() }
        try {
            val (staging, destination) = fixture(root)
            LocalMediaDatabase(destination, json).use { db ->
                db.migrate(listOf(record("existing", "existing-original.png")))
            }
            assertThrows(IllegalStateException::class.java) {
                LocalMediaStudioBackupInstaller(destination, json).install(staging)
            }
            assertTrue(staging.isDirectory)
            assertFalse(File(destination.filesDir, "media-studio/shared-paid.png").exists())
            LocalMediaDatabase(destination, json).use { db ->
                assertEquals("existing", db.get("existing")?.result?.id)
                assertEquals(null, db.get("a"))
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun restartAfterOnlyMediaInstalledCanCompleteSameStagedTransaction() {
        val root = File(base.cacheDir, "restore-replay-"+UUID.randomUUID()).apply { mkdirs() }
        try {
            val (staging, destination) = fixture(root)
            val media = File(destination.filesDir, "media-studio").apply { mkdirs() }
            File(staging, "media/shared-paid.png").copyTo(File(media, "shared-paid.png"))
            File(staging, "INSTALLING").writeText("media-studio-v1")
            assertEquals(2, LocalMediaStudioBackupInstaller(destination, json).install(staging))
            assertFalse(staging.exists())
            LocalMediaDatabase(destination, json).use { db ->
                assertEquals("b", db.get("b")?.result?.id)
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun conflictingExistingPaidOriginalRejectsRestoreBeforeAnyDatabaseInstall() {
        val root = File(base.cacheDir, "restore-file-conflict-"+UUID.randomUUID()).apply { mkdirs() }
        try {
            val (staging, destination) = fixture(root)
            val target = File(destination.filesDir, "media-studio/shared-paid.png")
            target.parentFile?.mkdirs()
            target.writeBytes(byteArrayOf(7, 7, 7))
            assertThrows(IllegalStateException::class.java) {
                LocalMediaStudioBackupInstaller(destination, json).install(staging)
            }
            assertEquals(byteArrayOf(7, 7, 7).toList(), target.readBytes().toList())
            assertFalse(destination.getDatabasePath("local_media_v1.db").exists())
            assertTrue(staging.isDirectory)
        } finally { root.deleteRecursively() }
    }

    @Test fun danglingSymlinkNeverCountsAsAnEmptyRestoreDestination() {
        val root = File(base.cacheDir, "restore-symlink-"+UUID.randomUUID()).apply { mkdirs() }
        try {
            val (staging, destination) = fixture(root)
            val file = File(destination.filesDir, "media-studio/shared-paid.png")
            file.parentFile?.mkdirs()
            java.nio.file.Files.createSymbolicLink(
                file.toPath(), File(root, "nonexistent-external-media").toPath(),
            )
            assertThrows(IllegalStateException::class.java) {
                LocalMediaStudioBackupInstaller(destination, json).install(staging)
            }
            assertTrue(java.nio.file.Files.isSymbolicLink(file.toPath()))
            assertFalse(destination.getDatabasePath("local_media_v1.db").exists())
            assertTrue(staging.isDirectory)
        } finally { root.deleteRecursively() }
    }

    @Test fun strayWalSidecarAlwaysRefusesUnverifiedRestoration() {
        val root = File(base.cacheDir, "restore-wal-guard-"+UUID.randomUUID()).apply { mkdirs() }
        try {
            val (staging, destination) = fixture(root)
            val db = destination.getDatabasePath("local_media_v1.db")
            db.parentFile?.mkdirs()
            File(db.path + "-wal").writeBytes(byteArrayOf(0, 1, 2))
            assertThrows(IllegalStateException::class.java) {
                LocalMediaStudioBackupInstaller(destination, json).install(staging)
            }
            assertFalse(db.exists())
            assertTrue(File(db.path + "-wal").isFile)
            assertTrue(staging.isDirectory)
        } finally { root.deleteRecursively() }
    }

    @Test fun restartAfterDatabaseCommitButBeforeStagingCleanupIsIdempotent() {
        val root = File(base.cacheDir, "restore-db-committed-"+UUID.randomUUID()).apply { mkdirs() }
        try {
            val (staging, destination) = fixture(root)
            val db = destination.getDatabasePath("local_media_v1.db")
            db.parentFile?.mkdirs()
            File(staging, "media-studio.sqlite").copyTo(db)
            File(staging, "INSTALLING").writeText("media-studio-v1")
            assertEquals(2, LocalMediaStudioBackupInstaller(destination, json).install(staging))
            assertFalse(staging.exists())
            assertTrue(File(destination.filesDir, "media-studio/shared-paid.png").isFile)
            LocalMediaDatabase(destination, json).use { stored ->
                assertEquals("a", stored.get("a")?.result?.id)
                assertEquals("b", stored.get("b")?.result?.id)
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun corruptedStagingMustNotCreateAnyUserDatabaseOrFile() {
        val root = File(base.cacheDir, "restore-corrupt-"+UUID.randomUUID()).apply { mkdirs() }
        try {
            val (staging, destination) = fixture(root)
            File(staging, "media/shared-paid.png").writeBytes(byteArrayOf(8, 8, 8, 8))
            assertThrows(IllegalArgumentException::class.java) {
                LocalMediaStudioBackupInstaller(destination, json).install(staging)
            }
            assertTrue(staging.isDirectory)
            assertFalse(destination.getDatabasePath("local_media_v1.db").exists())
            assertFalse(File(destination.filesDir, "media-studio/shared-paid.png").exists())
        } finally { root.deleteRecursively() }
    }
}

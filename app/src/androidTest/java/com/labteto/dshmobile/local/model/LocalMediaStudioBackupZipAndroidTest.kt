package com.labteto.dshmobile.local.model

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** ZIP-level tests use real isolated SQLite DBs, never any account or user media. */
@RunWith(AndroidJUnit4::class)
class LocalMediaStudioBackupZipAndroidTest {
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
    private fun fixture(root: File): Pair<Context, File> {
        val source = sandbox(File(root, "source").apply { mkdirs() })
        val original = File(source.filesDir, "media-studio/shared.png")
        original.parentFile?.mkdirs()
        original.writeBytes(byteArrayOf(1, 2, 3, 4, 5))
        val stage = File(root, "prepared")
        LocalMediaDatabase(source, json).use { db ->
            val record = LocalMediaRecord(
                id = "paid",
                result = LocalMediaResult("paid", "test", "test", "IMAGE", 1L,
                    fileName = original.name, sessionId = "chat"),
            )
            db.migrate(listOf(record))
            LocalMediaStudioBackupSnapshot(source, json).prepare(db, stage)
        }
        return source to stage
    }
    @Test fun verifiedZipRoundTripCanRestoreIdenticalPaidOriginalAndReceipts() {
        val root = File(base.cacheDir, "studio-zip-roundtrip-"+UUID.randomUUID()).apply { mkdirs() }
        try {
            val (source, prepared) = fixture(root)
            val transport = LocalMediaStudioBackupZip(source, json)
            val bytes = ByteArrayOutputStream()
            assertTrue(transport.export(prepared, bytes) > 100L)
            val extracted = File(root, "extracted")
            val inspected = transport.stage(ByteArrayInputStream(bytes.toByteArray()), extracted)
            assertEquals(1, inspected.mediaFiles.size)
            assertEquals(byteArrayOf(1, 2, 3, 4, 5).toList(),
                inspected.mediaFiles.single().readBytes().toList())
            val destination = sandbox(File(root, "destination").apply { mkdirs() })
            assertEquals(1, LocalMediaStudioBackupInstaller(destination, json).install(extracted))
            assertEquals(byteArrayOf(1, 2, 3, 4, 5).toList(),
                File(destination.filesDir, "media-studio/shared.png").readBytes().toList())
            LocalMediaDatabase(destination, json).use { db ->
                assertEquals("paid", db.get("paid")?.result?.id)
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun emptyStudioZipHasNoMediaEntriesButStillRestoresItsDatabase() {
        val root = File(base.cacheDir, "studio-zip-empty-"+UUID.randomUUID()).apply { mkdirs() }
        try {
            val source = sandbox(File(root, "source-empty").apply { mkdirs() })
            val prepared = File(root, "prepared-empty")
            LocalMediaDatabase(source, json).use { db ->
                db.migrate(emptyList())
                assertEquals(0, LocalMediaStudioBackupSnapshot(source, json)
                    .prepare(db, prepared).mediaFiles.size)
            }
            val codec = LocalMediaStudioBackupZip(source, json)
            val archive = ByteArrayOutputStream().also { codec.export(prepared, it) }
            val extraction = File(root, "extract-empty")
            val restored = codec.stage(ByteArrayInputStream(archive.toByteArray()), extraction)
            assertTrue(File(extraction, "media").isDirectory)
            assertTrue(restored.mediaFiles.isEmpty())
            val destination = sandbox(File(root, "destination-empty").apply { mkdirs() })
            assertEquals(0, LocalMediaStudioBackupInstaller(destination, json).install(extraction))
            assertFalse(extraction.exists())
            LocalMediaDatabase(destination, json).use { db ->
                assertTrue(db.migrated())
                assertTrue(db.page(null, 10).isEmpty())
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun traversalOrUnrelatedZipEntryFailsBeforeAnyUserInstallation() {
        val root = File(base.cacheDir, "studio-zip-traversal-"+UUID.randomUUID()).apply { mkdirs() }
        try {
            val (source, _) = fixture(root)
            val bytes = ByteArrayOutputStream()
            ZipOutputStream(bytes).use { zip ->
                zip.putNextEntry(ZipEntry("studio/media/../overwrite.db"))
                zip.write(byteArrayOf(1, 2))
                zip.closeEntry()
            }
            val target = File(root, "should-not-exist")
            assertThrows(IllegalArgumentException::class.java) {
                LocalMediaStudioBackupZip(source, json).stage(ByteArrayInputStream(bytes.toByteArray()), target)
            }
            assertFalse(target.exists())
            assertFalse(File(root, "overwrite.db").exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun duplicateArchiveMemberIsRejectedAndStagingRemoved() {
        val root = File(base.cacheDir, "studio-zip-duplicate-"+UUID.randomUUID()).apply { mkdirs() }
        try {
            val (source, _) = fixture(root)
            // ZipOutputStream disallows identical names when writing; construct a
            // valid archive then alter a second same-length name in the ZIP bytes.
            val bytes = ByteArrayOutputStream()
            ZipOutputStream(bytes).use { zip ->
                for (name in listOf("studio/media-studio.sqlite", "studio/media-studio.sqlitx")) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(byteArrayOf(5))
                    zip.closeEntry()
                }
            }
            val mutated = bytes.toByteArray()
            val find = "studio/media-studio.sqlitx".toByteArray(Charsets.UTF_8)
            val replacement = "studio/media-studio.sqlite".toByteArray(Charsets.UTF_8)
            for (idx in 0..mutated.size - find.size) {
                if (find.indices.all { n -> mutated[idx+n] == find[n] }) {
                    replacement.indices.forEach { n -> mutated[idx+n] = replacement[n] }
                }
            }
            val target = File(root, "not-accepted")
            assertThrows(IllegalArgumentException::class.java) {
                LocalMediaStudioBackupZip(source, json).stage(ByteArrayInputStream(mutated), target)
            }
            assertFalse(target.exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun corruptedMediaEntryFailsHashValidationWithoutTouchingOriginal() {
        val root = File(base.cacheDir, "studio-zip-tamper-"+UUID.randomUUID()).apply { mkdirs() }
        try {
            val (source, prepared) = fixture(root)
            val codec = LocalMediaStudioBackupZip(source, json)
            val archive = ByteArrayOutputStream().also { codec.export(prepared, it) }.toByteArray()
            val tampered = ByteArrayOutputStream()
            ZipInputStream(ByteArrayInputStream(archive)).use { read ->
                ZipOutputStream(tampered).use { write ->
                    while (true) {
                        val entry = read.nextEntry ?: break
                        write.putNextEntry(ZipEntry(entry.name))
                        if (entry.name == "studio/media/shared.png") {
                            write.write(byteArrayOf(7, 7, 7, 7, 7))
                        } else read.copyTo(write)
                        write.closeEntry()
                    }
                }
            }
            val target = File(root, "tampered")
            assertThrows(IllegalArgumentException::class.java) {
                codec.stage(ByteArrayInputStream(tampered.toByteArray()), target)
            }
            assertFalse(target.exists())
            assertEquals(byteArrayOf(1, 2, 3, 4, 5).toList(),
                File(source.filesDir, "media-studio/shared.png").readBytes().toList())
        } finally { root.deleteRecursively() }
    }
}

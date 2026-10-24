package com.labteto.dshmobile.local.session

import androidx.test.ext.junit.runners.AndroidJUnit4
import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SqliteSessionMediaArchiveAndroidTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun completeZipPreservesBothHashedAndLegacyAttachments() {
        val root = File(context.cacheDir, "media-backup-src-${UUID.randomUUID()}")
        val dest = File(context.cacheDir, "media-backup-dest-${UUID.randomUUID()}")
        val staged = File(context.cacheDir, "media-backup-stage-${UUID.randomUUID()}")
        try {
            val dir = File(root, ".dsh/attachments").apply { mkdirs() }
            val bytes = byteArrayOf(1, 2, 3, 4, 5)
            val digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
            File(dir, "$digest.png").writeBytes(bytes)
            File(dir, "old-image.jpg").writeBytes(byteArrayOf(8, 9, 7))
            val archive = SqliteSessionMediaArchive(context, root)
            val zipped = ByteArrayOutputStream().also { output ->
                ZipOutputStream(output).use { zip ->
                    zip.putNextEntry(ZipEntry("snapshot.sqlite"))
                    zip.write(byteArrayOf(1))
                    zip.closeEntry()
                    zip.putNextEntry(ZipEntry("events.sqlite"))
                    zip.write(eventDb(root).readBytes())
                    zip.closeEntry()
                    archive.export(zip, eventDb(root))
                }
            }.toByteArray()
            val names = extract(zipped, staged)
            archive.validateStaged(staged, names)
            val restored = SqliteSessionMediaArchive(context, dest)
            restored.installStaged(staged)
            assertTrue(File(dest, ".dsh/attachments/$digest.png").isFile)
            assertTrue(File(dest, ".dsh/attachments/old-image.jpg").isFile)
            assertEquals(bytes.toList(), File(dest, ".dsh/attachments/$digest.png").readBytes().toList())
            // An idempotent second install must leave identical files untouched.
            restored.installStaged(staged)
            assertTrue(File(dest, ".dsh/attachments/old-image.jpg").isFile)
        } finally {
            root.deleteRecursively()
            dest.deleteRecursively()
            staged.deleteRecursively()
        }
    }

    @Test fun alteredMediaFailsVerificationAndExistingConflictIsNeverOverwritten() {
        val root = File(context.cacheDir, "media-backup-src-${UUID.randomUUID()}")
        val dest = File(context.cacheDir, "media-backup-dest-${UUID.randomUUID()}")
        val staged = File(context.cacheDir, "media-backup-stage-${UUID.randomUUID()}")
        try {
            val dir = File(root, ".dsh/attachments").apply { mkdirs() }
            File(dir, "legacy.png").writeBytes(byteArrayOf(1, 2, 3))
            val zipped = ByteArrayOutputStream().also { stream ->
                ZipOutputStream(stream).use { zip ->
                    zip.putNextEntry(ZipEntry("snapshot.sqlite"))
                    zip.write(byteArrayOf(0))
                    zip.closeEntry()
                    zip.putNextEntry(ZipEntry("events.sqlite"))
                    zip.write(eventDb(root).readBytes())
                    zip.closeEntry()
                    SqliteSessionMediaArchive(context, root).export(zip, eventDb(root))
                }
            }.toByteArray()
            val names = extract(zipped, staged)
            val archive = SqliteSessionMediaArchive(context, dest)
            archive.validateStaged(staged, names)
            val previous = File(dest, ".dsh/attachments/legacy.png").apply {
                parentFile!!.mkdirs()
                writeBytes(byteArrayOf(7, 7, 7))
            }
            assertTrue(runCatching { archive.installStaged(staged) }.isFailure)
            assertEquals(listOf<Byte>(7, 7, 7), previous.readBytes().toList())
            previous.delete()
            File(staged, "media/legacy.png").writeBytes(byteArrayOf(0, 0, 0))
            assertTrue(runCatching { archive.validateStaged(staged, names) }.isFailure)
            assertFalse(File(dest, ".dsh/attachments/legacy.png").exists())
        } finally {
            root.deleteRecursively()
            dest.deleteRecursively()
            staged.deleteRecursively()
        }
    }

    @Test fun missingMediaOrUnsafeFilesDoNotProduceValidBackup() {
        val root = File(context.cacheDir, "media-backup-src-${UUID.randomUUID()}")
        val stage = File(context.cacheDir, "media-backup-stage-${UUID.randomUUID()}")
        try {
            val directory = File(root, ".dsh/attachments").apply { mkdirs() }
            val unsafe = File(directory, "empty-image.png").apply { writeBytes(byteArrayOf(1)) }
            val archived = ByteArrayOutputStream().also { stream ->
                ZipOutputStream(stream).use { zip ->
                    zip.putNextEntry(ZipEntry("snapshot.sqlite"))
                    zip.write(byteArrayOf(0))
                    zip.closeEntry()
                    zip.putNextEntry(ZipEntry("events.sqlite"))
                    zip.write(eventDb(root).readBytes())
                    zip.closeEntry()
                    SqliteSessionMediaArchive(context, root).export(zip, eventDb(root))
                }
            }.toByteArray()
            val names = extract(archived, stage)
            File(stage, "media/empty-image.png").delete()
            assertTrue(runCatching {
                SqliteSessionMediaArchive(context, root).validateStaged(stage, names)
            }.isFailure)
            unsafe.delete()
            File(directory, ".incoming-incomplete").writeBytes(byteArrayOf(3))
            assertTrue(runCatching {
                ZipOutputStream(ByteArrayOutputStream()).use { zip ->
                    SqliteSessionMediaArchive(context, root).export(zip, eventDb(root))
                }
            }.isFailure)
        } finally {
            root.deleteRecursively()
            stage.deleteRecursively()
        }
    }

    private fun eventDb(root: File): File {
        val file = File(root, "events-test.sqlite")
        if (!file.exists()) {
            file.parentFile?.mkdirs()
            SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
                db.execSQL("CREATE TABLE session_event_cursors(session_id TEXT PRIMARY KEY)")
            }
        }
        return file
    }

    private fun extract(bytes: ByteArray, root: File): Set<String> {
        val names = linkedSetOf<String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                assertTrue(names.add(entry.name))
                val file = if (entry.name.startsWith("attachments/")) {
                    File(root, "media/${entry.name.removePrefix("attachments/")}")
                } else File(root, entry.name)
                file.parentFile!!.mkdirs()
                file.outputStream().use { zip.copyTo(it) }
                zip.closeEntry()
            }
        }
        return names
    }
}

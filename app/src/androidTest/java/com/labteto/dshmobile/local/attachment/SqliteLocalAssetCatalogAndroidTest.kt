package com.labteto.dshmobile.local.attachment

import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import com.labteto.dshmobile.local.files.LocalWorkspace
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.local.session.SqliteSessionEventDatabase
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import com.labteto.dshmobile.local.model.LOCAL_IMAGE_REF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SqliteLocalAssetCatalogAndroidTest {
    private val digest = "a".repeat(64)
    private val image = LocalImportedAttachment(
        name = "demo.png",
        relativePath = ".dsh/attachments/$digest.png",
        mediaType = "image/png",
        bytes = 333,
        attachmentId = digest,
    )

    @Test fun idempotentRegistrationAndCrossSessionReferencesSurviveOtherSessionDeletion() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "asset-catalog-${UUID.randomUUID()}.db"
        try {
            SqliteSessionEventDatabase(context, Json, name).use { events ->
                val catalog = SqliteLocalAssetCatalog(events)
                catalog.register(image)
                catalog.register(image)
                assertEquals(333L, catalog.asset(digest)?.bytes)
                events.append("left", "user/message", buildJsonObject {})
                events.append("right", "user/message", buildJsonObject {})
                catalog.recordReference("left", "message-1", digest, "user_image")
                catalog.recordReference("left", "message-1", digest, "user_image")
                catalog.recordReference("right", "message-2", digest, "user_image")
                assertEquals(2L, catalog.referenceCount(digest))
                events.clearSession("left")
                assertEquals(1L, catalog.referenceCount(digest))
                assertNotNull(catalog.asset(digest))
                events.clearSession("right")
                assertEquals(0L, catalog.referenceCount(digest))
                // Zero indexed references does NOT delete file metadata or trigger GC:
                // a future backfill can still discover a legacy message.
                assertNotNull(catalog.asset(digest))
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test fun conflictingMetadataAndUncommittedReferencesAreRejected() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "asset-invalid-${UUID.randomUUID()}.db"
        try {
            SqliteSessionEventDatabase(context, Json, name).use { events ->
                val catalog = SqliteLocalAssetCatalog(events)
                catalog.register(image)
                assertTrue(runCatching {
                    catalog.register(image.copy(bytes = 100))
                }.isFailure)
                assertTrue(runCatching {
                    catalog.register(image.copy(relativePath = "../unsafe.png"))
                }.isFailure)
                assertTrue(runCatching {
                    catalog.recordReference("absent", "m-1", digest, "user_image")
                }.isFailure)
                events.append("present", "turn/start", buildJsonObject {})
                assertTrue(runCatching {
                    catalog.recordReference("present", "m-1", "b".repeat(64), "user_image")
                }.isFailure)
                assertEquals(0L, catalog.referenceCount(digest))
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test fun committedMessageReferencesIndexAtomicallyAndMissingAssetsRemainRecoverable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "asset-events-${UUID.randomUUID()}.db"
        try {
            SqliteSessionEventDatabase(context, Json, name).use { events ->
                val catalog = SqliteLocalAssetCatalog(events)
                catalog.register(image)
                val payload = buildJsonObject {
                    put("model_message", buildJsonObject {
                        put("content", buildJsonArray {
                            add(buildJsonObject {
                                put("type", LOCAL_IMAGE_REF)
                                put("attachmentId", digest)
                                put("path", image.relativePath)
                            })
                            add(buildJsonObject {
                                put("type", LOCAL_IMAGE_REF)
                                put("attachmentId", "b".repeat(64))
                            })
                        })
                    })
                }
                events.append("first", "user/message", payload)
                assertEquals(1L, catalog.referenceCount(digest))
                // A second session references the same asset; cross-session
                // deduplication must not erase the first one.
                events.append("second", "assistant/message", payload)
                assertEquals(2L, catalog.referenceCount(digest))
                events.clearSession("first")
                assertEquals(1L, catalog.referenceCount(digest))
                assertNull(catalog.asset("b".repeat(64)))
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test fun pendingImageReferencesBecomeLiveWhenItsFileIsRegistered() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "asset-pending-${UUID.randomUUID()}.db"
        try {
            SqliteSessionEventDatabase(context, Json, name).use { events ->
                val catalog = SqliteLocalAssetCatalog(events)
                val payload = buildJsonObject {
                    put("content", buildJsonArray {
                        add(buildJsonObject {
                            put("type", LOCAL_IMAGE_REF)
                            put("attachmentId", digest)
                        })
                    })
                }
                events.append("pending", "assistant/message", payload)
                assertEquals(0L, catalog.referenceCount(digest))
                assertEquals(1L, catalog.pendingReferenceCount(digest))
                catalog.register(image)
                assertEquals(1L, catalog.referenceCount(digest))
                assertEquals(0L, catalog.pendingReferenceCount(digest))
                catalog.register(image)
                assertEquals(1L, catalog.referenceCount(digest))
                events.clearSession("pending")
                assertEquals(0L, catalog.referenceCount(digest))
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun interruptedLegacyEventReindexResumesWithoutDuplicateReferences() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "asset-reindex-${UUID.randomUUID()}.db"
        try {
            SqliteSessionEventDatabase(context, Json, name).use { events ->
                val catalog = SqliteLocalAssetCatalog(events)
                val payload = buildJsonObject {
                    put("content", buildJsonArray {
                        add(buildJsonObject {
                            put("type", LOCAL_IMAGE_REF)
                            put("attachmentId", digest)
                        })
                    })
                }
                events.append("old", "assistant/message", payload)
                events.append("old", "assistant/message", payload)
                // Simulate a pre-v7 historical database: committed events exist
                // without indexed references or any deferred rows.
                events.writableDatabase.execSQL("DELETE FROM local_asset_pending_references")
                assertEquals(0L, catalog.pendingReferenceCount(digest))
                assertEquals(1, catalog.reconcileCommittedEvents(1))
                assertEquals(1L, catalog.pendingReferenceCount(digest))
            }
            SqliteSessionEventDatabase(context, Json, name).use { events ->
                val catalog = SqliteLocalAssetCatalog(events)
                assertEquals(1, catalog.reconcileCommittedEvents(1))
                assertEquals(2L, catalog.pendingReferenceCount(digest))
                assertEquals(0, catalog.reconcileCommittedEvents(1))
                catalog.register(image)
                assertEquals(2L, catalog.referenceCount(digest))
                assertEquals(0L, catalog.pendingReferenceCount(digest))
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun existingHashNamedMediaBackfillsWithoutDeletingUnknownOrInvalidFiles() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "asset-published-${UUID.randomUUID()}.db"
        val root = File(context.cacheDir, "asset-backfill-${UUID.randomUUID()}")
        val directory = File(root, ".dsh/attachments").apply { mkdirs() }
        try {
            SqliteSessionEventDatabase(context, Json, name).use { events ->
                val catalog = SqliteLocalAssetCatalog(events)
                val contents = "historical attachment".toByteArray()
                val hash = java.security.MessageDigest.getInstance("SHA-256").digest(contents)
                    .joinToString("") { "%02x".format(it.toInt() and 0xff) }
                val valid = File(directory, "$hash.txt").apply { writeBytes(contents) }
                val unknown = File(directory, "old-note.txt").apply { writeText("keep") }
                val first = catalog.reconcilePublishedFiles(root, listOf(valid, unknown))
                assertEquals(1, first.registered)
                assertEquals(1, first.unclassified)
                assertEquals(listOf(".dsh/attachments/$hash.txt"), catalog.locations(hash))
                assertEquals(0, catalog.reconcilePublishedFiles(root, listOf(valid)).registered)
                assertTrue(unknown.isFile)
                assertEquals("keep", unknown.readText())
                val corrupted = File(directory, "${"b".repeat(64)}.txt").apply {
                    writeText("wrong content")
                }
                assertTrue(runCatching {
                    catalog.reconcilePublishedFiles(root, listOf(corrupted))
                }.isFailure)
                assertTrue(corrupted.isFile)
            }
        } finally {
            context.deleteDatabase(name)
            root.deleteRecursively()
        }
    }

    @Test fun importerCanRefineLegacyRecoveredMediaTypeWithoutChangingContentId() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "asset-refine-${UUID.randomUUID()}.db"
        val root = File(context.cacheDir, "asset-refine-${UUID.randomUUID()}")
        val folder = File(root, ".dsh/attachments").apply { mkdirs() }
        try {
            val bytes = "# A Markdown file".toByteArray()
            val id = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            val file = File(folder, "$id.md").apply { writeBytes(bytes) }
            SqliteSessionEventDatabase(context, Json, name).use { db ->
                val catalog = SqliteLocalAssetCatalog(db)
                assertEquals(1, catalog.reconcilePublishedFiles(root, listOf(file)).registered)
                assertEquals("application/octet-stream", catalog.asset(id)?.mediaType)
                catalog.register(LocalImportedAttachment(
                    name = file.name, relativePath = ".dsh/attachments/$id.md",
                    mediaType = "text/markdown", bytes = bytes.size.toLong(),
                    attachmentId = id,
                ))
                assertEquals("text/markdown", catalog.asset(id)?.mediaType)
                assertEquals(listOf(".dsh/attachments/$id.md"), catalog.locations(id))
                assertEquals(0, catalog.reconcilePublishedFiles(root, listOf(file)).registered)
            }
        } finally {
            context.deleteDatabase(name)
            root.deleteRecursively()
        }
    }

    @Test fun duplicateContentUnderTwoSafeExtensionsKeepsBothLocations() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "asset-alias-${UUID.randomUUID()}.db"
        try {
            SqliteSessionEventDatabase(context, Json, name).use { events ->
                val catalog = SqliteLocalAssetCatalog(events)
                val first = image.copy(
                    mediaType = "text/plain",
                    relativePath = ".dsh/attachments/$digest.txt",
                )
                val second = image.copy(
                    mediaType = "text/csv",
                    relativePath = ".dsh/attachments/$digest.csv",
                )
                catalog.register(first)
                catalog.register(second)
                catalog.register(second)
                assertEquals(
                    listOf(second.relativePath, first.relativePath),
                    catalog.locations(digest),
                )
                assertEquals(first.bytes, catalog.asset(digest)?.bytes)
                assertTrue(runCatching {
                    catalog.register(second.copy(bytes = second.bytes + 1))
                }.isFailure)
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test fun eventIndexReadsRealTranscriptBlocksAndToolImages() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "asset-blocks-${UUID.randomUUID()}.db"
        try {
            SqliteSessionEventDatabase(context, Json, name).use { events ->
                val catalog = SqliteLocalAssetCatalog(events)
                catalog.register(image)
                val payload = buildJsonObject {
                    put("transcript", buildJsonArray {
                        add(buildJsonObject {
                            put("blocks", buildJsonArray {
                                add(buildJsonObject {
                                    put("kind", "image")
                                    put("path", image.relativePath)
                                    put("attachment_id", digest)
                                    put("media_type", "image/png")
                                })
                            })
                        })
                    })
                }
                events.append("transcript", "user/message", payload)
                events.append("transcript", "tool/result", payload)
                events.append("transcript", "agent/inbox/spliced", payload)
                assertEquals(3L, catalog.referenceCount(digest))
                events.clearSession("transcript")
                assertEquals(0L, catalog.referenceCount(digest))
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test fun realImageImporterPublishesAndRegistersFileMetadata() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val unique = UUID.randomUUID().toString()
        val name = "asset-import-$unique.db"
        val root = File(context.cacheDir, "asset-import-$unique").apply { mkdirs() }
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val encoded = ByteArrayOutputStream().also { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
        }.toByteArray()
        bitmap.recycle()
        try {
            SqliteSessionEventDatabase(context, Json, name).use { events ->
                val catalog = SqliteLocalAssetCatalog(events)
                val importer = LocalAttachmentImporter(
                    context, LocalWorkspace(root), catalog::register,
                )
                val item = importer.importImageBytes(encoded, "image/png")
                assertEquals("image/png", item.mediaType)
                assertEquals(item.bytes, catalog.asset(requireNotNull(item.attachmentId))?.bytes)
                assertTrue(File(root, item.relativePath).isFile)
                // Duplicate imports should reuse bytes and metadata safely.
                val duplicate = importer.importImageBytes(encoded, "image/png")
                assertEquals(item.attachmentId, duplicate.attachmentId)
                assertEquals(item.relativePath, duplicate.relativePath)
            }
        } finally {
            context.deleteDatabase(name)
            root.deleteRecursively()
        }
    }

    @Test fun v3UpgradeRetainsCanonicalAssetLocation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "asset-v3-${UUID.randomUUID()}.db"
        try {
            val file = context.getDatabasePath(name)
            file.parentFile?.mkdirs()
            SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
                old.execSQL(
                    "CREATE TABLE session_event_cursors (" +
                        "session_id TEXT PRIMARY KEY NOT NULL, next_sequence INTEGER NOT NULL, instance TEXT NOT NULL)"
                )
                old.execSQL(
                    "CREATE TABLE session_events (" +
                        "session_id TEXT NOT NULL REFERENCES session_event_cursors(session_id) ON DELETE CASCADE," +
                        "sequence INTEGER NOT NULL, event_type TEXT NOT NULL, created_at INTEGER NOT NULL," +
                        "schema_version INTEGER NOT NULL, payload TEXT NOT NULL," +
                        "PRIMARY KEY(session_id,sequence))"
                )
                old.execSQL(
                    "CREATE TABLE local_assets (" +
                        "asset_id TEXT PRIMARY KEY NOT NULL, relative_path TEXT NOT NULL," +
                        "media_type TEXT NOT NULL, byte_size INTEGER NOT NULL," +
                        "storage_kind TEXT NOT NULL, state TEXT NOT NULL, created_at INTEGER NOT NULL)"
                )
                old.execSQL(
                    "INSERT INTO local_assets VALUES (?,?,?,?,?,?,?)",
                    arrayOf<Any>(digest, image.relativePath, image.mediaType, image.bytes, "file", "present", 1L),
                )
                old.version = 3
            }
            SqliteSessionEventDatabase(context, Json, name).use { upgraded ->
                val catalog = SqliteLocalAssetCatalog(upgraded)
                assertEquals(listOf(image.relativePath), catalog.locations(digest))
                assertEquals(image.bytes, catalog.asset(digest)?.bytes)
                catalog.register(image.copy(relativePath = ".dsh/attachments/$digest.jpg"))
                assertEquals(2, catalog.locations(digest).size)
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test fun v3WithVisibleHintUpgradesWithoutDroppingRecentMessageIndex() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "asset-v3-visible-${UUID.randomUUID()}.db"
        try {
            val file = context.getDatabasePath(name)
            file.parentFile?.mkdirs()
            SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
                old.execSQL("CREATE TABLE session_event_cursors (" +
                    "session_id TEXT PRIMARY KEY NOT NULL, " +
                    "next_sequence INTEGER NOT NULL, instance TEXT NOT NULL)")
                old.execSQL("CREATE TABLE session_events (" +
                    "session_id TEXT NOT NULL REFERENCES session_event_cursors(session_id) ON DELETE CASCADE, " +
                    "sequence INTEGER NOT NULL, event_type TEXT NOT NULL, created_at INTEGER NOT NULL, " +
                    "schema_version INTEGER NOT NULL, payload TEXT NOT NULL, " +
                    "visible_hint INTEGER NOT NULL DEFAULT 0, " +
                    "PRIMARY KEY(session_id,sequence))")
                old.execSQL("INSERT INTO session_event_cursors VALUES ('legacy',1,'old-instance')")
                old.execSQL("INSERT INTO session_events VALUES (" +
                    "'legacy',0,'user/message',1,1,'{}',1)")
                old.version = 3
            }
            SqliteSessionEventDatabase(context, Json, name).use { upgraded ->
                assertEquals(0L, upgraded.latestSequence("legacy"))
                val catalog = SqliteLocalAssetCatalog(upgraded)
                catalog.register(image)
                assertEquals(image.bytes, catalog.asset(digest)?.bytes)
                assertEquals(1L, upgraded.append(
                    "legacy", "assistant/message", buildJsonObject {},
                ).sequence)
                upgraded.readableDatabase.rawQuery(
                    "SELECT visible_hint FROM session_events WHERE session_id='legacy' AND sequence=0",
                    null,
                ).use { rows ->
                    assertTrue(rows.moveToFirst())
                    assertEquals(1, rows.getInt(0))
                }
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test fun v2DatabaseUpgradesToV4WithoutDiscardingEvents() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "asset-upgrade-${UUID.randomUUID()}.db"
        try {
            val file = context.getDatabasePath(name)
            file.parentFile?.mkdirs()
            SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
                old.execSQL(
                    "CREATE TABLE session_event_cursors (" +
                        "session_id TEXT PRIMARY KEY NOT NULL, next_sequence INTEGER NOT NULL, instance TEXT NOT NULL)"
                )
                old.execSQL(
                    "CREATE TABLE session_events (" +
                        "session_id TEXT NOT NULL REFERENCES session_event_cursors(session_id) ON DELETE CASCADE," +
                        "sequence INTEGER NOT NULL, event_type TEXT NOT NULL, created_at INTEGER NOT NULL," +
                        "schema_version INTEGER NOT NULL, payload TEXT NOT NULL," +
                        "PRIMARY KEY(session_id,sequence))"
                )
                old.execSQL("INSERT INTO session_event_cursors VALUES ('legacy', 1, 'old-instance')")
                old.execSQL(
                    "INSERT INTO session_events VALUES ('legacy',0,'turn/start',123,1,'{}')"
                )
                old.version = 2
            }
            SqliteSessionEventDatabase(context, Json, name).use { upgraded ->
                assertEquals(0L, upgraded.latestSequence("legacy"))
                val catalog = SqliteLocalAssetCatalog(upgraded)
                assertNull(catalog.asset(digest))
                catalog.register(image)
                assertNotNull(catalog.asset(digest))
                assertEquals(1L, upgraded.append("legacy", "turn/end", buildJsonObject {}).sequence)
            }
        } finally {
            context.deleteDatabase(name)
        }
    }
}

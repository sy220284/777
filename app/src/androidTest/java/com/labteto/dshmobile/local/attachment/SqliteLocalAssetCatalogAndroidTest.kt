package com.labteto.dshmobile.local.attachment

import android.database.sqlite.SQLiteDatabase
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

    @Test fun v2DatabaseUpgradesToAssetCatalogWithoutDiscardingEvents() {
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

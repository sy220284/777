package com.labteto.dshmobile.local.session

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.harness.session.VersionedSessionStore
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SqliteLegacyZipConversionAndroidTest {
    @Test fun oldZipConvertsInIsolationThenRestoresWithoutChangingLiveSession() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val key = UUID.randomUUID().toString().replace("-", "")
        val legacyDir = File(context.cacheDir, "legacy-source-$key").apply { mkdirs() }
        val snapshotName = "legacy-zip-target-snapshot-$key.db"
        val eventName = "legacy-zip-target-events-$key.db"
        val pending = File(context.filesDir, "local-harness/.sqlite-restore-pending-$key")
        val id = "historic"
        try {
            val original = LocalHarnessSession(id = id, title = "旧版历史", updatedAt = 100L)
            VersionedSessionStore(legacyDir, Json).write(id,
                Json.encodeToJsonElement(LocalHarnessSession.serializer(), original).jsonObject,
                100L)
            LocalSessionEventLog(File(legacyDir, "$id.events.jsonl"), Json).apply {
                append("user/message", buildJsonObject { put("text", "old conversation") })
                append("assistant/message", buildJsonObject { put("text", "old reply") })
                close()
            }
            val legacy = ByteArrayOutputStream()
            LocalSessionStorageManager(legacyDir, Json).exportAll(legacy)
            SqliteSessionSnapshotStore(context, Json, snapshotName).use { snapshots ->
                SqliteSessionEventDatabase(context, Json, eventName).use { events ->
                    val importer = SqliteSessionStorageManager(context, snapshots, events,
                        snapshotName, eventName, key)
                    assertEquals(1, importer.stageLegacyRestore(
                        ByteArrayInputStream(legacy.toByteArray())))
                    assertTrue(File(pending, "READY").isFile)
                    assertEquals(0, snapshots.readSummaries().size)
                    assertEquals(-1L, events.latestSequence(id))
                }
            }
            val importer = SqliteSessionStorageManager(context,
                SqliteSessionSnapshotStore(context, Json, snapshotName),
                SqliteSessionEventDatabase(context, Json, eventName),
                snapshotName, eventName, key)
            assertEquals(1, importer.applyPendingRestore())
            SqliteSessionSnapshotStore(context, Json, snapshotName).use { snapshots ->
                SqliteSessionEventDatabase(context, Json, eventName).use { events ->
                    assertEquals("旧版历史", snapshots.read(id)?.document?.payload
                        ?.get("title")?.toString()?.trim('"'))
                    assertEquals(1L, events.latestSequence(id))
                    assertEquals(2, events.pageAfter(id, -1L).size)
                }
            }
            assertTrue(!pending.exists())
        } finally {
            pending.deleteRecursively()
            legacyDir.deleteRecursively()
            context.deleteDatabase(snapshotName)
            context.deleteDatabase(eventName)
        }
    }
}

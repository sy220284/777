package com.labteto.dshmobile.local.session

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.harness.session.VersionedSessionStore
import java.io.File
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalLegacySqliteMigrationAndroidTest {
    @Test fun existingJsonAndJsonlRemainVisibleAndMigrationIsIdempotent() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val key = UUID.randomUUID().toString().replace("-", "")
        val root = File(ctx.cacheDir, "migration-$key")
        val sessions = File(root, "sessions").apply { mkdirs() }
        val snapshotDb = "migration-snap-$key.db"
        val eventDb = "migration-events-$key.db"
        val old = LocalHarnessSession(id = "older", title = "既有历史")
        val payload = Json.encodeToJsonElement(LocalHarnessSession.serializer(), old).jsonObject
        VersionedSessionStore(sessions, Json).write("older", payload, 1L)
        val oldEvents = File(sessions, "older.events.jsonl")
        LocalSessionEventLog(oldEvents, Json).apply {
            append("user/message", buildJsonObject { put("source", "legacy") })
            append("assistant/message", buildJsonObject { put("source", "legacy-reply") })
            close()
        }
        try {
            SqliteSessionSnapshotStore(ctx, Json, snapshotDb).use { snapshots ->
                SqliteSessionEventDatabase(ctx, Json, eventDb).use { events ->
                    val importer = LocalLegacySqliteMigration(root, sessions, Json, snapshots, events)
                    importer.migrate("older")
                    assertEquals("既有历史", snapshots.read("older")?.document?.payload
                        ?.get("title")?.toString()?.trim('"'))
                    assertEquals(1L, events.latestSequence("older"))
                    importer.migrate("older")
                    assertEquals(1L, events.latestSequence("older"))
                    assertNotNull(snapshots.read("older"))
                    assertEquals(2, events.pageAfter("older", -1L).size)
                }
            }
        } finally {
            root.deleteRecursively()
            ctx.deleteDatabase(snapshotDb)
            ctx.deleteDatabase(eventDb)
        }
    }
}

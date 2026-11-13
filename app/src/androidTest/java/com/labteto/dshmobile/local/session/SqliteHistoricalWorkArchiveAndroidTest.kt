package com.labteto.dshmobile.local.session

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The Work archive sees SQLite events without creating a second JSONL fact source. */
@RunWith(AndroidJUnit4::class)
class SqliteHistoricalWorkArchiveAndroidTest {
    @Test fun historicalReaderUsesSqliteInstanceAndLeavesForegroundCacheUntouched() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val key = UUID.randomUUID().toString()
        val dbName = "work-archive-$key.db"
        val root = File(context.cacheDir, "work-archive-$key").apply { mkdirs() }
        try {
            SqliteSessionEventDatabase(context, Json, dbName).use { db ->
                val registry = LocalSessionEventLogRegistry(root, Json, sqlite = db)
                val live = registry.get("work-one")
                live.append("agent/run-checkpoint", buildJsonObject { put("mode", "work") })
                live.append("turn/end", buildJsonObject { put("reason", "completed") })
                val first = registry.historicalSources().single()
                assertEquals("work-one", first.id)
                assertEquals(2L, first.size)
                registry.readHistorical("work-one") { history ->
                    assertEquals(live.latestSequence(), history.latestSequence())
                    assertEquals(2, history.snapshot().size)
                }
                // A separate reader must never close or evict the live writer.
                assertEquals(2L, live.append("tool/result", buildJsonObject {
                    put("is_error", false)
                }).sequence)
                assertEquals(3L, registry.historicalSources().single().size)
                assertFalse(File(root, "work-one.events.jsonl").exists())
                registry.clearAndEvict(setOf("work-one"))
                assertTrue(registry.historicalSources().isEmpty())
                registry.get("work-one").append("turn/start", buildJsonObject {})
                assertNotEquals(first.identity, registry.historicalSources().single().identity)
            }
        } finally {
            context.deleteDatabase(dbName)
            root.deleteRecursively()
        }
    }
}

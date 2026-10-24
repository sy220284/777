package com.labteto.dshmobile.local.session

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.local.files.LocalWorkspace
import com.labteto.dshmobile.local.work.improvement.LocalWorkImprovementArchive
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
    @Test fun improvementArchiveProjectsSqliteWorkEventsWithoutJsonl() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val key = UUID.randomUUID().toString()
        val dbName = "work-projection-$key.db"
        val root = File(context.cacheDir, "work-projection-$key").apply { mkdirs() }
        try {
            SqliteSessionEventDatabase(context, Json, dbName).use { database ->
                val sessionRoot = File(root, "sessions").apply { mkdirs() }
                val workspace = LocalWorkspace(File(root, "workspace").apply { mkdirs() })
                val registry = LocalSessionEventLogRegistry(sessionRoot, Json, sqlite = database)
                val archive = LocalWorkImprovementArchive(workspace, registry)
                val live = registry.get("work-facts")
                live.append("agent/run-checkpoint", buildJsonObject { put("mode", "work") })
                live.append("turn/end", buildJsonObject { put("reason", "completed") })
                live.append("tool/result", buildJsonObject { put("is_error", true) })
                archive.refresh()
                val summary = workspace.readRaw("自我改进/00-自我改进总览.md")
                assertTrue(summary.contains("Work 已结算回合：1"))
                assertTrue(summary.contains("Work 工具结果：1；其中错误结果：1"))
                assertFalse(File(sessionRoot, "work-facts.events.jsonl").exists())
                live.append("turn/end", buildJsonObject { put("reason", "completed") })
                archive.refresh()
                assertTrue(workspace.readRaw("自我改进/00-自我改进总览.md")
                    .contains("Work 已结算回合：2"))
            }
        } finally {
            context.deleteDatabase(dbName)
            root.deleteRecursively()
        }
    }

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

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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Native runtime registry seam: reads/writes/repair/projection use one SQL authority,
 * with no JSONL sidecar created for a fresh session.
 */
@RunWith(AndroidJUnit4::class)
class SqliteLocalSessionEventAdapterAndroidTest {

    @Test fun toolActivityAndArtifactProjectionWindowsRespectTheirDeclaredLimits() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val key = UUID.randomUUID().toString()
        val name = "work-tool-window-$key.db"
        val legacyPath = File(context.cacheDir, "$key.events.jsonl")
        try {
            SqliteSessionEventDatabase(context, Json, name).use { db ->
                val log = LocalSessionEventLog(legacyPath, Json, sessionId = key, sqlite = db)
                // The old SQLite contract rejected limit=1024 even when the log had one row.
                log.append("tool/call", buildJsonObject {
                    put("id", "tool-start")
                    put("name", "read")
                })
                assertEquals(1, log.pageBeforeChronological(limit = 1_024).size)
                // After >200 rows, a Work projection must see the full window in order.
                repeat(230) { index ->
                    log.append("progress/step", buildJsonObject { put("index", index) })
                }
                log.append("tool/result", buildJsonObject {
                    put("id", "tool-start")
                    put("name", "read")
                    put("content", "读取结束")
                })
                val toolWindow = log.pageBeforeChronological(limit = 1_024)
                assertEquals(232, toolWindow.size)
                assertEquals((0L..231L).toList(), toolWindow.map { it.sequence })
                assertEquals(232, log.pageBeforeChronological(limit = 4_096).size)
                val toolFacts = com.labteto.dshmobile.local.tools.localToolActivityProjectionEvents(log)
                assertEquals(listOf("tool/call", "tool/result"), toolFacts.map { it.type })
                assertEquals(listOf(0L, 231L), toolFacts.map { it.sequence })
                // The narrow transcript paging contract is not weakened.
                assertEquals(200, log.pageBeforeNewestFirst(limit = 200).size)
                log.close()
            }
        } finally {
            context.deleteDatabase(name)
            legacyPath.delete()
        }
    }
    @Test fun adapterUsesSqliteForPagingRepairProjectionAndReset() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val key = UUID.randomUUID().toString()
        val name = "native-sqlite-adapter-$key.db"
        val legacyPath = File(context.cacheDir, "$key.events.jsonl")
        try {
            SqliteSessionEventDatabase(context, Json, name).use { db ->
                val log = LocalSessionEventLog(
                    file = legacyPath,
                    json = Json,
                    sessionId = key,
                    sqlite = db,
                )
                assertEquals(-1L, log.latestSequence())
                assertEquals(0L, log.append("turn/start", buildJsonObject {}).sequence)
                log.append("step/start", buildJsonObject { put("step", 1) })
                log.append("tool/call", buildJsonObject {
                    put("id", "call-1")
                    put("name", "side_effect")
                    put("step", 1)
                    put("execution_started", true)
                })
                val repaired = log.repairInterruptedTail(force = true)
                assertTrue(repaired.repaired)
                assertTrue(repaired.toolResults.any { it.callId == "call-1" })
                assertEquals("TOOL_OUTCOME_UNKNOWN", repaired.toolResults.first().code)
                assertEquals("turn/end", log.latestOf(setOf("turn/end", "turn/start"))?.type)
                assertEquals(
                    log.snapshot().map { it.sequence },
                    log.withEvents { seq -> seq.map { it.sequence }.toList() },
                )
                assertEquals(log.snapshot().takeLast(2).map { it.sequence }.asReversed(),
                    log.pageBeforeNewestFirst(limit = 2).map { it.sequence })
                assertEquals(log.snapshot().takeLast(2).map { it.sequence },
                    log.pageBeforeChronological(limit = 2).map { it.sequence })
                val identity = requireNotNull(db.instance(key))
                val latest = log.latestSequence()
                log.writeProjectionCheckpoint("ui-transcript", 1, latest, "ready", log.resetGeneration)
                assertEquals("ready", log.readProjectionCheckpoint("ui-transcript", 1)?.payload)
                assertFalse(legacyPath.exists())
                log.clear()
                assertNull(log.readProjectionCheckpoint("ui-transcript", 1))
                assertEquals(-1L, log.latestSequence())
                assertEquals(0L, log.append("turn/start", buildJsonObject {}).sequence)
                assertNotEquals(identity, db.instance(key))
                assertFalse(legacyPath.exists())
                log.close()
            }
        } finally {
            context.deleteDatabase(name)
            legacyPath.delete()
        }
    }
}

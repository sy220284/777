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

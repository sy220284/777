package com.labteto.dshmobile.local.tools

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.harness.agent.AgentToolCall
import com.labteto.dshmobile.local.session.LocalSessionEventLog
import com.labteto.dshmobile.local.session.SqliteSessionEventDatabase
import java.io.File
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Native SQLite regression: unrelated Work events must not change tool side-effect settlement. */
@RunWith(AndroidJUnit4::class)
class LocalTurnSettlementSqliteAndroidTest {
    @Test fun indexedProjectionKeepsExactAdmittedWriteIdentityAcrossLargeHistory() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "indexed-tool-settlement-${UUID.randomUUID()}.db"
        val database = SqliteSessionEventDatabase(context, Json, name)
        val log = LocalSessionEventLog(
            file = File(context.cacheDir, "$name.jsonl"),
            json = Json,
            sessionId = "work-indexed",
            sqlite = database,
        )
        try {
            log.append("step/start", buildJsonObject { put("step", 3) })
            val write = AgentToolCall("write", "write", buildJsonObject {})
            log.append("tool/call", buildJsonObject {
                put("id", write.id)
                put("name", write.name)
                put("execution_started", false)
            })
            log.append("tool/execution-started", buildJsonObject {
                put("id", write.id)
                put("execution_id", "exec-write")
                put("read_only", false)
            })
            repeat(600) { number ->
                log.append("work/progress", buildJsonObject {
                    put("content", "work progress ${number}: " + "not a tool receipt".repeat(12))
                })
            }
            val pending = pendingToolSettlements(log, listOf(write), emptySet())
            assertEquals(1, pending.size)
            assertEquals("exec-write", pending.single().executionId)
            assertTrue(pending.single().started)
            log.append("tool/result", buildJsonObject {
                put("id", "write")
                put("execution_id", "exec-write")
                put("is_error", false)
            })
            assertTrue(pendingToolSettlements(log, listOf(write), emptySet()).isEmpty())
        } finally {
            log.close()
            database.close()
            context.deleteDatabase(name)
        }
    }
}

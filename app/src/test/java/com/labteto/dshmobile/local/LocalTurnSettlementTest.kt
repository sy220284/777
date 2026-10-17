package com.labteto.dshmobile.local

import com.labteto.dshmobile.harness.agent.AgentToolCall
import com.labteto.dshmobile.harness.session.SessionRecovery
import com.labteto.dshmobile.local.session.LocalSessionEventLog
import com.labteto.dshmobile.local.tools.pendingToolSettlements
import com.labteto.dshmobile.local.work.interruptedWorkToolEvent
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalTurnSettlementTest {
    @get:Rule val files = TemporaryFolder()

    @Test
    fun cancellationUsesAdmittedReadContractAndPreservesExactExecutionInDurableResult() {
        withLog { log ->
            log.append("step/start", buildJsonObject { put("step", 2) })
            val read = call("read")
            val write = call("write")
            admit(log, read, readOnly = true)
            admit(log, write, readOnly = false)
            val pending = pendingToolSettlements(log, listOf(read, write, call("unstarted")), emptySet())
            val results = pending.map { item ->
                SessionRecovery.interruptedToolResult(item.call.id, item.call.name, 2,
                    item.started, item.verifiedReadOnly, item.executionId)
            }
            assertEquals(listOf(SessionRecovery.TOOL_READ_RESULT_UNKNOWN,
                SessionRecovery.TOOL_OUTCOME_UNKNOWN, SessionRecovery.TOOL_NOT_STARTED), results.map { it.code })
            results.forEach { log.append("tool/result", interruptedWorkToolEvent(it, "source", "cancelled")) }
            assertEquals("exec-write", log.latestMatching(setOf("tool/result")) { data ->
                data["id"]?.jsonPrimitive?.contentOrNull == write.id
            }?.data?.get("execution_id")?.jsonPrimitive?.contentOrNull)
            assertTrue(pendingToolSettlements(log, listOf(read, write, call("unstarted")), emptySet()).isEmpty())
        }
    }

    @Test
    fun oldStepCannotLendAdmissionToCurrentProviderCallIdAndCompletedCallsAreOmitted() {
        withLog { log ->
            val reused = call("reused")
            log.append("step/start", buildJsonObject { put("step", 1) })
            admit(log, reused, readOnly = false)
            log.append("step/start", buildJsonObject { put("step", 2) })
            val pending = pendingToolSettlements(log, listOf(reused, call("completed")), setOf("completed"))
            assertEquals(1, pending.size)
            assertFalse(pending.single().started)
            assertNull(pending.single().executionId)
        }
    }

    @Test
    fun persistedResultIsNotSettledAgainWhenInMemoryCompletionWasInterrupted() {
        withLog { log ->
            val read = call("read")
            log.append("step/start", buildJsonObject { put("step", 1) })
            admit(log, read, readOnly = true)
            log.append("tool/result", buildJsonObject {
                put("id", read.id); put("execution_id", "exec-read"); put("is_error", false)
            })
            assertTrue(pendingToolSettlements(log, listOf(read), emptySet()).isEmpty())
        }
    }

    private fun call(id: String) = AgentToolCall(id, id, buildJsonObject { })
    private fun admit(log: LocalSessionEventLog, call: AgentToolCall, readOnly: Boolean) {
        log.append("tool/call", buildJsonObject {
            put("id", call.id); put("name", call.name); put("execution_started", false)
        })
        log.append("tool/execution-started", buildJsonObject {
            put("id", call.id); put("execution_id", "exec-${call.id}"); put("read_only", readOnly)
        })
    }
    private fun withLog(block: (LocalSessionEventLog) -> Unit) {
        val log = LocalSessionEventLog(File(files.newFolder(), "events.jsonl"), Json)
        try { block(log) } finally { log.close() }
    }
}

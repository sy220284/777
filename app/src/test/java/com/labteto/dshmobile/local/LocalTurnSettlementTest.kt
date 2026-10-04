package com.labteto.dshmobile.local

import com.labteto.dshmobile.harness.agent.AgentToolCall
import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalTurnSettlementTest {
    @Test
    fun pendingSettlementDistinguishesStartedFromNotStartedCalls() {
        val calls = listOf(
            AgentToolCall("call-a", "write", buildJsonObject { }),
            AgentToolCall("call-b", "bash", buildJsonObject { }),
            AgentToolCall("call-c", "read", buildJsonObject { }),
        )

        val pending = pendingToolSettlements(
            calls = calls,
            startedCallIds = setOf("call-a", "call-b"),
            completedCallIds = setOf("call-a"),
        )

        assertEquals(listOf("call-b", "call-c"), pending.map { it.call.id })
        assertTrue(pending.first().started)
        assertFalse(pending.last().started)
    }

    @Test
    fun startedIdsOnlyCountExecutionsAfterCurrentStepBoundary() {
        val root = Files.createTempDirectory("tool-settlement").toFile()
        try {
            val log = LocalSessionEventLog(File(root, "events.jsonl"), Json)
            val previous = AgentToolCall("previous", "write", buildJsonObject { })
            val current = AgentToolCall("current", "write", buildJsonObject { })
            val missing = AgentToolCall("missing", "write", buildJsonObject { })

            log.append("tool/execution-started", buildJsonObject { put("id", previous.id) })
            log.append("step/start", buildJsonObject { put("step", 2) })
            log.append("tool/execution-started", buildJsonObject { put("id", current.id) })

            assertEquals(
                setOf(current.id),
                startedToolCallIdsForActiveStep(log, listOf(previous, current, missing)),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun completedCallsNeedNoSettlement() {
        val call = AgentToolCall("call-a", "read", buildJsonObject { })

        assertTrue(
            pendingToolSettlements(
                calls = listOf(call),
                startedCallIds = setOf(call.id),
                completedCallIds = setOf(call.id),
            ).isEmpty(),
        )
    }
}

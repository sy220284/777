package com.labteto.dshmobile.harness.session

import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionRecoveryTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun startedToolWithoutResultBecomesOutcomeUnknownAndTailCloses() {
        val directory = Files.createTempDirectory("session-recovery-started").toFile()
        try {
            val log = SessionEventLog(directory.resolve("events.jsonl"), json, maxBytes = 4_096, clock = { 1L })
            log.append("turn/start", buildJsonObject { })
            log.append("step/start", buildJsonObject { put("step", 1) })
            log.append("assistant/message", assistantWithTool("call-1", "write"))
            log.append("tool/call", buildJsonObject {
                put("step", 1); put("id", "call-1"); put("name", "write")
            })

            val repaired = SessionRecovery.repairInterruptedTail(log)
            assertTrue(repaired.repaired)
            assertEquals(SessionRecovery.TOOL_OUTCOME_UNKNOWN, repaired.toolResults.single().code)
            assertEquals(listOf("tool/result", "step/end", "turn/end"), repaired.appended.map { it.type })
            assertFalse(SessionRecovery.repairInterruptedTail(log).repaired)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun assistantDeclaredToolThatNeverStartedBecomesNotStarted() {
        val directory = Files.createTempDirectory("session-recovery-not-started").toFile()
        try {
            val log = SessionEventLog(directory.resolve("events.jsonl"), json, maxBytes = 4_096, clock = { 1L })
            log.append("turn/start", buildJsonObject { })
            log.append("step/start", buildJsonObject { put("step", 3) })
            log.append("assistant/message", assistantWithTool("call-2", "read"))

            val repaired = SessionRecovery.repairInterruptedTail(log)
            assertEquals(SessionRecovery.TOOL_NOT_STARTED, repaired.toolResults.single().code)
            assertEquals(3, repaired.toolResults.single().step)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun balancedTurnNeedsNoRepair() {
        val directory = Files.createTempDirectory("session-recovery-balanced").toFile()
        try {
            val log = SessionEventLog(directory.resolve("events.jsonl"), json, maxBytes = 4_096, clock = { 1L })
            log.append("turn/start", buildJsonObject { })
            log.append("turn/end", buildJsonObject { put("reason", "completed") })
            assertFalse(SessionRecovery.repairInterruptedTail(log).repaired)
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun assistantWithTool(id: String, name: String) = buildJsonObject {
        put("role", "assistant")
        put("content", "")
        put("tool_calls", buildJsonArray {
            add(buildJsonObject {
                put("id", id)
                put("type", "function")
                put("function", buildJsonObject {
                    put("name", name)
                    put("arguments", "{}")
                })
            })
        })
    }
}

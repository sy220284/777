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
    fun admittedReadOnlyOperationRetainsRetryableRecoveryAcrossColdLogReplay() {
        val directory = Files.createTempDirectory("session-recovery-read-only").toFile()
        try {
            val file = directory.resolve("events.jsonl")
            val log = SessionEventLog(file, json, maxBytes = 4_096)
            log.append("turn/start", buildJsonObject { })
            log.append("step/start", buildJsonObject { put("step", 1) })
            log.append("tool/call", buildJsonObject {
                put("id", "read"); put("goal_id", "goal"); put("execution_started", false)
            })
            log.append("tool/execution-started", buildJsonObject {
                put("id", "read"); put("execution_id", "exec-read"); put("read_only", true)
            })
            val recovered = SessionRecovery.repairInterruptedTail(log).toolResults.single()
            assertEquals(SessionRecovery.TOOL_READ_RESULT_UNKNOWN, recovered.code)
            assertTrue(recovered.modelContent.contains("\"retryable\":true"))
            val ledger = ToolAttemptLedger()
            SessionEventLog(file, json, maxBytes = 4_096).forEachAfter(-1L) { ledger.observe(it) }
            assertFalse(ledger.hasUnresolvedExternalEffects("goal"))
            assertEquals("exec-read", ledger.snapshot().single().executionId)
            assertEquals(ToolAttemptState.SETTLED, ledger.snapshot().single().state)
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun concurrentRecoveryReadersCommitOnlyOneRepairForSharedSession() {
        val directory = Files.createTempDirectory("session-recovery-exclusive").toFile()
        try {
            val path = directory.resolve("events.jsonl")
            val first = SessionEventLog(path, json, maxBytes = 4_096)
            val second = SessionEventLog(path, json, maxBytes = 4_096)
            try {
                first.append("turn/start", buildJsonObject {})
                first.append("step/start", buildJsonObject { put("step", 1) })
                first.append("tool/call", buildJsonObject {
                    put("id", "mutation")
                    put("execution_started", true)
                })
                val gate = java.util.concurrent.CountDownLatch(1)
                val threads = java.util.concurrent.Executors.newFixedThreadPool(2)
                try {
                    val futures = listOf(first, second).map { log ->
                        threads.submit<SessionRepairResult> {
                            gate.await()
                            SessionRecovery.repairInterruptedTail(log)
                        }
                    }
                    gate.countDown()
                    val results = futures.map { it.get(10, java.util.concurrent.TimeUnit.SECONDS) }
                    assertEquals(1, results.count { it.repaired })
                    assertEquals(1, first.snapshot().count { it.type == "tool/result" })
                    assertEquals(1, first.snapshot().count { it.type == "turn/end" })
                    assertEquals(SessionRecovery.TOOL_OUTCOME_UNKNOWN,
                        results.flatMap { it.toolResults }.single().code)
                } finally {
                    threads.shutdownNow()
                }
            } finally {
                second.close()
                first.close()
            }
        } finally {
            directory.deleteRecursively()
        }
    }

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
            val recovered = repaired.toolResults.single()
            assertEquals(SessionRecovery.TOOL_OUTCOME_UNKNOWN, recovered.code)
            assertTrue(recovered.modelContent.contains("\"status\":\"error\""))
            assertTrue(recovered.modelContent.contains("\"side_effect\":\"possible\""))
            assertTrue(recovered.modelContent.contains("\"retryable\":false"))
            assertEquals(listOf("tool/result", "step/end", "turn/end"), repaired.appended.map { it.type })
            assertFalse(SessionRecovery.repairInterruptedTail(log).repaired)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun newToolDeclarationWithoutExecutorAdmissionIsNotStarted() {
        val directory = Files.createTempDirectory("session-recovery-declared-only").toFile()
        try {
            val log = SessionEventLog(directory.resolve("events.jsonl"), json, maxBytes = 4_096, clock = { 1L })
            log.append("turn/start", buildJsonObject { })
            log.append("step/start", buildJsonObject { put("step", 2) })
            log.append("assistant/message", assistantWithTool("declared", "write"))
            log.append("tool/call", buildJsonObject {
                put("step", 2)
                put("id", "declared")
                put("name", "write")
                put("execution_started", false)
            })

            val recovered = SessionRecovery.repairInterruptedTail(log).toolResults.single()

            assertEquals(SessionRecovery.TOOL_NOT_STARTED, recovered.code)
            assertTrue(recovered.modelContent.contains("\"retryable\":true"))
            assertTrue(recovered.modelContent.contains("\"side_effect\":\"none\""))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun executorAdmissionAfterDeclarationBecomesOutcomeUnknown() {
        val directory = Files.createTempDirectory("session-recovery-admitted").toFile()
        try {
            val log = SessionEventLog(directory.resolve("events.jsonl"), json, maxBytes = 4_096, clock = { 1L })
            log.append("turn/start", buildJsonObject { })
            log.append("step/start", buildJsonObject { put("step", 2) })
            log.append("assistant/message", assistantWithTool("admitted", "write"))
            log.append("tool/call", buildJsonObject {
                put("step", 2)
                put("id", "admitted")
                put("name", "write")
                put("execution_started", false)
            })
            log.append("tool/execution-started", buildJsonObject {
                put("id", "admitted")
                put("name", "write")
            })

            val recovered = SessionRecovery.repairInterruptedTail(log).toolResults.single()

            assertEquals(SessionRecovery.TOOL_OUTCOME_UNKNOWN, recovered.code)
            assertTrue(recovered.modelContent.contains("\"retryable\":false"))
            assertTrue(recovered.modelContent.contains("\"side_effect\":\"possible\""))
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
            val recovered = repaired.toolResults.single()
            assertEquals(SessionRecovery.TOOL_NOT_STARTED, recovered.code)
            assertEquals(3, recovered.step)
            assertTrue(recovered.modelContent.contains("\"retryable\":true"))
            assertTrue(recovered.modelContent.contains("\"side_effect\":\"none\""))
        } finally {
            directory.deleteRecursively()
        }
    }


    @Test
    fun providerNeutralToolMetadataRecoversWithoutOpenAiToolCalls() {
        val directory = Files.createTempDirectory("session-recovery-canonical").toFile()
        try {
            val log = SessionEventLog(directory.resolve("events.jsonl"), json, maxBytes = 4_096, clock = { 1L })
            log.append("turn/start", buildJsonObject { })
            log.append("step/start", buildJsonObject { put("step", 4) })
            log.append("assistant/message", buildJsonObject {
                put("role", "assistant")
                put("content", "")
                put("model_tool_calls", buildJsonArray {
                    add(buildJsonObject {
                        put("id", "canonical-call")
                        put("name", "read")
                    })
                })
            })

            val repaired = SessionRecovery.repairInterruptedTail(log)

            assertEquals(listOf("canonical-call"), repaired.toolResults.map { it.callId })
            assertEquals("read", repaired.toolResults.single().name)
            assertEquals(SessionRecovery.TOOL_NOT_STARTED, repaired.toolResults.single().code)
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


    @Test
    fun completedStepInsideOpenTurnDoesNotResurrectOldPendingCalls() {
        val directory = Files.createTempDirectory("session-recovery-step-boundary").toFile()
        try {
            val log = SessionEventLog(directory.resolve("events.jsonl"), json, maxBytes = 700, clock = { 1L })
            log.append("turn/start", buildJsonObject { })
            log.append("step/start", buildJsonObject { put("step", 1) })
            log.append("assistant/message", assistantWithTool("old-call", "write"))
            log.append("step/end", buildJsonObject { put("step", 1) })
            log.append("step/start", buildJsonObject { put("step", 2) })
            log.append("assistant/message", assistantWithTool("current-call", "read"))

            val repaired = SessionRecovery.repairInterruptedTail(log)

            assertEquals(listOf("current-call"), repaired.toolResults.map { it.callId })
            assertEquals(2, repaired.toolResults.single().step)
            assertEquals(SessionRecovery.TOOL_NOT_STARTED, repaired.toolResults.single().code)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun largeRotatedHistoryRepairsOnlyNewestOpenTail() {
        val directory = Files.createTempDirectory("session-recovery-large-history").toFile()
        try {
            val log = SessionEventLog(directory.resolve("events.jsonl"), json, maxBytes = 4_096, clock = { 1L })
            repeat(160) { index ->
                log.append("turn/start", buildJsonObject { put("turn", index) })
                log.append("step/start", buildJsonObject { put("step", 1) })
                log.append("assistant/message", buildJsonObject {
                    put("role", "assistant")
                    put("content", "history-$index-" + "x".repeat(80))
                })
                log.append("step/end", buildJsonObject { put("step", 1) })
                log.append("turn/end", buildJsonObject { put("reason", "completed") })
            }
            assertTrue(directory.listFiles().orEmpty().any { it.name.startsWith("events.jsonl.part-") })

            log.append("turn/start", buildJsonObject { put("turn", 999) })
            log.append("step/start", buildJsonObject { put("step", 7) })
            log.append("assistant/message", assistantWithTool("tail-call", "write"))
            log.append("tool/call", buildJsonObject {
                put("step", 7); put("id", "tail-call"); put("name", "write")
            })

            val repaired = SessionRecovery.repairInterruptedTail(log)

            assertEquals(listOf("tail-call"), repaired.toolResults.map { it.callId })
            assertEquals(SessionRecovery.TOOL_OUTCOME_UNKNOWN, repaired.toolResults.single().code)
            assertEquals(listOf("tool/result", "step/end", "turn/end"), repaired.appended.map { it.type })
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun coldRecoveryPreservesUnresolvedLegacyWritesWhenProviderCallIdsAreReused() {
        val directory = Files.createTempDirectory("session-recovery-reused-legacy").toFile()
        try {
            val file = directory.resolve("events.jsonl")
            val log = SessionEventLog(file, json, maxBytes = 4_096)
            log.append("turn/start", buildJsonObject {})
            log.append("step/start", buildJsonObject { put("step", 1) })
            repeat(2) {
                log.append("tool/call", buildJsonObject {
                    put("id", "reused")
                    put("name", "write")
                    put("goal_id", "goal")
                })
            }
            // Recovery must still emit model-visible results but cannot ascribe
            // either receipt to one of the two legacy executions by call ID alone.
            val repaired = SessionRecovery.repairInterruptedTail(log)
            assertEquals(2, repaired.toolResults.size)
            assertTrue(repaired.toolResults.all {
                it.code == SessionRecovery.TOOL_OUTCOME_UNKNOWN
            })
            val ledger = ToolAttemptLedger()
            SessionEventLog(file, json, maxBytes = 4_096).use { recoveredLog ->
                recoveredLog.forEachAfter(-1L) { ledger.observe(it) }
            }
            assertEquals(2, ledger.pending().size)
            assertTrue(ledger.hasUnresolvedExternalEffects("goal"))
        } finally { directory.deleteRecursively() }
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

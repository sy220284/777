package com.labteto.dshmobile.local.usage

import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.TokenUsageAction
import com.labteto.dshmobile.local.TokenUsageContext
import com.labteto.dshmobile.local.TokenUsageRecord
import com.labteto.dshmobile.local.aggregateTokenUsageRecords
import com.labteto.dshmobile.local.runtime.LOCAL_AGENT_RUN_CHECKPOINT_EVENT
import com.labteto.dshmobile.local.runtime.LOCAL_SUBAGENT_RUN_CHECKPOINT_EVENT
import com.labteto.dshmobile.local.session.LocalSessionEventLog
import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalTokenUsageContextBridgeTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun nestedAndResumedRunsKeepDirectParentAndAggregateUnderOriginalTaskAfterRestart() {
        val directory = Files.createTempDirectory("usage-run-ancestry").toFile()
        try {
            val file = File(directory, "events.jsonl")
            val log = LocalSessionEventLog(file, json)
            checkpoint(log, "root", null, "call-root")
            checkpoint(log, "child", "root", "call-child")
            checkpoint(log, "nested", "child", "call-nested")
            checkpoint(log, "resume", "root", "call-resume")
            log.close()
            val restored = LocalSessionEventLog(file, json)
            val bridge = LocalTokenUsageContextBridge(
                sessionFacts = { LocalTokenUsageSessionFacts(LocalUsageMode.WORK, "任务") },
                eventLogFor = { restored },
                currentSessionId = { "unrelated-session" },
            )
            val nested = bridge.resolve("captured-session", "call-nested", TokenUsageAction.WORK_SUBAGENT)
            assertEquals("child", nested.parentRunId)
            assertEquals("root", nested.taskRunId)
            assertEquals("captured-session", nested.sessionId)
            val resumed = bridge.resolve("captured-session", "call-resume", TokenUsageAction.WORK_MAIN)
            assertEquals("root", resumed.taskRunId)
            val records = listOf("root", "child", "nested", "resume").mapIndexed { index, id ->
                val context = bridge.resolve("captured-session", "call-$id", TokenUsageAction.WORK_MAIN)
                TokenUsageRecord("request-$id", index.toLong(), "model", context, inputTokens = 10, outputTokens = 5, reported = true)
            }
            val snapshot = aggregateTokenUsageRecords((records + records.last()).asSequence())
            assertEquals(1, snapshot.tasks.size)
            assertEquals("root", snapshot.tasks.single().key)
            assertEquals(60L, snapshot.tasks.single().aggregate.totalTokens)
            assertEquals(4L, snapshot.tracked.requestCount)
            restored.close()
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun duplicateCallIdsStayBoundToCapturedSessionAfterForegroundSwitch() {
        val root = Files.createTempDirectory("usage-cross-session").toFile()
        try {
            val first = LocalSessionEventLog(File(root, "a.jsonl"), json)
            val second = LocalSessionEventLog(File(root, "b.jsonl"), json)
            checkpoint(first, "task-a", null, "shared-call")
            checkpoint(second, "task-b", null, "shared-call")
            val bridge = LocalTokenUsageContextBridge(
                sessionFacts = { LocalTokenUsageSessionFacts(LocalUsageMode.WORK, it) },
                eventLogFor = { if (it == "session-a") first else second },
                currentSessionId = { "session-b" },
            )
            assertEquals("task-a", bridge.resolve(
                "session-a", "shared-call", TokenUsageAction.WORK_MAIN,
            ).taskRunId)
            assertEquals("task-b", bridge.resolve(
                "session-b", "shared-call", TokenUsageAction.WORK_MAIN,
            ).taskRunId)
            first.close()
            second.close()
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun oldAccountingPayloadRemainsReadableWithoutTaskRoot() {
        val context = json.decodeFromString(TokenUsageContext.serializer(), """{"mode":"WORK","runId":"child","parentRunId":"root"}""")
        assertNull(context.taskRunId)
        val record = TokenUsageRecord("legacy", 1, "model", context, inputTokens = 12, reported = true)
        assertEquals("root", aggregateTokenUsageRecords(sequenceOf(record)).tasks.single().key)
    }

    @Test
    fun missingCheckpointUsesKnownAncestorAndCorruptCycleDoesNotInventRoot() {
        val directory = Files.createTempDirectory("usage-run-missing").toFile()
        try {
            val log = LocalSessionEventLog(File(directory, "events.jsonl"), json)
            checkpoint(log, "child", "missing-root", "call-child")
            assertEquals("missing-root", resolveTokenUsageTaskRunId(log, "child"))
            checkpoint(log, "a", "b", "call-a")
            checkpoint(log, "b", "a", "call-b")
            assertNull(resolveTokenUsageTaskRunId(log, "a"))
            assertNull(resolveTokenUsageTaskRunId(log, null))
            log.close()
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun checkpoint(log: LocalSessionEventLog, id: String, parent: String?, call: String) {
        log.append(if (parent == null) LOCAL_AGENT_RUN_CHECKPOINT_EVENT else LOCAL_SUBAGENT_RUN_CHECKPOINT_EVENT, buildJsonObject {
            put("run_id", id)
            put("call_id", call)
            put("run_kind", if (parent == null) "foreground" else "subagent")
            put("input", "同一工作任务")
            parent?.let { put("parent_run_id", it) }
        })
    }
}

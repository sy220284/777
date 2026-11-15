package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.harness.jobs.JobSnapshot
import com.labteto.dshmobile.local.jobs.LocalJobInfo
import com.labteto.dshmobile.local.jobs.LocalJobRegistration
import com.labteto.dshmobile.local.session.LocalSessionEventLog
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalAgentOutputReaderTest {
    @get:Rule val folder = TemporaryFolder()
    private fun log() = LocalSessionEventLog(File(folder.root, "events.jsonl"), Json,
        sessionId = "session-a")
    private fun withLog(block: (LocalSessionEventLog) -> Unit) {
        val log = log()
        try { block(log) } finally { log.close() }
    }
    private fun registration(id: String = "job-child", status: String = "dormant") =
        LocalJobRegistration(
            LocalJobInfo(id, "子代理：核查", status, ownerSessionId = "session-a", isAgent = true),
            JobSnapshot(id = id, label = "子代理：核查", status = status,
                ownerId = "session-a", output = "状态摘要"),
        )
    private fun LocalSessionEventLog.result(id: String, output: String) =
        append(LOCAL_SUBAGENT_HISTORY_CHECKPOINT_EVENT, encodeLocalSubagentHistoryCheckpoint(
            id, localPersistentSubagentId(id), 3, listOf(buildJsonObject {
                put("role", "assistant"); put("content", output); put("_dsh_completion", "COMPLETED")
            }), emptySet(), terminalOutput = output, resultId = "result-$id",
        ))

    @Test fun completedResultComesFromTheExactDurableChildAfterReopen() {
        val output = "开头\n" + "结果正文".repeat(20_000) + "\n结尾"
        withLog { it.result("job-child", output); it.result("job-other", "另一个任务") }
        withLog { assertEquals(output, readLocalAgentOutput(it, registration())) }
    }

    @Test fun terminalJobWithoutResultDoesNotPassItsSummaryOffAsTheDeliverable() {
        withLog { log ->
            log.result("job-other", "其他结果")
            assertThrows(IllegalStateException::class.java) {
                readLocalAgentOutput(log, registration())
            }
        }
    }

    @Test fun activeJobShowsCurrentProgressInsteadOfAnOlderCompletedResult() {
        withLog { log ->
            log.result("job-child", "上一轮结果")
            assertEquals("状态摘要", readLocalAgentOutput(log, registration(status = "running")))
        }
    }
}

package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.session.LocalSessionEventLog
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalWorkOpenGraphProjectionTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun incrementallyReadsNewFactsAndInvalidatesAfterClear() {
        val log = LocalSessionEventLog(File(temporary.root, "facts.jsonl"), Json)
        val projection = LocalWorkOpenGraphProjection()
        fun fact(id: String) = buildJsonObject {
            put("graph_id", id); put("graph_execution_id", "$id-run"); put("goal_id", "goal")
        }
        assertTrue(projection.unresolved(log).isEmpty())
        log.append("taskgraph/start", fact("first"))
        assertEquals(listOf("first"), projection.unresolved(log).map { it.graphId })
        log.append("other-event", buildJsonObject { put("note", "not a graph") })
        assertEquals(listOf("first"), projection.unresolved(log).map { it.graphId })
        log.append("taskgraph/end", fact("first"))
        assertTrue(projection.unresolved(log).isEmpty())
        log.append("taskgraph/start", fact("second"))
        assertEquals(listOf("second"), projection.unresolved(log).map { it.graphId })
        log.clear()
        assertTrue(projection.unresolved(log).isEmpty())
        log.append("taskgraph/start", fact("third"))
        assertEquals(listOf("third"), projection.unresolved(log).map { it.graphId })
        log.close()
    }

    @Test fun acceptedResultsAndAdmissionsShareTheIncrementalCursor() {
        val log = LocalSessionEventLog(File(temporary.root, "evidence.jsonl"), Json)
        val projection = LocalWorkRecoveryProjections.forLog(log)
        assertTrue(projection.acceptedFor(log, "execution-a").first.isEmpty())
        log.append("taskgraph/job-admitted", buildJsonObject {
            put("graph_execution_id", "execution-a"); put("node_id", "a"); put("job_id", "job-a")
        })
        val first = log.append("taskgraph/job-result", buildJsonObject {
            put("graph_execution_id", "execution-a"); put("job_id", "job-a"); put("output", "durable")
        })
        log.append("taskgraph/node-accepted", buildJsonObject {
            put("graph_execution_id", "execution-a"); put("node_id", "a"); put("result_sequence", first.sequence)
        })
        assertEquals("job-a", projection.nodeAdmissions(log, "execution-a", "a").single()["job_id"]
            ?.toString()?.trim('"'))
        assertEquals("durable", projection.acceptedFor(log, "execution-a").second[first.sequence]
            ?.get("output")?.toString()?.trim('"'))
        assertTrue(projection.acceptedFor(log, "execution-b").first.isEmpty())
        assertEquals(first.sequence, projection.jobResult(log, "job-a")?.sequence)
        assertEquals(first.sequence, projection.acceptedFor(log, "execution-a").second.keys.single())
        log.clear()
        assertTrue(projection.acceptedFor(log, "execution-a").first.isEmpty())
        assertTrue(projection.nodeAdmissions(log, "execution-a", "a").isEmpty())
        assertNull(projection.jobResult(log, "job-a"))
        log.close()
    }

    @Test fun toolAttemptReceiptsStayScopedToTheOriginalPersistentJob() {
        val log = LocalSessionEventLog(File(temporary.root, "tool-attempts.jsonl"), Json)
        val projection = LocalWorkRecoveryProjections.forLog(log)
        val jobId = "job-graph-a"
        log.append("agent/subagent-run-checkpoint", buildJsonObject {
            put("agent_id", localPersistentSubagentId(jobId)); put("run_id", "run-1")
        })
        log.append("tool/execution-started", buildJsonObject {
            put("id", "call-1"); put("execution_id", "effect-1")
            put("name", "file_write"); put("read_only", false)
            put("attributes", buildJsonObject { put("run_id", "run-1") })
        })
        assertTrue(projection.toolAttempts(log, jobId).single().unresolvedExternalEffect)
        log.append("subagent/tool-result", buildJsonObject {
            put("background_job_id", "different-job"); put("id", "call-1")
            put("execution_id", "effect-1"); put("side_effect", "confirmed")
        })
        assertTrue(projection.toolAttempts(log, jobId).single().unresolvedExternalEffect)
        val receipt = log.append("subagent/tool-result", buildJsonObject {
            put("background_job_id", jobId); put("id", "call-1")
            put("execution_id", "effect-1"); put("side_effect", "confirmed")
        })
        assertTrue(projection.toolAttempts(log, jobId).single().settled)
        assertEquals(receipt.sequence, projection.toolResult(log, jobId, "effect-1", "call-1")?.sequence)
        log.clear()
        assertTrue(projection.toolAttempts(log, jobId).isEmpty())
        log.close()
    }
}

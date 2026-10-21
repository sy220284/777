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
    @Test fun reusedToolCallRetainsUnknownSecondExecutionAcrossIncrementalQueries() {
        val log = LocalSessionEventLog(File(temporary.root, "reused-call.jsonl"), Json)
        val projection = LocalWorkOpenGraphProjection()
        val job = "job-reused"
        log.append("agent/subagent-run-checkpoint", buildJsonObject {
            put("agent_id", localPersistentSubagentId(job)); put("run_id", "run")
        })
        fun start(execution: String, readOnly: Boolean) {
            log.append("subagent/tool-call", buildJsonObject {
                put("agent_id", localPersistentSubagentId(job)); put("id", "same")
                put("execution_started", false)
            })
            log.append("tool/execution-started", buildJsonObject {
                put("id", "same"); put("execution_id", execution); put("read_only", readOnly)
                put("attributes", buildJsonObject { put("run_id", "run") })
            })
        }
        start("first", true)
        log.append("subagent/tool-result", buildJsonObject {
            put("background_job_id", job); put("id", "same"); put("execution_id", "first")
        })
        assertTrue(projection.toolAttempts(log, job).single().settled)
        start("second", false)
        assertEquals(2, projection.toolAttempts(log, job).size)
        assertTrue(projection.toolAttempts(log, job).last().unresolvedExternalEffect)
        log.append("subagent/tool-result", buildJsonObject {
            put("background_job_id", job); put("id", "same"); put("execution_id", "second")
            put("is_error", true); put("error_code", "TOOL_OUTCOME_UNKNOWN")
        })
        val attempts = projection.toolAttempts(log, job)
        assertTrue(attempts.last().outcomeUncertain)
        assertEquals(attempts, LocalWorkOpenGraphProjection().toolAttempts(log, job))
        log.clear()
        assertTrue(projection.toolAttempts(log, job).isEmpty())
        log.close()
    }

    @Test fun closedGraphPayloadRemainsReadableFromDurableLog() {
        val log = LocalSessionEventLog(File(temporary.root, "closed-graph.jsonl"), Json)
        val projection = LocalWorkOpenGraphProjection()
        val result = log.append("taskgraph/job-result", buildJsonObject {
            put("graph_execution_id", "execution"); put("job_id", "job")
            put("output", "large-result".repeat(10_000))
        })
        log.append("taskgraph/node-accepted", buildJsonObject {
            put("graph_execution_id", "execution"); put("node_id", "node")
            put("result_sequence", result.sequence)
        })
        log.append("taskgraph/end", buildJsonObject {
            put("graph_id", "graph"); put("graph_execution_id", "execution")
        })
        assertEquals(result, projection.jobResult(log, "job"))
        assertEquals(result.data, projection.acceptedFor(log, "execution").second[result.sequence])
        assertEquals(result, LocalWorkOpenGraphProjection().jobResult(log, "job"))
        log.close()
    }

    @Test fun lateRunCheckpointRebuildsEarlierFactsWithoutAcceptingForeignReceipts() {
        val log = LocalSessionEventLog(File(temporary.root, "late-checkpoint.jsonl"), Json)
        val projection = LocalWorkOpenGraphProjection()
        val job = "job-late"
        fun checkpoint(run: String) = log.append("agent/subagent-run-checkpoint", buildJsonObject {
            put("agent_id", localPersistentSubagentId(job)); put("run_id", run)
        })
        fun start(run: String, execution: String) = log.append("tool/execution-started", buildJsonObject {
            put("id", "same"); put("execution_id", execution); put("read_only", false)
            put("attributes", buildJsonObject { put("run_id", run) })
        })
        checkpoint("first-run")
        start("first-run", "first")
        assertEquals(1, projection.toolAttempts(log, job).size)
        start("second-run", "second")
        // Ownership has not been durably associated with this Job yet.
        assertEquals(1, projection.toolAttempts(log, job).size)
        checkpoint("second-run")
        assertEquals(2, projection.toolAttempts(log, job).size)
        log.append("subagent/tool-result", buildJsonObject {
            put("background_job_id", "job-foreign"); put("id", "same"); put("execution_id", "second")
        })
        log.append("subagent/tool-result", buildJsonObject {
            put("background_job_id", job); put("id", "same")
        })
        assertTrue(projection.toolAttempts(log, job).all { it.unresolvedExternalEffect })
        log.append("subagent/tool-result", buildJsonObject {
            put("background_job_id", job); put("id", "same"); put("execution_id", "first")
        })
        val attempts = projection.toolAttempts(log, job)
        assertTrue(attempts.first().settled)
        assertTrue(attempts.last().unresolvedExternalEffect)
        assertEquals(attempts, LocalWorkOpenGraphProjection().toolAttempts(log, job))
        log.close()
    }

    @Test fun incompleteIndexedFactsAreNotCommittedAsSafeAndCanBeReread() {
        val file = File(temporary.root, "incomplete-facts.jsonl")
        val log = LocalSessionEventLog(file, Json)
        val projection = LocalWorkOpenGraphProjection()
        val job = "job-incomplete"
        log.append("agent/subagent-run-checkpoint", buildJsonObject {
            put("agent_id", localPersistentSubagentId(job)); put("run_id", "run")
        })
        log.append("tool/execution-started", buildJsonObject {
            put("id", "write"); put("execution_id", "effect"); put("read_only", false)
            put("attributes", buildJsonObject { put("run_id", "run") })
        })
        log.append("other-event", buildJsonObject { put("note", "preserve latest sequence") })
        projection.unresolved(log) // Index the durable admission before the read failure.
        val original = file.readText()
        file.writeText(original.lineSequence().filterNot { it.contains("tool/execution-started") }
            .filter { it.isNotBlank() }.joinToString("\n", postfix = "\n"))
        org.junit.Assert.assertThrows(IllegalStateException::class.java) {
            projection.toolAttempts(log, job)
        }
        file.writeText(original)
        assertTrue(projection.toolAttempts(log, job).single().unresolvedExternalEffect)
        log.close()
    }

    @Test fun unreadableAdmissionIsNotReportedAsAnUnadmittedNode() {
        val file = File(temporary.root, "missing-admission.jsonl")
        val log = LocalSessionEventLog(file, Json)
        val projection = LocalWorkOpenGraphProjection()
        log.append("taskgraph/job-admitted", buildJsonObject {
            put("graph_execution_id", "execution"); put("node_id", "node"); put("job_id", "job")
        })
        log.append("other-event", buildJsonObject { put("note", "preserve latest sequence") })
        assertEquals(1, projection.nodeAdmissions(log, "execution", "node").size)
        val original = file.readText()
        file.writeText(original.lineSequence().filterNot { it.contains("taskgraph/job-admitted") }
            .filter { it.isNotBlank() }.joinToString("\n", postfix = "\n"))
        org.junit.Assert.assertThrows(IllegalStateException::class.java) {
            projection.nodeAdmissions(log, "execution", "node")
        }
        file.writeText(original)
        assertEquals(1, projection.nodeAdmissions(log, "execution", "node").size)
        log.close()
    }

}

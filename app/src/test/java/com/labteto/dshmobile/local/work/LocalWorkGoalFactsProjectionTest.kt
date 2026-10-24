package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.harness.session.ToolAttemptLedger
import com.labteto.dshmobile.local.session.LocalSessionEventLog
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalWorkGoalFactsProjectionTest {
    @get:Rule val temporary = TemporaryFolder()
    private val goal = LocalGoal(description = "交付", id = "goal", startedAtSequence = -1L)

    @Test fun evictedIndicesRebuildExactResultsWithoutRetainingClosedJobHistory() {
        val log = LocalSessionEventLog(File(temporary.root, "eviction.jsonl"), Json)
        try {
            repeat(12) { n -> log.append("taskgraph/job-result", buildJsonObject {
                put("job_id", "job-$n"); put("graph_execution_id", "graph-$n")
            }) }
            val projection = LocalWorkOpenGraphProjection(maxCachedFacts = 4)
            val first = projection.jobResult(log, "job-0")!!
            assertTrue(projection.cachedFactCount() <= 4)
            assertEquals(11L, projection.indexedThroughSequence())
            assertEquals(first, projection.jobResult(log, "job-0"))
            assertEquals(0, projection.lastRefreshEvents)
            assertEquals(11L, projection.jobResult(log, "job-11")!!.sequence)
            assertEquals(0, projection.lastRefreshEvents)
            assertTrue(projection.cachedFactCount() <= 4)
            log.append("taskgraph/job-result", buildJsonObject {
                put("job_id", "job-12"); put("graph_execution_id", "graph-12")
            })
            assertEquals(12L, projection.jobResult(log, "job-12")!!.sequence)
            assertEquals(1, projection.lastRefreshEvents)
            assertEquals(12L, projection.indexedThroughSequence())
        } finally { log.close() }
    }

    @Test fun thousandsOfClosedJobsRetainCursorAndNeverFullReplayOnRepeatedQueries() {
        val log = LocalSessionEventLog(File(temporary.root, "long-cold-history.jsonl"), Json)
        try {
            repeat(5_000) { n ->
                log.append("taskgraph/job-result", buildJsonObject {
                    put("job_id", "job-$n")
                    put("graph_execution_id", "execution-$n")
                })
            }
            val projection = LocalWorkOpenGraphProjection(maxCachedFacts = 4)
            assertEquals(0L, projection.jobResult(log, "job-0")!!.sequence)
            assertEquals(5_000, projection.lastRefreshEvents)
            assertEquals(4_999L, projection.indexedThroughSequence())
            repeat(3) {
                assertEquals(0L, projection.jobResult(log, "job-0")!!.sequence)
                assertEquals(4_999L, projection.jobResult(log, "job-4999")!!.sequence)
                assertEquals(0, projection.lastRefreshEvents)
            }
            assertTrue(projection.cachedFactCount() <= 4)
        } finally { log.close() }
    }

    @Test fun repeatedAssessmentAppliesOnlyNewEventsAndMatchesFullRebuild() {
        val log = LocalSessionEventLog(File(temporary.root, "goal.jsonl"), Json)
        repeat(2_000) { log.append("note", buildJsonObject { put("text", "history") }) }
        log.append("tool/execution-started", buildJsonObject {
            put("id", "write"); put("execution_id", "effect"); put("read_only", false)
            put("attributes", buildJsonObject { put("goal_id", goal.id) })
        })
        val projection = LocalWorkGoalFactsProjection()
        val first = projection.assess(log, goal)
        assertTrue(first.hasUnresolvedTools())
        assertEquals(2_001, first.appliedEvents)
        assertEquals(0, projection.assess(log, goal).appliedEvents)
        log.append("tool/result", buildJsonObject {
            put("id", "write"); put("execution_id", "effect"); put("is_error", false)
        })
        val settled = projection.assess(log, goal)
        assertEquals(1, settled.appliedEvents)
        assertFalse(settled.hasUnresolvedTools())
        val reference = ToolAttemptLedger()
        log.forEachAfter(-1L) { reference.observe(it.type, it.data) }
        assertEquals(reference.snapshot(), settled.attempts)
        val cold = LocalWorkGoalFactsProjection().assess(log, goal)
        assertEquals(0, cold.appliedEvents)
        assertEquals(settled.attempts, cold.attempts)
        log.close()
    }

    @Test fun corruptCheckpointAndResetRebuildWithoutLosingUnresolvedGraph() {
        val file = File(temporary.root, "rebuild.jsonl")
        val log = LocalSessionEventLog(file, Json)
        log.append("taskgraph/start", buildJsonObject {
            put("goal_id", goal.id); put("graph_id", "graph"); put("graph_execution_id", "execution")
        })
        assertTrue(LocalWorkGoalFactsProjection().assess(log, goal).hasUnsettledGraphs)
        File(file.parentFile, file.name + ".projection-work.goal-facts.json").writeText("broken")
        val projection = LocalWorkGoalFactsProjection()
        val rebuilt = projection.assess(log, goal)
        assertEquals(1, rebuilt.appliedEvents)
        assertTrue(rebuilt.hasUnsettledGraphs)
        log.clear()
        assertFalse(projection.assess(log, goal).hasUnsettledGraphs)
        log.close()
        assertThrows(IllegalStateException::class.java) { projection.assess(log, goal) }
    }
    @Test fun writerOwnedCompletionAndConcurrentUiAssessmentUseTheSameLockOrder() {
        val log = LocalSessionEventLog(File(temporary.root, "lock-order.jsonl"), Json)
        log.append("note", buildJsonObject { put("text", "source") })
        val projection = LocalWorkOpenGraphProjection()
        val writerHeld = java.util.concurrent.CountDownLatch(1)
        val queryStarted = java.util.concurrent.CountDownLatch(1)
        lateinit var reader: Thread
        val writerTask = java.util.concurrent.FutureTask<Unit> {
            log.withWriterLease {
                writerHeld.countDown()
                assertTrue(queryStarted.await(5, java.util.concurrent.TimeUnit.SECONDS))
                val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
                while (reader.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.yield()
                assertEquals(Thread.State.BLOCKED, reader.state)
                projection.goalAssessment(log, goal)
                projection.unresolved(log)
            }
            Unit
        }
        val readerTask = java.util.concurrent.FutureTask<Unit> {
            assertTrue(writerHeld.await(5, java.util.concurrent.TimeUnit.SECONDS))
            queryStarted.countDown()
            projection.goalAssessment(log, goal)
            Unit
        }
        reader = Thread(readerTask, "goal-ui-reader").apply { isDaemon = true }
        val writer = Thread(writerTask, "goal-completion-writer").apply { isDaemon = true }
        writer.start(); reader.start()
        writerTask.get(10, java.util.concurrent.TimeUnit.SECONDS)
        readerTask.get(10, java.util.concurrent.TimeUnit.SECONDS)
        log.close()
        log.close() // Closing remains idempotent after the lock-order change.
    }

}

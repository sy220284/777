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

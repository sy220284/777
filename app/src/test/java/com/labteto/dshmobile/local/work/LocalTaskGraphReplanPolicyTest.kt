package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.session.LocalSessionEventLog
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalTaskGraphReplanPolicyTest {
    private fun plan(
        first: String = "research",
        dependent: Boolean = true,
        write: Boolean = false,
        goal: String = "goal-v1",
    ) = LocalWorkTaskGraphPlan(buildJsonObject {
        put("allow_mutation", write)
        put("goal_fingerprint", goal)
        put("nodes", buildJsonArray {
            add(buildJsonObject {
                put("node_id", "a"); put("task", first)
                put("depends_on", buildJsonArray {})
            })
            add(buildJsonObject {
                put("node_id", "b"); put("task", "verify")
                put("depends_on", buildJsonArray { if (dependent) add(JsonPrimitive("a")) })
            })
        })
    })

    @Test fun changedDAGInvalidatesEveryPriorAcceptanceAndCreatesNewPlanIdentity() {
        val previous = plan()
        val changed = plan(first = "research revised", dependent = false)
        val admit = admitLocalTaskGraphReplan(previous, changed)
        assertEquals(setOf("a", "b"), admit.invalidatedNodeIds)
        assertEquals(previous.version, admit.oldPlanVersion)
        assertEquals(changed.version, admit.newPlanVersion)
        assertTrue(admit.oldPlanVersion != admit.newPlanVersion)
        assertTrue(previous.nodeVersion(previous.nodes.first()) !=
            changed.nodeVersion(changed.nodes.first()))
    }

    @Test fun writableSideEffectsRequireSeparateVerificationBeforeReplanning() {
        assertTrue(runCatching { admitLocalTaskGraphReplan(plan(write = true), plan(first = "new")) }
            .exceptionOrNull()?.message.orEmpty().contains("TASKGRAPH_REPLAN_WRITABLE"))
        assertTrue(runCatching { admitLocalTaskGraphReplan(plan(), plan(write = true)) }.isFailure)
    }

    @Test fun unchangedOrNewGoalCannotClaimAnImplicitGraphReplan() {
        assertTrue(runCatching { admitLocalTaskGraphReplan(plan(), plan()) }
            .exceptionOrNull()?.message.orEmpty().contains("TASKGRAPH_REPLAN_NO_CHANGES"))
        assertTrue(runCatching { admitLocalTaskGraphReplan(plan(), plan(goal = "other-goal")) }
            .exceptionOrNull()?.message.orEmpty().contains("TASKGRAPH_REPLAN_GOAL_CHANGED"))
    }
    @Test fun coldRecoveryRecognizesDurablyStartedSuccessorBeforeOldEndEvent() {
        val path = Files.createTempFile("replan-recovery-", ".jsonl").toFile()
        val old = plan()
        val revised = plan(first = "new directions", dependent = false)
        try {
            var log = LocalSessionEventLog(path, Json)
            log.append("taskgraph/start", buildJsonObject {
                put("graph_id", "old-graph")
                put("graph_execution_id", "old-execution")
                put("session_id", "root")
                put("goal_fingerprint", "goal-v1")
                put("plan", old.value)
            })
            log.append("taskgraph/start", buildJsonObject {
                put("graph_id", "new-graph")
                put("graph_execution_id", "new-execution")
                put("replaces_graph_id", "old-graph")
                put("session_id", "root")
                put("goal_fingerprint", "goal-v1")
                put("plan", revised.value)
            })
            log.close()
            log = LocalSessionEventLog(path, Json)
            try {
                assertEquals("new-graph", durableTaskGraphReplacement(
                    log, "root", "old-graph", "old-execution", old))
                assertEquals(null, durableTaskGraphReplacement(
                    log, "other-session", "old-graph", "old-execution", old))
                assertEquals(null, durableTaskGraphReplacement(
                    log, "root", "old-graph", "old-execution", plan(write = true)))
                assertEquals(null, durableTaskGraphReplacement(
                    log, "root", "other-old-graph", "old-execution", old))
            } finally { log.close() }
        } finally { path.delete() }
    }


}

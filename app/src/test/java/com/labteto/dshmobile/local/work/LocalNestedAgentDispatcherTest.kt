package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.harness.agent.AgentToolCall
import com.labteto.dshmobile.local.agent.LocalAgentParentLink
import com.labteto.dshmobile.local.agent.LocalAgentSubtreeBudget
import com.labteto.dshmobile.local.agent.LocalSubagentCapabilities
import com.labteto.dshmobile.local.agent.LocalSubagentResult
import com.labteto.dshmobile.local.agent.LocalSubagentStatus
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Actual nested dispatcher boundary with a deterministic scripted child instead of paid model calls. */
class LocalNestedAgentDispatcherTest {
    private fun call(id: String = "child-tool") = AgentToolCall(
        id, "subagent", buildJsonObject {
            put("task", "verify recursive data")
            put("role", "verify")
            put("max_steps", 3)
        },
    )

    @Test fun threeLevelChainReturnsLeafEvidenceWithoutIncreasingAuthority() = runBlocking {
        val visited = mutableListOf<Int>()
        val sharedBudget = LocalAgentSubtreeBudget(maxDescendants = 3, maxDescendantModelSteps = 10)
        suspend fun execute(level: Int, caps: LocalSubagentCapabilities): LocalSubagentResult {
            visited += level
            if (level == 3) {
                assertFalse(caps.allowMutation)
                assertFalse(caps.continuable)
                assertEquals(3, caps.depth)
                return LocalSubagentResult(LocalSubagentStatus.COMPLETED, "leaf-evidence")
            }
            val tool = dispatchLocalNestedSubagent(
                call = call("tool-level-$level"),
                parentCapabilities = caps,
                parentProfileId = "fixed-model",
                parentMaxSteps = 8,
                subtreeBudget = sharedBudget,
                availableAgentSlots = { 3 },
                captureParent = { id ->
                    LocalAgentParentLink("test-session", "run-$level", "child-level-$level", id)
                },
                runChild = { spec, budget ->
                    assertTrue(budget === sharedBudget)
                    assertEquals("fixed-model", spec.modelOverride)
                    assertEquals(level + 1, spec.capabilities.depth)
                    assertEquals("run-$level", spec.parentLink?.parentRunId)
                    execute(level + 1, spec.capabilities)
                },
            )
            return LocalSubagentResult(
                if (tool.isError) LocalSubagentStatus.FAILED else LocalSubagentStatus.COMPLETED,
                tool.content,
            )
        }

        val result = execute(1, LocalSubagentCapabilities(maxDepth = 3))
        assertEquals(LocalSubagentStatus.COMPLETED, result.status)
        assertEquals("leaf-evidence", result.output)
        assertEquals(listOf(1, 2, 3), visited)
        assertEquals(2, sharedBudget.used())
    }

    @Test fun unknownParentIdentityAndResourceExhaustionCannotStartChild() = runBlocking {
        val budget = LocalAgentSubtreeBudget(maxDescendants = 2)
        var attempts = 0
        suspend fun admit(slots: Int, parent: LocalAgentParentLink?) =
            dispatchLocalNestedSubagent(
                call = call(), parentCapabilities = LocalSubagentCapabilities(maxDepth = 3),
                parentProfileId = "pinned", parentMaxSteps = 10, subtreeBudget = budget,
                availableAgentSlots = { slots },
                captureParent = { parent },
                runChild = { _, _ ->
                    attempts++
                    LocalSubagentResult(LocalSubagentStatus.COMPLETED, "unreachable")
                },
            )
        val noSlot = admit(0, LocalAgentParentLink("s", "p", "slot"))
        assertTrue(noSlot.isError)
        assertTrue(noSlot.content.contains("NO_AGENT_SLOT"))
        val noParent = admit(2, null)
        assertTrue(noParent.isError)
        assertTrue(noParent.content.contains("REQUIRES_LIVE_PARENT_RUN"))
        assertEquals(0, attempts)
        assertEquals(0, budget.used())
    }

    @Test fun scriptedChildTimeoutProducesExplicitFailureAndLeavesOwnershipToParent() = runBlocking {
        val budget = LocalAgentSubtreeBudget()
        val result = dispatchLocalNestedSubagent(
            call = call(), parentCapabilities = LocalSubagentCapabilities(maxDepth = 2),
            parentProfileId = "model", parentMaxSteps = 6, subtreeBudget = budget,
            availableAgentSlots = { 1 },
            captureParent = { LocalAgentParentLink("s", "r", "slot") },
            executionDeadlineOverrideMillis = 20L,
            runChild = { _, _ ->
                delay(200L)
                LocalSubagentResult(LocalSubagentStatus.COMPLETED, "late-result")
            },
        )
        assertTrue(result.isError)
        assertTrue(result.content.contains("NESTED_DELEGATION_TIMEOUT"))
        assertEquals(1, budget.used())
    }

    @Test fun cancellationPropagatesThroughDispatcherInsteadOfBecomingToolResult() = runBlocking {
        var completed = false
        val child = launch {
            dispatchLocalNestedSubagent(
                call = call(), parentCapabilities = LocalSubagentCapabilities(maxDepth = 2),
                parentProfileId = "model", parentMaxSteps = 6,
                subtreeBudget = LocalAgentSubtreeBudget(),
                availableAgentSlots = { 1 },
                captureParent = { LocalAgentParentLink("s", "r", "slot") },
                runChild = { _, _ ->
                    delay(60_000L)
                    completed = true
                    LocalSubagentResult(LocalSubagentStatus.COMPLETED, "not-authorized")
                },
            )
        }
        delay(40L)
        child.cancelAndJoin()
        assertTrue(child.isCancelled)
        assertFalse(completed)
    }
}

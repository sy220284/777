package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.harness.agent.AgentEvent
import com.labteto.dshmobile.harness.agent.AgentEventSink
import com.labteto.dshmobile.harness.agent.AgentModel
import com.labteto.dshmobile.harness.agent.AgentModelReply
import com.labteto.dshmobile.harness.agent.AgentStopReason
import com.labteto.dshmobile.harness.agent.AgentToolCall
import com.labteto.dshmobile.harness.agent.AgentToolExecutor
import com.labteto.dshmobile.harness.agent.AgentTurnEngine
import com.labteto.dshmobile.local.agent.LocalAgentParentLink
import com.labteto.dshmobile.local.agent.LocalAgentSubtreeBudget
import com.labteto.dshmobile.local.agent.LocalSubagentCapabilities
import com.labteto.dshmobile.local.agent.LocalSubagentResult
import com.labteto.dshmobile.local.agent.LocalSubagentStatus
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Actual shared production AgentTurnEngine + real recursive dispatcher.
 * Provider responses and parent identities are deterministically scripted;
 * this is a native execution-flow test, not a live external model test.
 */
class LocalRecursiveAgentTurnEngineTest {
    @Test fun threeAgentLevelsCompleteToolLoopsAndPropagateEvidence() = runBlocking {
        val budget = LocalAgentSubtreeBudget(maxDescendants = 2)
        val events = mutableListOf<Pair<Int, AgentEvent>>()
        val admitted = mutableListOf<Int>()

        suspend fun runLevel(level: Int, capabilities: LocalSubagentCapabilities): LocalSubagentResult {
            admitted += level
            var queries = 0
            val engine = AgentTurnEngine(
                model = AgentModel { messages ->
                    queries += 1
                    if (level == 3) {
                        assertFalse(capabilities.allowMutation)
                        AgentModelReply(content = "verified-leaf")
                    } else if (queries == 1) {
                        AgentModelReply(toolCalls = listOf(AgentToolCall(
                            id = "tool-depth-$level",
                            name = "subagent",
                            arguments = buildJsonObject {
                                put("task", "verify next depth")
                                put("role", "verify")
                                put("max_steps", 3)
                            },
                        )))
                    } else {
                        val childOutput = messages.last().content
                        assertTrue(childOutput.contains("verified-leaf"))
                        AgentModelReply(content = "level-$level accepted: $childOutput")
                    }
                },
                tools = AgentToolExecutor { call ->
                    assertTrue(level < 3)
                    dispatchLocalNestedSubagent(
                        call = call,
                        parentCapabilities = capabilities,
                        parentProfileId = "test-profile",
                        parentMaxSteps = 3,
                        subtreeBudget = budget,
                        availableAgentSlots = { 3 },
                        captureParent = { toolId ->
                            LocalAgentParentLink("native-test", "run-$level", "slot-$level", toolId)
                        },
                        runChild = { spec, shared ->
                            assertTrue(shared === budget)
                            assertEquals("test-profile", spec.modelOverride)
                            assertEquals(level + 1, spec.capabilities.depth)
                            assertEquals("run-$level", spec.parentLink?.parentRunId)
                            assertTrue(spec.instructions.contains("verify"))
                            runLevel(level + 1, spec.capabilities)
                        },
                    )
                },
                eventSink = AgentEventSink { e -> events += level to e },
                maxSteps = 3,
                idFactory = { "turn-$level" },
            )
            val result = engine.run("level-$level work")
            assertEquals(AgentStopReason.COMPLETED, result.stopReason)
            return LocalSubagentResult(LocalSubagentStatus.COMPLETED, result.answer)
        }

        val output = runLevel(1, LocalSubagentCapabilities(maxDepth = 3))
        assertEquals(LocalSubagentStatus.COMPLETED, output.status)
        assertTrue(output.output.contains("verified-leaf"))
        assertEquals(listOf(1, 2, 3), admitted)
        assertEquals(2, budget.used())
        assertEquals(2, events.count { it.second is AgentEvent.ToolStarted })
        assertEquals(2, events.count { it.second is AgentEvent.ToolFinished })
        assertEquals(3, events.count { it.second is AgentEvent.TurnCompleted })
    }
}

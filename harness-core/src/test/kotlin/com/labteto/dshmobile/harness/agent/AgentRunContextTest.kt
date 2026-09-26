package com.labteto.dshmobile.harness.agent

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRunContextTest {
    @Test
    fun runContextCanClampStepsAndKeepStableRunIdentity() = runBlocking {
        var calls = 0
        val loop = AgentLoop(
            model = AgentModel {
                calls += 1
                AgentModelReply(
                    toolCalls = listOf(
                        AgentToolCall("c$calls", "read", buildJsonObject {}),
                    ),
                )
            },
            tools = AgentToolExecutor {
                AgentToolResult("ok")
            },
            maxSteps = 8,
        )
        val context = AgentRunContext(
            runId = "run-fixed",
            sessionId = "session-1",
            modelRoute = AgentModelRoute("deepseek", "https://api.deepseek.com", "deepseek-flash"),
            resources = AgentResourceBudget(maxSteps = 2),
        )

        val result = loop.run("go", context = context)

        assertEquals("run-fixed", result.turnId)
        assertEquals(2, result.steps)
        assertEquals(AgentStopReason.STEP_LIMIT, result.stopReason)
        assertEquals(2, calls)
    }

    @Test
    fun concreteToolViewBlocksInvisibleToolInCore() = runBlocking {
        var executed = false
        var modelCalls = 0
        val loop = AgentLoop(
            model = AgentModel {
                modelCalls += 1
                if (modelCalls == 1) {
                    AgentModelReply(
                        toolCalls = listOf(
                            AgentToolCall("hidden", "bash", buildJsonObject {}),
                        ),
                    )
                } else {
                    AgentModelReply(content = "done")
                }
            },
            tools = AgentToolExecutor {
                executed = true
                AgentToolResult("should not run")
            },
        )
        val context = AgentRunContext(
            runId = "run-tools",
            sessionId = "session-1",
            modelRoute = AgentModelRoute("deepseek", "https://api.deepseek.com", "deepseek-flash"),
            toolView = AgentToolView(setOf("read")),
        )

        val result = loop.run("go", context = context)

        assertFalse(executed)
        assertEquals("done", result.answer)
        val tool = result.messages.first { it.role == "tool" }
        assertTrue(tool.content.contains("TOOL_NOT_VISIBLE"))
    }
}

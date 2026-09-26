package com.labteto.dshmobile.harness.agent

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRunInterceptorTest {
    private fun context() = AgentRunContext(
        runId = "run-hook",
        sessionId = "session-hook",
        modelRoute = AgentModelRoute("deepseek", "https://api.deepseek.com", "m"),
    )

    @Test
    fun beforeHookCanRejectWithoutCallingModelAndFailureHookObservesIt() = runBlocking {
        var modelCalled = false
        var failureObserved = false
        val events = mutableListOf<AgentEvent>()
        val loop = AgentLoop(
            model = AgentModel {
                modelCalled = true
                AgentModelReply(content = "unexpected")
            },
            tools = AgentToolExecutor { AgentToolResult("ok") },
            eventSink = AgentEventSink { events += it },
            runInterceptors = listOf(
                object : AgentRunInterceptor {
                    override suspend fun beforeRun(context: AgentRunContext, input: String) {
                        error("blocked-by-plugin")
                    }

                    override suspend fun onRunFailure(context: AgentRunContext, error: Throwable) {
                        failureObserved = error.message == "blocked-by-plugin"
                    }
                },
            ),
        )

        val failure = runCatching { loop.run("hello", context = context()) }.exceptionOrNull()

        assertFalse(modelCalled)
        assertTrue(failureObserved)
        assertTrue(failure is IllegalStateException)
        assertTrue(events.any { it is AgentEvent.TurnFailed })
    }

    @Test
    fun afterHookObservesStableCompletedResult() = runBlocking {
        var observed: AgentRunResult? = null
        val loop = AgentLoop(
            model = AgentModel { AgentModelReply(content = "done") },
            tools = AgentToolExecutor { AgentToolResult("ok") },
            runInterceptors = listOf(
                object : AgentRunInterceptor {
                    override suspend fun afterRun(context: AgentRunContext, result: AgentRunResult) {
                        observed = result
                    }
                },
            ),
        )

        val result = loop.run("hello", context = context())

        assertEquals("done", result.answer)
        assertEquals(result, observed)
    }
}

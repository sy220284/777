package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.harness.agent.AgentToolSideEffect
import com.labteto.dshmobile.harness.tools.ToolAccess
import com.labteto.dshmobile.local.model.LocalToolCall
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalParallelToolFailureTest {
    private val call = LocalToolCall("child-1", "subagent", JsonObject(emptyMap()), "{}")

    @Test
    fun parallelAgentFailureWithPossibleSideEffectMustNotAdvertiseRetry() {
        val result = parallelToolFailure(call, ToolAccess.AGENT_CONTROL, "failed after starting")
        assertTrue(result.isError)
        assertFalse(result.retryable)
        assertEquals(AgentToolSideEffect.POSSIBLE, result.sideEffect)
    }

    @Test
    fun readOnlyParallelFailureMayBeRetried() {
        val result = parallelToolFailure(call, ToolAccess.READ_ONLY, "failed before mutation")
        assertTrue(result.retryable)
        assertEquals(AgentToolSideEffect.NONE, result.sideEffect)
    }
}

package com.labteto.dshmobile.local.work

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalWorkAgentControlBuiltinRuntimeTest {
    @Test
    fun agentControlToolSurfaceIsExplicitAndComplete() {
        assertEquals(
            setOf(
                "subagent",
                "spawn_subagent",
                "subagent_fork",
                "fork_subagent",
                "list_subagent_models",
                "list_agents",
                "send_message",
                "interrupt_agent",
                "workflow",
            ),
            LocalWorkAgentControlBuiltinRuntime.TOOL_NAMES,
        )
    }
}

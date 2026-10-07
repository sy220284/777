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
                "team_members",
                "team_spawn",
                "team_send_message",
                "team_task_create",
                "team_task_get",
                "team_task_list",
                "team_task_update",
                "team_interrupt",
                "team_wait",
            ),
            LocalWorkAgentControlBuiltinRuntime.TOOL_NAMES,
        )
    }
}

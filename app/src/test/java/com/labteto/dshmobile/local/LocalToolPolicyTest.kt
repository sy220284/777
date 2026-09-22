package com.labteto.dshmobile.local

import com.labteto.dshmobile.harness.tools.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class LocalToolPolicyTest {
    @Test fun everyCatalogEntryHasAnExplicitPolicy() {
        LocalToolCatalog.specs.forEach {
            val name = it.jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content
            LocalToolPolicy.access(name)
            LocalToolPolicy.approval(name)
        }
        assertTrue(runCatching { LocalToolPolicy.access("new_undeclared_tool") }.isFailure)
    }

    @Test fun aliasesCannotBypassApprovalOrReadOnlyScope() = runTest {
        for (name in listOf("write", "write_file", "edit", "edit_file", "bash", "run_shell")) {
            var executed = 0
            var approvals = 0
            val registry = ToolRegistry()
            val canonical = LocalToolPolicy.canonical(name)
            registry.register(HarnessTool(canonical, JsonObject(emptyMap()), LocalToolPolicy.access(name),
                LocalToolPolicy.approval(name), executor = HarnessToolExecutor { _, _, _ ->
                    executed++; ToolResult("ok")
                }))
            assertTrue(registry.execute(canonical, JsonObject(emptyMap())).isError)
            assertEquals(0, executed)
            assertTrue(registry.execute(canonical, JsonObject(emptyMap()), context = ToolContext(
                allowMutation = false, approval = { approvals++; true },
            )).isError)
            assertEquals(0, approvals)
            assertFalse(registry.execute(canonical, JsonObject(emptyMap()), context = ToolContext(
                approval = { approvals++; true },
            )).isError)
            assertEquals(1, approvals)
            assertEquals(1, executed)
        }
    }

    @Test fun planCanBeSubmittedWithoutEnablingExecution() {
        assertTrue(LocalToolPolicy.allowedInPlan("exit_plan_mode", ToolAccess.SESSION_WRITE))
        assertTrue(LocalToolPolicy.allowedInPlan("ask_user_question", ToolAccess.SESSION_WRITE))
        assertFalse(LocalToolPolicy.allowedInPlan("write", ToolAccess.WORKSPACE_WRITE))
        assertFalse(LocalToolPolicy.allowedInPlan("subagent_fork", ToolAccess.AGENT_CONTROL))
    }
}

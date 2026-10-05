package com.labteto.dshmobile.local

import com.labteto.dshmobile.harness.tools.*
import com.labteto.dshmobile.local.runtime.canAutoApproveSafely
import com.labteto.dshmobile.local.tools.LocalAutoApprovalScope
import com.labteto.dshmobile.local.tools.LocalToolCatalog
import com.labteto.dshmobile.local.tools.LocalToolPolicy
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
            LocalToolPolicy.metadata(name)
            LocalToolPolicy.autoApprovalScope(name)
            LocalToolPolicy.exposure(name)
        }
        assertTrue(runCatching { LocalToolPolicy.access("new_undeclared_tool") }.isFailure)
        assertTrue(runCatching { LocalToolPolicy.exposure("new_undeclared_tool") }.isFailure)
    }

    @Test fun ordinaryExecutionToolsStayCoreWhileNarrowCapabilitiesAreDeferred() {
        for (name in listOf(
            "read", "tool_output_read", "write", "edit", "apply_patch", "file_inspect",
            "list_files", "glob", "grep", "bash", "capability_search", "update_plan",
            "exit_plan_mode", "ask_user_question", "subagent", "session_event_search",
        )) {
            assertEquals(name, ToolExposure.CORE, LocalToolPolicy.exposure(name))
        }
        for (name in listOf(
            "web_search", "web_fetch", "http_request", "download_file", "network_diagnose",
            "job_list", "job_output", "job_kill", "json_query", "environment_info",
            "todo_write", "create_goal", "get_goal", "update_goal", "skill",
            "subagent_fork", "list_subagent_models", "list_agents", "send_message",
            "interrupt_agent", "workflow", "memory_search", "memory_list", "memory_remember",
            "memory_update", "memory_forget", "session_search", "session_trace",
            "session_event_trace", "session_event_read", "present",
        )) {
            assertEquals(name, ToolExposure.OPTIONAL, LocalToolPolicy.exposure(name))
        }
    }

    @Test fun tokenAnalysisDiscoversStructuredEnvironmentInfoInsteadOfRawLogScanning() {
        val keywords = LocalToolPolicy.metadata("environment_info").discoveryKeywords
        assertTrue("token" in keywords)
        assertTrue("token消耗" in keywords)
        assertEquals(ToolExposure.OPTIONAL, LocalToolPolicy.exposure("environment_info"))
        assertEquals(ToolAccess.READ_ONLY, LocalToolPolicy.access("environment_info"))
    }

    @Test fun aliasesCannotBypassApprovalOrReadOnlyScope() = runTest {
        for (name in listOf("write", "write_file", "edit", "edit_file", "bash", "run_shell")) {
            var executed = 0
            var approvals = 0
            val registry = ToolRegistry()
            val canonical = LocalToolPolicy.canonical(name)
            registry.register(HarnessTool(
                name = canonical,
                schema = functionToolSchema(canonical, "测试工具"),
                access = LocalToolPolicy.access(name),
                approvalPolicy = LocalToolPolicy.approval(name),
                exposure = ToolExposure.CORE,
                metadata = LocalToolPolicy.metadata(name),
                executor = HarnessToolExecutor { _, _, _ ->
                    executed++; ToolResult("ok")
                },
            ))
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

    @Test fun safeAutoApprovalScopeFollowsTheSandboxBoundary() {
        for (name in listOf("write", "edit", "apply_patch", "download_file")) {
            assertEquals(LocalAutoApprovalScope.WORKSPACE, LocalToolPolicy.autoApprovalScope(name))
        }
        assertEquals(LocalAutoApprovalScope.NONE, LocalToolPolicy.autoApprovalScope("bash"))
        for (name in listOf("read", "file_inspect", "session_trace", "session_event_read")) {
            assertEquals(LocalAutoApprovalScope.READ_ONLY, LocalToolPolicy.autoApprovalScope(name))
        }
        for (name in listOf("job_kill", "http_request", "memory_update", "memory_forget", "present")) {
            assertEquals(LocalAutoApprovalScope.NONE, LocalToolPolicy.autoApprovalScope(name))
        }
        assertTrue(runCatching { LocalToolPolicy.autoApprovalScope("new_undeclared_tool") }.isFailure)
    }

    @Test fun planCanBeSubmittedWithoutEnablingExecution() {
        assertTrue(LocalToolPolicy.allowedInPlan("exit_plan_mode", ToolAccess.SESSION_WRITE))
        assertTrue(LocalToolPolicy.allowedInPlan("ask_user_question", ToolAccess.SESSION_WRITE))
        assertFalse(LocalToolPolicy.allowedInPlan("write", ToolAccess.WORKSPACE_WRITE))
        assertFalse(LocalToolPolicy.allowedInPlan("subagent_fork", ToolAccess.AGENT_CONTROL))
    }

    @Test fun invocationSemanticsKeepBackgroundFetchOutOfReadOnlyAndPlanScopes() {
        val foreground = buildJsonObject {
            put("url", "https://example.com")
            put("run_in_background", false)
        }
        val background = buildJsonObject {
            put("url", "https://example.com")
            put("run_in_background", true)
        }

        assertTrue(LocalToolPolicy.isReadOnlyInvocation("web_fetch", ToolAccess.NETWORK, foreground))
        assertFalse(LocalToolPolicy.isReadOnlyInvocation("web_fetch", ToolAccess.NETWORK, background))
        assertTrue(LocalToolPolicy.allowedInPlan("web_fetch", ToolAccess.NETWORK, foreground))
        assertFalse(LocalToolPolicy.allowedInPlan("web_fetch", ToolAccess.NETWORK, background))
    }

    @Test fun modelToolCallMustBelongToTheExactVisibleStepSurface() {
        assertTrue(LocalToolPolicy.isVisibleCall("read", setOf("read", "grep")))
        assertTrue(LocalToolPolicy.isVisibleCall("read_file", setOf("read")))
        assertFalse(LocalToolPolicy.isVisibleCall("process_exec", setOf("read", "grep")))
    }

    @Test fun shellRemainsHighRiskWhileGlobalAutoApprovalMayApproveIt() {
        assertEquals(ToolApprovalPolicy.ALWAYS, LocalToolPolicy.approval("bash"))
        assertEquals(LocalAutoApprovalScope.NONE, LocalToolPolicy.autoApprovalScope("bash"))
        assertFalse(canAutoApproveSafely(toolForPolicy("bash")))
    }

    private fun toolForPolicy(name: String) = HarnessTool(
        name = name,
        schema = functionToolSchema(name, "测试工具"),
        access = LocalToolPolicy.access(name),
        approvalPolicy = LocalToolPolicy.approval(name),
        exposure = ToolExposure.CORE,
        metadata = LocalToolPolicy.metadata(name),
        executor = HarnessToolExecutor { _, _, _ -> ToolResult("ok") },
    )

}

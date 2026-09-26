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
            LocalToolPolicy.autoApprovalScope(name)
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

    @Test fun shellAlwaysRequiresExplicitApprovalWithoutProcessSandbox() {
        for (command in listOf(
            "ls -la",
            "git status",
            "npm test",
            "cat /storage/emulated/0/Download/a.txt",
            "cat /system/build.prop",
            "git reset --hard",
            "git push --force",
        )) {
            assertFalse(
                "Android 无进程级 workspace-write 沙箱时 shell 不应自动批准: $command",
                LocalToolPolicy.canAutoApproveCommand(command),
            )
        }
        assertTrue(LocalToolPolicy.shellRiskReason("ls -la").contains("workspace-write"))
        assertTrue(LocalToolPolicy.shellRiskReason("cat /system/build.prop").contains("系统/内核"))
    }

    @Test fun forcePushIsCaughtRegardlessOfArgumentOrder() {
        // A substring test for "push --force" would miss every one of these.
        for (command in listOf(
            "git push --force", "git push -f", "git push origin main --force",
            "git push origin main -f", "git push --force-with-lease",
            "git push --force origin main",
        )) {
            assertFalse("强推应弹窗: $command", LocalToolPolicy.canAutoApproveCommand(command))
        }

        // Other uses of a force flag that are not a push must not be caught by the push heuristic.
        assertTrue("非推送的 -f 不应拦截", LocalToolPolicy.canAutoApproveCommand("ls -f"))
        assertTrue("普通推送不应拦截", LocalToolPolicy.canAutoApproveCommand("git push origin main"))
    }


}

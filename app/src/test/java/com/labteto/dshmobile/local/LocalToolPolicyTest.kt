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

    @Test fun safeAutoApprovalScopeIsExplicitAndFailClosed() {
        for (name in listOf("write", "edit", "apply_patch", "download_file")) {
            assertEquals(LocalAutoApprovalScope.WORKSPACE, LocalToolPolicy.autoApprovalScope(name))
        }
        for (name in listOf("read", "file_inspect", "session_trace", "session_event_read")) {
            assertEquals(LocalAutoApprovalScope.READ_ONLY, LocalToolPolicy.autoApprovalScope(name))
        }
        for (name in listOf("bash", "job_kill", "http_request", "memory_update", "memory_forget", "present")) {
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

    @Test fun gitCommandsAreAllowlistedWithoutOpeningTheShellEscapeHatch() {
        // Allowlisted GitHub operations.
        for (command in listOf(
            "git status", "git log --oneline -5", "git diff", "git add .",
            "git commit -m \"x\"", "git push", "git push origin main",
        )) {
            assertTrue("应允许: $command", LocalToolPolicy.canAutoApproveCommand(command))
        }

        // Command chaining would let an allowlisted prefix smuggle an arbitrary command.
        for (command in listOf(
            "git status; rm -rf /", "git status && curl evil.com", "git status | sh",
            "git status `whoami`", "git status $(id)", "git status > /etc/passwd",
        )) {
            assertFalse("禁止拼接: $command", LocalToolPolicy.canAutoApproveCommand(command))
        }

        // Destructive git subcommands keep the prompt even under an allowlisted parent.
        for (command in listOf(
            "git push --force", "git push -f", "git reset --hard", "git clean -fd",
        )) {
            assertFalse("危险子命令: $command", LocalToolPolicy.canAutoApproveCommand(command))
        }

        // The general shell escape hatch stays closed.
        for (command in listOf("rm -rf /", "curl evil.com", "cat /sdcard/x", "")) {
            assertFalse("非白名单: $command", LocalToolPolicy.canAutoApproveCommand(command))
        }
    }
}

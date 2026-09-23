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
        for (name in listOf("write", "edit", "apply_patch", "download_file", "bash")) {
            assertEquals(LocalAutoApprovalScope.WORKSPACE, LocalToolPolicy.autoApprovalScope(name))
        }
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

    @Test fun shellCommandsAreApprovedUnlessTheyTargetFirmwareOrRewriteHistory() {
        // Ordinary user-space work needs no prompt: the sandbox, not the prompt, is the guard.
        for (command in listOf(
            "ls -la", "git status", "git push", "git push origin main", "git add .",
            "git commit -m \"x\"", "rm -rf ./build", "npm test",
            "cat /storage/emulated/0/Download/a.txt",
        )) {
            assertTrue("应免弹窗: $command", LocalToolPolicy.canAutoApproveCommand(command))
        }

        // Commands that name firmware keep the prompt as a second line of defence.
        for (command in listOf(
            "cat /system/build.prop", "ls /vendor/lib64", "rm /system/bin/x",
            "cat /proc/sys/kernel/panic", "cat /proc/meminfo", "ls /sys",
            "cat /sys/class/net/wlan0/address", "cat /dev/null",
            "ls /data/system", "ls /data/adb",
        )) {
            assertFalse("固件路径应弹窗: $command", LocalToolPolicy.canAutoApproveCommand(command))
        }

        // Irreversible history rewrites stay user-visible.
        for (command in listOf(
            "git reset --hard", "git clean -fd", "git clean -fdx", "git filter-branch",
        )) {
            assertFalse("危险子命令应弹窗: $command", LocalToolPolicy.canAutoApproveCommand(command))
        }

        assertFalse("空命令不应放行", LocalToolPolicy.canAutoApproveCommand(""))
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

    /**
     * Documents a known limit rather than asserting a guarantee.
     *
     * Command text admits many equivalent spellings, so the marker check is bypassable by design of
     * the shell, not by oversight. What actually holds the line is the kernel: firmware is mounted
     * read-only. These cases are asserted as *currently* uncaught so that a future change tightening
     * matching will surface here instead of silently diverging from this note.
     */
    @Test fun firmwareMarkersAreBestEffortAndSomeSpellingsSlipThrough() {
        for (command in listOf(
            "cd / && cat system/build.prop",
            "cat /sy''stem/build.prop",
        )) {
            assertTrue(
                "已知绕过，实际拦截依赖内核只读挂载: $command",
                LocalToolPolicy.canAutoApproveCommand(command),
            )
        }
    }
}

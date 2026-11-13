package com.labteto.dshmobile.harness.tools

import com.labteto.dshmobile.harness.plugin.HarnessContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeModePluginTest {
    private fun plan(vararg names: String) = buildJsonObject {
        put("steps", buildJsonArray {
            names.forEach { name ->
                add(buildJsonObject {
                    put("tool", name)
                    put("args", buildJsonObject {})
                })
            }
        })
    }

    private fun register(
        registry: ToolRegistry,
        name: String,
        access: ToolAccess = ToolAccess.READ_ONLY,
        exposure: ToolExposure = ToolExposure.CORE,
        approval: ToolApprovalPolicy = ToolApprovalPolicy.NEVER,
        execute: suspend () -> ToolResult,
    ) {
        registry.register(HarnessTool(
            name, functionToolSchema(name, "测试"), access, approval, exposure,
            ToolMetadata("test", discoveryKeywords = setOf("test")),
            executor = HarnessToolExecutor { _, _, _ -> execute() },
        ))
    }

    @Test fun orderedStepsUseRealApprovalAndExecutionReceipts() = runTest {
        val registry = ToolRegistry()
        val calls = mutableListOf<String>()
        register(registry, "read") { calls += "read"; ToolResult("loaded") }
        register(registry, "write", ToolAccess.WORKSPACE_WRITE,
            approval = ToolApprovalPolicy.ALWAYS) { calls += "write"; ToolResult("saved") }
        CodeModePlugin(registry).install(HarnessContext(registry))
        var approvals = 0
        val receipts = mutableListOf<String>()
        val result = registry.execute("code_mode_run", plan("read", "write"),
            context = ToolContext(sessionId = "s", approval = { approvals++; true },
                onExecutionFinished = { tool, _ -> receipts += tool.name }))
        assertFalse(result.isError)
        assertEquals(listOf("read", "write"), calls)
        assertEquals(2, approvals)
        assertEquals(listOf("read", "write", "code_mode_run"), receipts)
        assertTrue(result.content.contains("\"completed_steps\":2"))
    }

    @Test fun internalToolsCannotBeSmuggledIntoScript() = runTest {
        val registry = ToolRegistry()
        var called = false
        register(registry, "private", exposure = ToolExposure.INTERNAL) {
            called = true
            ToolResult("secret")
        }
        CodeModePlugin(registry).install(HarnessContext(registry))
        val result = registry.execute("code_mode_run", plan("private"),
            context = ToolContext(approval = { true }))
        assertTrue(result.isError)
        assertEquals("CODE_MODE_UNAUTHORIZED_TOOL", result.errorCode)
        assertFalse(called)
    }

    @Test fun scriptCannotCallCodeModeRecursively() = runTest {
        val registry = ToolRegistry()
        CodeModePlugin(registry).install(HarnessContext(registry))
        val result = registry.execute("code_mode_run", plan("code_mode_run"),
            context = ToolContext(approval = { true }))
        assertTrue(result.isError)
        assertEquals("CODE_MODE_RECURSION_DENIED", result.errorCode)
    }

    @Test fun invalidLaterStepRejectsTheWholeScriptBeforeAnyMutation() = runTest {
        val registry = ToolRegistry()
        var writes = 0
        register(registry, "write", ToolAccess.WORKSPACE_WRITE) {
            writes++
            ToolResult("saved")
        }
        CodeModePlugin(registry).install(HarnessContext(registry))
        val result = registry.execute("code_mode_run", plan("write", "not_registered"),
            context = ToolContext(approval = { true }))
        assertEquals("CODE_MODE_TOOL_MISSING", result.errorCode)
        assertTrue(result.isError)
        assertEquals(0, writes)
    }

    @Test fun toolExceptionsProduceAnExplicitUnknownEffectReceipt() = runTest {
        val registry = ToolRegistry()
        var laterCalls = 0
        register(registry, "throws") { throw IllegalStateException("private diagnostic") }
        register(registry, "later") { laterCalls++; ToolResult("oops") }
        CodeModePlugin(registry).install(HarnessContext(registry))
        val result = registry.execute("code_mode_run", plan("throws", "later"),
            context = ToolContext(approval = { true }))
        assertEquals("CODE_MODE_STEP_EXCEPTION", result.errorCode)
        assertEquals(0, laterCalls)
        assertTrue(result.content.contains("unknown_after_exception"))
        assertFalse(result.content.contains("private diagnostic"))
    }

    @Test fun firstFailureStopsLaterMutationWithoutAutomaticRetry() = runTest {
        val registry = ToolRegistry()
        var writes = 0
        register(registry, "fail") { ToolResult("broken", isError = true, errorCode = "FAIL") }
        register(registry, "write", ToolAccess.WORKSPACE_WRITE) {
            writes++
            ToolResult("written")
        }
        CodeModePlugin(registry).install(HarnessContext(registry))
        val result = registry.execute("code_mode_run", plan("fail", "write"),
            context = ToolContext(approval = { true }))
        assertTrue(result.isError)
        assertEquals("CODE_MODE_STEP_FAILED", result.errorCode)
        assertEquals(0, writes)
        assertTrue(result.content.contains("\"completed_steps\":0"))
    }

    @Test fun mutationScopeAndMissingApprovalStillApplyToWholeScript() = runTest {
        val registry = ToolRegistry()
        var called = false
        register(registry, "read") { called = true; ToolResult("ok") }
        CodeModePlugin(registry).install(HarnessContext(registry))
        val noMutation = registry.executeTracked("code_mode_run", plan("read"),
            context = ToolContext(allowMutation = false, approval = { true }))
        val noApproval = registry.executeTracked("code_mode_run", plan("read"))
        assertFalse(noMutation.executionStarted)
        assertFalse(noApproval.executionStarted)
        assertEquals("MUTATION_SCOPE_BLOCKED", noMutation.result.errorCode)
        assertEquals("APPROVAL_REQUIRED", noApproval.result.errorCode)
        assertFalse(called)
    }
}

package com.labteto.dshmobile.harness.tools

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.*
import org.junit.Test

class ToolBindingSnapshotTest {
    private fun tool(name: String, execute: () -> Unit) = HarnessTool(
        name, functionToolSchema(name, "sampled handler"), ToolAccess.READ_ONLY,
        ToolApprovalPolicy.NEVER, ToolExposure.CORE, ToolMetadata("test"),
        executor = HarnessToolExecutor { _, _, _ -> execute(); ToolResult("ok") },
    )

    @Test
    fun replacementAfterSamplingCannotExecuteWithTheOldModelContract() = runTest {
        val registry = ToolRegistry()
        var invoked = 0
        val original = tool("read_file") { invoked++ }
        registry.register(original)
        val step = registry.captureBindings(JsonArray(listOf(original.schema)))
        registry.register(tool("read_file") { invoked++ }, replace = true)
        val result = withContext(step) { registry.executeTracked("read_file", buildJsonObject {}) }
        assertEquals("TOOL_REGISTRATION_CHANGED", result.result.errorCode)
        assertFalse(result.executionStarted)
        assertEquals(0, invoked)
    }

    @Test
    fun newlyRegisteredToolWaitsForTheNextSamplingRequest() = runTest {
        val registry = ToolRegistry()
        val before = registry.captureBindings(JsonArray(emptyList()))
        var invoked = 0
        val added = tool("new_tool") { invoked++ }
        registry.register(added)
        withContext(before) {
            assertFalse(registry.executeTracked("new_tool", buildJsonObject {}).executionStarted)
        }
        val after = registry.captureBindings(JsonArray(listOf(added.schema)))
        withContext(after) {
            assertTrue(registry.executeTracked("new_tool", buildJsonObject {}).executionStarted)
        }
        assertEquals(1, invoked)
    }

    @Test
    fun nestedStepDoesNotPublishItsBindingsIntoTheParent() = runTest {
        val registry = ToolRegistry()
        val first = tool("first") {}
        val second = tool("second") {}
        registry.register(first)
        registry.register(second)
        val parent = registry.captureBindings(JsonArray(listOf(first.schema)))
        val child = registry.captureBindings(JsonArray(listOf(second.schema)))
        withContext(parent) {
            withContext(child) {
                assertTrue(registry.executeTracked("second", buildJsonObject {}).executionStarted)
            }
            assertFalse(registry.executeTracked("second", buildJsonObject {}).executionStarted)
            assertTrue(registry.executeTracked("first", buildJsonObject {}).executionStarted)
        }
    }
    private fun codeModePlan(name: String) = buildJsonObject {
        put("steps", buildJsonArray {
            add(buildJsonObject {
                put("tool", name)
                put("args", buildJsonObject {})
            })
        })
    }

    @Test
    fun codeModeOnlySampleCanExecutePreviouslyBoundCoreTool() = runTest {
        val registry = ToolRegistry()
        var invoked = 0
        registry.register(tool("read_file") { invoked++ })
        CodeModePlugin(registry).install(com.labteto.dshmobile.harness.plugin.HarnessContext(registry))
        val snapshot = registry.captureBindings(JsonArray(listOf(
            requireNotNull(registry.get("code_mode_run")).schema,
        )))
        withContext(snapshot) {
            val unexposed = registry.executeTracked("read_file", buildJsonObject {})
            assertFalse(unexposed.executionStarted)
            assertEquals("TOOL_REGISTRATION_CHANGED", unexposed.result.errorCode)
            val script = registry.execute("code_mode_run", codeModePlan("read_file"),
                context = ToolContext(approval = { true }))
            assertFalse(script.isError)
        }
        assertEquals(1, invoked)
    }

    @Test
    fun codeModeExecutesSelectedOptionalToolsButNeverUnselectedOrReplacedTools() = runTest {
        val registry = ToolRegistry()
        var publishedCalls = 0
        var hiddenCalls = 0
        val first = HarnessTool(
            name = "mcp_selected_echo",
            schema = functionToolSchema("mcp_selected_echo", "MCP 已加载工具"),
            access = ToolAccess.PRIVILEGED,
            approvalPolicy = ToolApprovalPolicy.ALWAYS,
            exposure = ToolExposure.OPTIONAL,
            metadata = ToolMetadata("MCP"),
            executor = HarnessToolExecutor { _, _, _ ->
                publishedCalls++
                ToolResult("remote-ok")
            },
        )
        val hidden = first.copy(
            name = "mcp_hidden_echo",
            schema = functionToolSchema("mcp_hidden_echo", "未加载工具"),
            executor = HarnessToolExecutor { _, _, _ ->
                hiddenCalls++
                ToolResult("should-not-run")
            },
        )
        registry.register(first)
        registry.register(hidden)
        CodeModePlugin(registry).install(com.labteto.dshmobile.harness.plugin.HarnessContext(registry))
        val frozen = registry.captureBindings(JsonArray(listOf(
            requireNotNull(registry.get("code_mode_run")).schema,
            requireNotNull(registry.get("mcp_selected_echo")).schema,
        )))
        withContext(frozen) {
            val allowed = registry.execute("code_mode_run", codeModePlan("mcp_selected_echo"),
                context = ToolContext(approval = { true }))
            assertFalse(allowed.isError)
            assertTrue(allowed.content.contains("remote-ok"))
            val denied = registry.execute("code_mode_run", codeModePlan("mcp_hidden_echo"),
                context = ToolContext(approval = { true }))
            assertEquals("CODE_MODE_UNSAMPLED_TOOL", denied.errorCode)
        }
        assertEquals(1, publishedCalls)
        assertEquals(0, hiddenCalls)
        registry.register(first.copy(executor = HarnessToolExecutor { _, _, _ ->
            publishedCalls++
            ToolResult("wrong-version")
        }), replace = true)
        withContext(frozen) {
            val revoked = registry.execute("code_mode_run", codeModePlan("mcp_selected_echo"),
                context = ToolContext(approval = { true }))
            assertEquals("CODE_MODE_UNSAMPLED_TOOL", revoked.errorCode)
        }
        assertEquals(1, publishedCalls)
    }

    @Test
    fun codeModeOnlySampleRejectsLaterOrReplacedToolsBeforeSideEffects() = runTest {
        val registry = ToolRegistry()
        var effects = 0
        registry.register(tool("before") { effects++ })
        CodeModePlugin(registry).install(com.labteto.dshmobile.harness.plugin.HarnessContext(registry))
        val snapshot = registry.captureBindings(JsonArray(listOf(
            requireNotNull(registry.get("code_mode_run")).schema,
        )))
        registry.register(tool("later") { effects++ })
        withContext(snapshot) {
            val late = registry.execute("code_mode_run", codeModePlan("later"),
                context = ToolContext(approval = { true }))
            assertEquals("CODE_MODE_UNSAMPLED_TOOL", late.errorCode)
        }
        registry.register(tool("before") { effects++ }, replace = true)
        withContext(snapshot) {
            val replaced = registry.execute("code_mode_run", codeModePlan("before"),
                context = ToolContext(approval = { true }))
            assertEquals("CODE_MODE_UNSAMPLED_TOOL", replaced.errorCode)
        }
        assertEquals(0, effects)
    }

}

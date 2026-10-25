package com.labteto.dshmobile.harness.tools

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
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
}

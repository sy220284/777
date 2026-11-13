package com.labteto.dshmobile.harness.tools

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.*
import org.junit.Test

class ToolExecutionEnvironmentTest {
    @Test fun approvedWorkspaceToolUsesSelectedEnvironmentAndKeepsRegistryReceipt() = runTest {
        val registry = ToolRegistry()
        var localCalls = 0
        var remoteCalls = 0
        registry.register(HarnessTool("write", functionToolSchema("write", "write"),
            ToolAccess.WORKSPACE_WRITE, ToolApprovalPolicy.ALWAYS, ToolExposure.CORE,
            ToolMetadata("files", environmentBound = true), executor = HarnessToolExecutor { _, _, _ ->
                localCalls++; ToolResult("local")
            }))
        val environment = ToolExecutionEnvironmentContext { tool, context, input, _ ->
            remoteCalls++; assertEquals("write", tool.name); assertTrue(context.allowMutation)
            assertEquals(buildJsonObject {}, input); ToolResult("remote", fileVersions = mapOf("a" to "version"))
        }
        val denied = withContext(environment) {
            registry.executeTracked("write", buildJsonObject {}, context = ToolContext(approval = { false }))
        }
        assertFalse(denied.executionStarted)
        assertEquals(0, remoteCalls)
        val receipt = withContext(environment) {
            registry.executeTracked("write", buildJsonObject {}, context = ToolContext(approval = { true }))
        }
        assertTrue(receipt.executionStarted)
        assertEquals("remote", receipt.result.content)
        assertEquals(mapOf("a" to "version"), receipt.result.fileVersions)
        assertEquals(1, remoteCalls)
        assertEquals(0, localCalls)
    }

    @Test fun deviceToolKeepsItsHostOwnerInsideARemoteEnvironment() = runTest {
        val registry = ToolRegistry()
        registry.register(HarnessTool("device", functionToolSchema("device", "device"),
            ToolAccess.DEVICE, ToolApprovalPolicy.ALWAYS, ToolExposure.CORE, ToolMetadata("device"),
            executor = HarnessToolExecutor { _, _, _ -> ToolResult("host device") }))
        val result = withContext(ToolExecutionEnvironmentContext { _, _, _, _ -> error("device exported") }) {
            registry.execute("device", buildJsonObject {}, context = ToolContext(approval = { true }))
        }
        assertEquals("host device", result.content)
    }
}

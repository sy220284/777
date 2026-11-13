package com.labteto.dshmobile.local.tools

import com.labteto.dshmobile.harness.tools.HarnessTool
import com.labteto.dshmobile.harness.tools.HarnessToolExecutor
import com.labteto.dshmobile.harness.tools.ToolAccess
import com.labteto.dshmobile.harness.tools.ToolApprovalPolicy
import com.labteto.dshmobile.harness.tools.ToolExposure
import com.labteto.dshmobile.harness.tools.ToolMetadata
import com.labteto.dshmobile.harness.tools.ToolResult
import com.labteto.dshmobile.harness.tools.functionToolSchema
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalToolMutationScopeTest {
    private fun tool(access: ToolAccess): HarnessTool = HarnessTool(
        name = "plugin_tool",
        schema = functionToolSchema("plugin_tool", "Extends current workspace"),
        access = access,
        approvalPolicy = ToolApprovalPolicy.ALWAYS,
        exposure = ToolExposure.OPTIONAL,
        metadata = ToolMetadata("plugin", discoveryKeywords = setOf("workspace")),
        executor = HarnessToolExecutor { _, _, _ -> ToolResult("ok") },
    )

    @Test
    fun externalWorkspaceWritersMustShareTheExistingCoordinatorLease() {
        assertTrue(requiresSharedWorkspaceMutationLease(tool(ToolAccess.WORKSPACE_WRITE)))
        assertTrue(requiresSharedWorkspaceMutationLease(tool(ToolAccess.PROCESS)))
        assertFalse(requiresSharedWorkspaceMutationLease(tool(ToolAccess.READ_ONLY)))
        assertFalse(requiresSharedWorkspaceMutationLease(tool(ToolAccess.NETWORK)))
        assertFalse(requiresSharedWorkspaceMutationLease(tool(ToolAccess.AGENT_CONTROL)))
    }
}

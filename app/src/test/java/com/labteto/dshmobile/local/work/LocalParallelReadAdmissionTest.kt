package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.harness.tools.HarnessTool
import com.labteto.dshmobile.harness.tools.HarnessToolExecutor
import com.labteto.dshmobile.harness.tools.ToolAccess
import com.labteto.dshmobile.harness.tools.ToolApprovalPolicy
import com.labteto.dshmobile.harness.tools.ToolExposure
import com.labteto.dshmobile.harness.tools.ToolMetadata
import com.labteto.dshmobile.harness.tools.ToolResult
import com.labteto.dshmobile.harness.tools.functionToolSchema
import com.labteto.dshmobile.local.tools.LocalToolPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalParallelReadAdmissionTest {
    private fun tool(
        name: String,
        access: ToolAccess,
        approval: ToolApprovalPolicy = ToolApprovalPolicy.NEVER,
        safe: Boolean = false,
    ): HarnessTool = HarnessTool(
        name = name,
        schema = functionToolSchema(name, "Test tool"),
        access = access,
        approvalPolicy = approval,
        exposure = ToolExposure.CORE,
        metadata = ToolMetadata(family = "Test", parallelReadSafe = safe),
        executor = HarnessToolExecutor { _, _, _ -> ToolResult("ok") },
    )

    @Test
    fun onlyAuditedNativePureQueriesOptIn() {
        for (name in listOf("read", "file_inspect", "list_files", "glob", "grep", "json_query")) {
            assertTrue(name, LocalToolPolicy.metadata(name).parallelReadSafe)
        }
        for (name in listOf("write", "edit", "bash", "subagent", "http_request",
                "session_event_search", "team_task_list", "web_fetch", "memory_search")) {
            assertFalse(name, LocalToolPolicy.metadata(name).parallelReadSafe)
        }
    }

    @Test
    fun optInRequiresSafeAccessAndNoApproval() {
        assertTrue(isSafeParallelReadTool(tool("read", ToolAccess.READ_ONLY, safe = true)))
        assertFalse(isSafeParallelReadTool(tool("write", ToolAccess.WORKSPACE_WRITE, safe = true)))
        assertFalse(isSafeParallelReadTool(tool("network", ToolAccess.NETWORK, safe = true)))
        assertFalse(isSafeParallelReadTool(tool("read", ToolAccess.READ_ONLY,
            approval = ToolApprovalPolicy.ALWAYS, safe = true)))
        assertFalse(isSafeParallelReadTool(tool("unreviewed", ToolAccess.READ_ONLY)))
        assertFalse(isSafeParallelReadTool(null))
    }
}

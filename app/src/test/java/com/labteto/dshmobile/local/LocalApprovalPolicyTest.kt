package com.labteto.dshmobile.local

import com.labteto.dshmobile.harness.tools.HarnessTool
import com.labteto.dshmobile.harness.tools.HarnessToolExecutor
import com.labteto.dshmobile.harness.tools.ToolAccess
import com.labteto.dshmobile.harness.tools.ToolApprovalPolicy
import com.labteto.dshmobile.harness.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalApprovalPolicyTest {
    @Test
    fun workspaceAutoApprovalOnlyCoversExplicitlySandboxedTools() {
        for (name in listOf("write", "edit", "apply_patch", "download_file")) {
            assertTrue(canAutoApprove(tool(name, LocalToolPolicy.access(name), LocalToolPolicy.approval(name))))
        }

        assertFalse(canAutoApprove(tool("bash", ToolAccess.PROCESS, ToolApprovalPolicy.ALWAYS)))
        assertFalse(canAutoApprove(tool("http_request", ToolAccess.PRIVILEGED, ToolApprovalPolicy.ALWAYS)))
        assertFalse(canAutoApprove(tool("memory_forget", ToolAccess.SESSION_WRITE, ToolApprovalPolicy.ALWAYS)))
        assertFalse(canAutoApprove(tool("plugin_external_write", ToolAccess.WORKSPACE_WRITE, ToolApprovalPolicy.ALWAYS)))
    }

    @Test
    fun deviceTurnLeaseCannotOverrideAlwaysApproval() {
        assertTrue(canUseDeviceApprovalLease(
            tool("android_tap", ToolAccess.DEVICE, ToolApprovalPolicy.MUTATION),
        ))
        assertFalse(canUseDeviceApprovalLease(
            tool("android_privilege_request", ToolAccess.DEVICE, ToolApprovalPolicy.ALWAYS),
        ))
        assertFalse(canUseDeviceApprovalLease(
            tool("android_settings_set", ToolAccess.PRIVILEGED, ToolApprovalPolicy.ALWAYS),
        ))
    }

    private fun tool(
        name: String,
        access: ToolAccess,
        approval: ToolApprovalPolicy,
    ) = HarnessTool(
        name = name,
        schema = JsonObject(emptyMap()),
        access = access,
        approvalPolicy = approval,
        executor = HarnessToolExecutor { _, _, _ -> ToolResult("ok") },
    )
}

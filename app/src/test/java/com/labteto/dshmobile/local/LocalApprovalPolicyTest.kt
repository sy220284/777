package com.labteto.dshmobile.local

import com.labteto.dshmobile.harness.tools.HarnessTool
import com.labteto.dshmobile.harness.tools.HarnessToolExecutor
import com.labteto.dshmobile.harness.tools.ToolAccess
import com.labteto.dshmobile.harness.tools.ToolApprovalPolicy
import com.labteto.dshmobile.harness.tools.ToolExposure
import com.labteto.dshmobile.harness.tools.ToolMetadata
import com.labteto.dshmobile.harness.tools.ToolResult
import com.labteto.dshmobile.harness.tools.functionToolSchema
import com.labteto.dshmobile.local.interaction.LocalApproval
import com.labteto.dshmobile.local.interaction.LocalApprovalImpact
import com.labteto.dshmobile.local.runtime.approvalImpact
import com.labteto.dshmobile.local.runtime.canAutoApproveSafely
import com.labteto.dshmobile.local.runtime.canResolvePendingByEnablingAutoApproval
import com.labteto.dshmobile.local.runtime.canUseDeviceApprovalLease
import com.labteto.dshmobile.local.tools.LocalToolPolicy
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalApprovalPolicyTest {
    @Test
    fun lowRiskClassificationCoversConfinedWorkspaceWritesAndReadOnlyTools() {
        for (name in listOf("write", "edit", "apply_patch", "download_file")) {
            assertTrue(canAutoApproveSafely(tool(name, LocalToolPolicy.access(name), LocalToolPolicy.approval(name))))
        }
        assertTrue(canAutoApproveSafely(tool("session_trace", ToolAccess.READ_ONLY, ToolApprovalPolicy.ALWAYS)))
        assertTrue(canAutoApproveSafely(tool("plugin_external_read", ToolAccess.READ_ONLY, ToolApprovalPolicy.ALWAYS)))

        // Shell shares the app UID and can leave cwd, so it stays outside the low-risk classification.
        assertFalse(canAutoApproveSafely(tool("bash", ToolAccess.PROCESS, ToolApprovalPolicy.ALWAYS)))

        // Categories acting outside the filesystem sandbox remain high-risk classifications.
        assertFalse(canAutoApproveSafely(tool("http_request", ToolAccess.PRIVILEGED, ToolApprovalPolicy.ALWAYS)))
        assertFalse(canAutoApproveSafely(tool("memory_forget", ToolAccess.SESSION_WRITE, ToolApprovalPolicy.ALWAYS)))
        assertFalse(canAutoApproveSafely(tool("plugin_external_write", ToolAccess.WORKSPACE_WRITE, ToolApprovalPolicy.ALWAYS)))
    }

    @Test
    fun enablingAutoApprovalResolvesAnyCurrentApproval() {
        val lowRisk = LocalApproval(
            callId = "safe",
            toolName = "read",
            summary = "read",
            arguments = "{}",
            access = "read_only",
            canAutoApproveSafely = true,
        )
        val highRisk = LocalApproval(
            callId = "risk",
            toolName = "github_api_request",
            summary = "github write",
            arguments = "{}",
            access = "privileged",
            canAutoApproveSafely = false,
        )

        assertTrue(canResolvePendingByEnablingAutoApproval(lowRisk))
        assertTrue(canResolvePendingByEnablingAutoApproval(highRisk))
        assertFalse(canResolvePendingByEnablingAutoApproval(null))
    }

    @Test
    fun deviceTurnLeaseCannotOverrideAlwaysApproval() {
        assertTrue(canUseDeviceApprovalLease(
            tool("android_tap", ToolAccess.DEVICE, ToolApprovalPolicy.MUTATION),
        ))
        assertFalse(canUseDeviceApprovalLease(
            tool("android_screen", ToolAccess.READ_ONLY, ToolApprovalPolicy.ALWAYS),
        ))
        assertFalse(canUseDeviceApprovalLease(
            tool("android_notification_list", ToolAccess.READ_ONLY, ToolApprovalPolicy.ALWAYS),
        ))
    }

    @Test
    fun sessionEventReadsAreEligibleForPersistentSafeApproval() {
        for (name in listOf("session_event_search", "session_trace", "session_event_trace", "session_event_read")) {
            val definition = tool(name, LocalToolPolicy.access(name), LocalToolPolicy.approval(name))
            org.junit.Assert.assertEquals(ToolApprovalPolicy.ALWAYS, definition.approvalPolicy)
            assertTrue(canAutoApproveSafely(definition))
        }
    }

    @Test
    fun impactLevelsFollowExternalEffect() {
        assertTrue(approvalImpact(tool("read", ToolAccess.READ_ONLY, ToolApprovalPolicy.ALWAYS)) == LocalApprovalImpact.LOW)
        assertTrue(approvalImpact(tool("write", ToolAccess.WORKSPACE_WRITE, ToolApprovalPolicy.ALWAYS)) == LocalApprovalImpact.LOW)
        assertTrue(approvalImpact(tool("plugin_external_write", ToolAccess.WORKSPACE_WRITE, ToolApprovalPolicy.ALWAYS)) == LocalApprovalImpact.MEDIUM)
        assertTrue(approvalImpact(tool("memory_update", ToolAccess.SESSION_WRITE, ToolApprovalPolicy.ALWAYS)) == LocalApprovalImpact.MEDIUM)
        assertTrue(approvalImpact(tool("bash", ToolAccess.PROCESS, ToolApprovalPolicy.ALWAYS)) == LocalApprovalImpact.HIGH)
        assertTrue(approvalImpact(tool("http_request", ToolAccess.PRIVILEGED, ToolApprovalPolicy.ALWAYS)) == LocalApprovalImpact.CRITICAL)
    }

    private fun tool(
        name: String,
        access: ToolAccess,
        approval: ToolApprovalPolicy,
    ) = HarnessTool(
        name = name,
        schema = functionToolSchema(name, "测试工具"),
        access = access,
        approvalPolicy = approval,
        exposure = ToolExposure.CORE,
        metadata = ToolMetadata("测试"),
        executor = HarnessToolExecutor { _, _, _ -> ToolResult("ok") },
    )
}

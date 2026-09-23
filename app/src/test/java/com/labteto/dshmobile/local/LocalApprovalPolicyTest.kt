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
    fun safeAutoApprovalCoversConfinedWorkspaceWritesAndReadOnlyTools() {
        for (name in listOf("write", "edit", "apply_patch", "download_file")) {
            assertTrue(canAutoApprove(tool(name, LocalToolPolicy.access(name), LocalToolPolicy.approval(name))))
        }
        assertTrue(canAutoApprove(tool("session_trace", ToolAccess.READ_ONLY, ToolApprovalPolicy.ALWAYS)))
        assertTrue(canAutoApprove(tool("plugin_external_read", ToolAccess.READ_ONLY, ToolApprovalPolicy.ALWAYS)))

        // Shell execution is approved by the sandbox boundary: what a command can reach is bounded by
        // the kernel, not by the prompt. The per-command policy still withholds firmware-targeting and
        // history-rewriting commands, which the parameter-aware overload applies.
        assertTrue(canAutoApprove(tool("bash", ToolAccess.PROCESS, ToolApprovalPolicy.ALWAYS)))

        // Categories that act outside the filesystem sandbox stay explicit.
        assertFalse(canAutoApprove(tool("http_request", ToolAccess.PRIVILEGED, ToolApprovalPolicy.ALWAYS)))
        assertFalse(canAutoApprove(tool("memory_forget", ToolAccess.SESSION_WRITE, ToolApprovalPolicy.ALWAYS)))
        assertFalse(canAutoApprove(tool("plugin_external_write", ToolAccess.WORKSPACE_WRITE, ToolApprovalPolicy.ALWAYS)))
    }

    @Test
    fun enablingSafeModeOnlyResolvesACurrentlySafeApproval() {
        val safe = LocalApproval(
            callId = "safe",
            toolName = "read",
            summary = "read",
            arguments = "{}",
            access = "read_only",
            canAutoApproveSafely = true,
        )
        val highRisk = LocalApproval(
            callId = "risk",
            toolName = "bash",
            summary = "bash",
            arguments = "{}",
            access = "process",
            canAutoApproveSafely = false,
        )

        assertTrue(canResolvePendingByEnablingSafeAutoApproval(safe))
        assertFalse(canResolvePendingByEnablingSafeAutoApproval(highRisk))
        assertFalse(canResolvePendingByEnablingSafeAutoApproval(null))
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

    @Test
    fun sessionEventReadsAreEligibleForPersistentSafeApproval() {
        for (name in listOf("session_event_search", "session_trace", "session_event_trace", "session_event_read")) {
            val definition = tool(name, LocalToolPolicy.access(name), LocalToolPolicy.approval(name))
            org.junit.Assert.assertEquals(ToolApprovalPolicy.ALWAYS, definition.approvalPolicy)
            assertTrue(canAutoApprove(definition))
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
        schema = JsonObject(emptyMap()),
        access = access,
        approvalPolicy = approval,
        executor = HarnessToolExecutor { _, _, _ -> ToolResult("ok") },
    )
}

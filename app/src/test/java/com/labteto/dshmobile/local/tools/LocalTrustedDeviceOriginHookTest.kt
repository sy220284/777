package com.labteto.dshmobile.local.tools

import com.labteto.dshmobile.harness.tools.HarnessTool
import com.labteto.dshmobile.harness.tools.HarnessToolExecutor
import com.labteto.dshmobile.harness.tools.ToolAccess
import com.labteto.dshmobile.harness.tools.ToolApprovalPolicy
import com.labteto.dshmobile.harness.tools.ToolExposure
import com.labteto.dshmobile.harness.tools.ToolMetadata
import com.labteto.dshmobile.harness.tools.ToolPreUseDecision
import com.labteto.dshmobile.harness.tools.ToolPreUseRequest
import com.labteto.dshmobile.harness.tools.ToolResult
import com.labteto.dshmobile.harness.tools.functionToolSchema
import com.labteto.dshmobile.local.security.LocalPolicyLayer
import com.labteto.dshmobile.local.security.mergeLocalExecutionPolicy
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalTrustedDeviceOriginHookTest {
    private val tool = HarnessTool(
        name = "android_open_uri",
        schema = functionToolSchema("android_open_uri", "测试"),
        access = ToolAccess.NETWORK,
        approvalPolicy = ToolApprovalPolicy.NEVER,
        exposure = ToolExposure.CORE,
        metadata = ToolMetadata("device"),
        executor = HarnessToolExecutor { _, _, _ -> ToolResult("open") },
    )

    private suspend fun decision(
        address: String,
        deniedHosts: Set<String> = emptySet(),
    ): ToolPreUseDecision =
        LocalTrustedDeviceOriginHook {
            mergeLocalExecutionPolicy(managed = LocalPolicyLayer(deniedHosts = deniedHosts))
        }.beforeUse(ToolPreUseRequest(
            sessionId = "session",
            tool = tool,
            input = buildJsonObject { put("uri", address) },
        ))

    @Test fun blockedAndCredentialedHostsAreRejectedBeforeApproval() = runTest {
        assertTrue(decision("https://blocked.example/a", setOf("blocked.example")) is ToolPreUseDecision.Deny)
        assertTrue(decision("https://user:secret@ok.example/a") is ToolPreUseDecision.Deny)
        assertTrue(decision("https://") is ToolPreUseDecision.Deny)
    }

    @Test fun permittedOriginsAndOtherUriSchemesKeepTheirDevicePermissionPath() = runTest {
        assertEquals(ToolPreUseDecision.Allow, decision("https://ok.example/a"))
        assertEquals(ToolPreUseDecision.Allow, decision("content://authority/document/1"))
    }
}

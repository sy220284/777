package com.labteto.dshmobile.reference

import com.labteto.dshmobile.harness.session.SessionRecovery
import com.labteto.dshmobile.harness.tools.HarnessTool
import com.labteto.dshmobile.harness.tools.HarnessToolExecutor
import com.labteto.dshmobile.harness.tools.ToolAccess
import com.labteto.dshmobile.harness.tools.ToolApprovalPolicy
import com.labteto.dshmobile.harness.tools.ToolExposure
import com.labteto.dshmobile.harness.tools.ToolMetadata
import com.labteto.dshmobile.harness.tools.ToolContext
import com.labteto.dshmobile.harness.tools.ToolRegistry
import com.labteto.dshmobile.harness.tools.ToolResult
import com.labteto.dshmobile.harness.tools.functionToolSchema
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Local Android/runtime behavior contracts that have no pinned official golden fixture.
 *
 * These tests intentionally stay separate from [ReferenceConformanceTest]: they extend coverage
 * without claiming that locally-authored expectations came from the locked upstream harness.
 */
class NativeRuntimeBehaviorContractTest {
    @Test
    fun interruptedToolRecoveryDistinguishesStartedFromNotStarted() {
        val notStarted = SessionRecovery.interruptedToolResult(
            callId = "pending",
            name = "write_file",
            step = 1,
            started = false,
        )
        val started = SessionRecovery.interruptedToolResult(
            callId = "started",
            name = "write_file",
            step = 1,
            started = true,
        )

        assertEquals(SessionRecovery.TOOL_NOT_STARTED, notStarted.code)
        assertTrue(notStarted.modelContent.contains("\"retryable\":true"))
        assertEquals(SessionRecovery.TOOL_OUTCOME_UNKNOWN, started.code)
        assertTrue(started.modelContent.contains("\"side_effect\":\"possible\""))
        assertTrue(started.modelContent.contains("\"retryable\":false"))
    }

    @Test
    fun deniedApprovalNeverEntersToolExecutor() = runTest {
        var executed = false
        val registry = ToolRegistry().apply {
            register(
                HarnessTool(
                    name = "dangerous_write",
                    schema = functionToolSchema("dangerous_write", "危险写入测试"),
                    access = ToolAccess.WORKSPACE_WRITE,
                    approvalPolicy = ToolApprovalPolicy.ALWAYS,
                    exposure = ToolExposure.CORE,
                    metadata = ToolMetadata("测试"),
                    executor = HarnessToolExecutor { _, _, _ ->
                        executed = true
                        ToolResult("written")
                    },
                ),
            )
        }

        val invocation = registry.executeTracked(
            name = "dangerous_write",
            input = buildJsonObject { },
            context = ToolContext(approval = { false }),
        )

        assertEquals("APPROVAL_DENIED", invocation.result.errorCode)
        assertFalse(invocation.executionStarted)
        assertFalse(executed)
    }
}

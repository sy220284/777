package com.labteto.dshmobile.harness.tools

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A granted approval belongs to one advertised handler contract, not to a string name. */
class ToolRegistrationAdmissionTest {
    private fun registered(name: String, result: () -> ToolResult): HarnessTool = HarnessTool(
        name = name,
        schema = functionToolSchema(name, "Registration snapshot test"),
        access = ToolAccess.WORKSPACE_WRITE,
        approvalPolicy = ToolApprovalPolicy.ALWAYS,
        exposure = ToolExposure.CORE,
        metadata = ToolMetadata("Test"),
        executor = HarnessToolExecutor { _, _, _ -> result() },
    )

    @Test
    fun toolUninstalledWhileAwaitingApprovalMustNotExecuteOldHandler() = runTest {
        val registry = ToolRegistry()
        var oldExecuted = 0
        registry.register(registered("write_asset") {
            oldExecuted++
            ToolResult("old handler ran")
        })

        val observed = registry.executeTracked(
            "write_asset",
            buildJsonObject {},
            context = ToolContext(approval = {
                registry.unregister("write_asset")
                true
            }),
        )

        assertEquals("TOOL_REGISTRATION_CHANGED", observed.result.errorCode)
        assertFalse(observed.executionStarted)
        assertFalse(observed.result.retryable)
        assertEquals(0, oldExecuted)
    }

    @Test
    fun replacementDuringApprovalCannotBorrowTheOldApproval() = runTest {
        val registry = ToolRegistry()
        var oldExecuted = 0
        var replacementExecuted = 0
        registry.register(registered("write_asset") {
            oldExecuted++
            ToolResult("old")
        })
        val observed = registry.executeTracked(
            "write_asset",
            buildJsonObject {},
            context = ToolContext(approval = {
                registry.register(registered("write_asset") {
                    replacementExecuted++
                    ToolResult("replacement")
                }, replace = true)
                true
            }),
        )
        assertEquals("TOOL_REGISTRATION_CHANGED", observed.result.errorCode)
        assertFalse(observed.executionStarted)
        assertEquals(0, oldExecuted)
        assertEquals(0, replacementExecuted)

        // The replacement is still usable, but it requires a new approval.
        val current = registry.executeTracked(
            "write_asset", buildJsonObject {},
            context = ToolContext(approval = { true }),
        )
        assertTrue(current.executionStarted)
        assertFalse(current.result.isError)
        assertEquals(1, replacementExecuted)
    }

    @Test
    fun expectedRegistrationBindsCallerPermissionSnapshot() = runTest {
        val registry = ToolRegistry()
        registry.register(registered("write_asset") { ToolResult("old") })
        val original = requireNotNull(registry.get("write_asset"))
        registry.register(registered("write_asset") { ToolResult("replacement") }, replace = true)
        var approvals = 0
        val rejected = registry.executeTracked(
            "write_asset", buildJsonObject {},
            context = ToolContext(
                expectedRegistration = original,
                approval = { approvals++; true },
            ),
        )
        assertEquals("TOOL_REGISTRATION_CHANGED", rejected.result.errorCode)
        assertEquals(0, approvals)
        assertFalse(rejected.executionStarted)
    }
}

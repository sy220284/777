package com.labteto.dshmobile.local.tools

import com.labteto.dshmobile.harness.tools.HarnessTool
import com.labteto.dshmobile.harness.tools.HarnessToolExecutor
import com.labteto.dshmobile.harness.tools.ToolAccess
import com.labteto.dshmobile.harness.tools.ToolApprovalPolicy
import com.labteto.dshmobile.harness.tools.ToolExposure
import com.labteto.dshmobile.harness.tools.ToolMetadata
import com.labteto.dshmobile.harness.tools.ToolResult
import com.labteto.dshmobile.harness.tools.functionToolSchema
import com.labteto.dshmobile.local.agent.LocalSubagentCapabilities
import com.labteto.dshmobile.local.model.LocalToolCall
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalDirectToolDispatchTest {
    private fun registered(name: String, exposure: ToolExposure = ToolExposure.CORE) =
        HarnessTool(
            name = name, schema = functionToolSchema(name, "test"),
            access = ToolAccess.READ_ONLY, approvalPolicy = ToolApprovalPolicy.NEVER,
            exposure = exposure, metadata = ToolMetadata(
                "test", discoveryKeywords = if (exposure == ToolExposure.OPTIONAL) setOf(name) else emptySet()),
            executor = HarnessToolExecutor { _, _, _ -> ToolResult("ok") },
        )

    @Test fun directCallKeepsActualArgumentsAndDisallowsNestedGateway() {
        val child = buildJsonObject { put("path", "README.md") }
        val args = buildJsonObject {
            put("name", "read")
            put("arguments", child)
        }
        val call = LocalToolCall("same-call", DIRECT_TOOL_CALL, args, args.toString())
        val resolved = call.directToolTarget()
        assertNotNull(resolved)
        assertEquals("read", resolved!!.name)
        assertEquals(child, resolved.arguments)
        assertEquals(child.toString(), resolved.rawArguments)
        val recursive = buildJsonObject {
            put("name", DIRECT_TOOL_CALL)
            put("arguments", buildJsonObject {})
        }
        assertNull(LocalToolCall("bad", DIRECT_TOOL_CALL, recursive, recursive.toString())
            .directToolTarget())
    }

    @Test fun ordinarySubagentCannotReachLeadOrAutomationControl() {
        val scope = LocalSubagentExecutionScope(LocalSubagentCapabilities(), null)
        for (name in listOf(
            "team_create_member", "team_send_message", "team_task_update",
            "schedule_recurring_task", "mcp_http_connect", "workflow",
            "subagent", "memory_forget", "update_goal",
        )) {
            assertFalse(name, scope.canDiscover(registered(name)))
        }
        assertTrue(scope.canDiscover(registered("read")))
    }

    @Test fun teamChildCanUseOnlyItsMemberCommands() {
        val scope = LocalSubagentExecutionScope(LocalSubagentCapabilities(teamManaged = true), null)
        assertTrue(scope.canDiscover(registered("team_send_message", ToolExposure.OPTIONAL))
            .not()) // See optional grant admission below.
        assertTrue(scope.canDiscover(registered("team_send_message")))
        assertTrue(scope.canDiscover(registered("team_task_update")))
        assertFalse(scope.canDiscover(registered("team_create_member")))
        assertFalse(scope.canDiscover(registered("team_spawn")))
    }

    @Test fun optionalTeamExtensionNeedsExplicitGrant() {
        val denied = LocalSubagentExecutionScope(
            LocalSubagentCapabilities(teamManaged = true), null)
        val granted = LocalSubagentExecutionScope(LocalSubagentCapabilities(
            teamManaged = true, initialOptionalTools = setOf("mcp_billing_lookup")), null)
        val plugin = registered("mcp_billing_lookup", ToolExposure.OPTIONAL)
        assertFalse(denied.canDiscover(plugin))
        assertTrue(granted.canDiscover(plugin))
    }

    @Test fun nestedDelegationRequiresRunnerAdmission() {
        val capabilities = LocalSubagentCapabilities(
            maxDepth = 2, depth = 1, allowMutation = false, continuable = false)
        assertFalse(LocalSubagentExecutionScope(capabilities, null)
            .canDiscover(registered("subagent")))
        assertTrue(LocalSubagentExecutionScope(capabilities, null, canDelegateNested = true)
            .canDiscover(registered("subagent")))
        assertFalse(LocalSubagentExecutionScope(capabilities, null, canDelegateNested = true)
            .canDiscover(registered("http_request")))
    }

    @Test fun virtualScreenMustMatchItsActualLease() {
        val scope = LocalSubagentExecutionScope(
            LocalSubagentCapabilities(virtualScreen = true), "virtual-1")
        val action = registered("android_vscreen_tap")
        assertFalse(scope.permits(action, buildJsonObject { put("id", "virtual-2") }))
        assertTrue(scope.permits(action, buildJsonObject { put("id", "virtual-1") }))
    }
}

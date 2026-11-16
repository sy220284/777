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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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


    @Test fun genericGatewayRestoresOnlySchemaDeclaredNumericFields() {
        val contract = Json.parseToJsonElement("""
            {"function":{"parameters":{"type":"object","properties":{
                "timeout_seconds":{"type":"integer"},
                "depth":{"type":"integer"},
                "max_bytes":{"type":"integer"},
                "offset":{"type":"integer"},
                "a":{"type":"number"},
                "b":{"type":"number"},
                "opaque":{"type":"string"},
                "nested":{"type":"object","properties":{"count":{"type":"integer"}}},
                "items":{"type":"array","items":{"type":"object","properties":{"n":{"type":"integer"}}}},
                "invalid":{"type":"integer"},
                "nonfinite":{"type":"number"}
            }}}}
        """.trimIndent()).jsonObject
        val input = Json.parseToJsonElement("""
            {"timeout_seconds":"30","depth":"4","max_bytes":"1024","offset":"0",
             "a":"2.5","b":"-1.25","opaque":"001",
             "nested":{"count":"42"},"items":[{"n":"7"}],
             "invalid":"01","nonfinite":"NaN"}
        """.trimIndent()).jsonObject
        val gateway = buildJsonObject {
            put("name", "mcp_selfcheck-mcp_add_pair")
            put("arguments", input)
        }
        val call = LocalToolCall("numeric", DIRECT_TOOL_CALL, gateway, gateway.toString())
            .directToolTarget()!!.withSchemaTypedNumbers(contract)
        val expected = Json.parseToJsonElement("""
            {"timeout_seconds":30,"depth":4,"max_bytes":1024,"offset":0,
             "a":2.5,"b":-1.25,"opaque":"001",
             "nested":{"count":42},"items":[{"n":7}],
             "invalid":"01","nonfinite":"NaN"}
        """.trimIndent()).jsonObject
        assertEquals(expected, call.arguments)
        assertEquals(expected.toString(), call.rawArguments)
        assertFalse(call.arguments.getValue("offset").jsonPrimitive.isString)
        assertTrue(call.arguments.getValue("opaque").jsonPrimitive.isString)
    }

    @Test fun gatewayLeavesAlreadyTypedNumbersAndUntypedFieldsUntouched() {
        val contract = Json.parseToJsonElement("""
            {"function":{"parameters":{"type":"object","properties":{
                "offset":{"type":"integer"},"raw":{"type":"object","additionalProperties":true}
            }}}}
        """.trimIndent()).jsonObject
        val args = Json.parseToJsonElement(
            """{"offset":0,"raw":{"not_declared_as_number":"123"}}"""
        ).jsonObject
        val existing = LocalToolCall("typed", "write_chunk", args, args.toString())
        val result = existing.withSchemaTypedNumbers(contract)
        assertTrue(existing === result)
        assertTrue(result.arguments.getValue("raw").jsonObject
            .getValue("not_declared_as_number").jsonPrimitive.isString)
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
        assertTrue(scope.canDiscover(registered("team_send_message", ToolExposure.OPTIONAL)))
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

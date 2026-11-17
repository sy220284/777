package com.labteto.dshmobile.local.tools

import com.labteto.dshmobile.harness.tools.HarnessTool
import com.labteto.dshmobile.harness.tools.HarnessToolExecutor
import com.labteto.dshmobile.harness.tools.ToolAccess
import com.labteto.dshmobile.harness.tools.ToolApprovalPolicy
import com.labteto.dshmobile.harness.tools.ToolExposure
import com.labteto.dshmobile.harness.tools.ToolMetadata
import com.labteto.dshmobile.harness.tools.ToolRegistry
import com.labteto.dshmobile.harness.tools.ToolResult
import com.labteto.dshmobile.harness.tools.functionToolSchema
import com.labteto.dshmobile.uicontract.UiToolDraftValidation
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalToolCapabilityDraftValidatorTest {
    private val arguments = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("mode", buildJsonObject {
                put("type", "string")
                put("enum", buildJsonArray { add(JsonPrimitive("inspect")); add(JsonPrimitive("update")) })
            })
            put("items", buildJsonObject {
                put("type", "array")
                put("items", buildJsonObject { put("type", "integer") })
                put("maxItems", 2)
            })
        })
        put("required", buildJsonArray { add(JsonPrimitive("mode")) })
        put("additionalProperties", false)
    }

    private fun registered(calls: AtomicInteger): ToolRegistry = ToolRegistry().apply {
        register(HarnessTool(
            name = "test_catalog", schema = functionToolSchema("test_catalog", "受限校验", parameterSchema = arguments),
            access = ToolAccess.PRIVILEGED,
            approvalPolicy = ToolApprovalPolicy.ALWAYS,
            exposure = ToolExposure.CORE,
            metadata = ToolMetadata(family = "TEST"),
            executor = HarnessToolExecutor { _, _, _ ->
                calls.incrementAndGet()
                ToolResult("executed")
            },
        ))
    }

    @Test fun currentSchemaValidatesJsonButNeverCreatesAnExecution() {
        val calls = AtomicInteger()
        val registry = registered(calls)
        assertEquals(UiToolDraftValidation.Valid,
            validateCapabilityDraftAgainstRegistry(registry, "test_catalog", arguments,
                """{"mode":"inspect","items":[1,2]}"""))
        assertTrue(validateCapabilityDraftAgainstRegistry(registry, "test_catalog", arguments,
            """{"items":[1]}""") is UiToolDraftValidation.Rejected)
        assertTrue(validateCapabilityDraftAgainstRegistry(registry, "test_catalog", arguments,
            """{"mode":"unknown"}""") is UiToolDraftValidation.Rejected)
        assertTrue(validateCapabilityDraftAgainstRegistry(registry, "test_catalog", arguments,
            """{"mode":"update","items":[1,2,3]}""") is UiToolDraftValidation.Rejected)
        assertTrue(validateCapabilityDraftAgainstRegistry(registry, "test_catalog", arguments,
            """{"mode":"inspect","extra":true}""") is UiToolDraftValidation.Rejected)
        assertTrue(validateCapabilityDraftAgainstRegistry(registry, "test_catalog", arguments,
            """{"mode":"inspect",}""") is UiToolDraftValidation.Rejected)
        assertEquals(0, calls.get())
    }

    @Test fun workDraftUsesFullStandardSchemaForBothNativeAndRemoteTools() {
        val schema = Json.parseToJsonElement("""{
          "type":"object","properties":{"option":{"anyOf":[{"type":"string","minLength":2},{"type":"null"}]}},
          "required":["option"],"additionalProperties":false
        }""").jsonObject
        val registry = ToolRegistry()
        for (name in listOf("native_full", "remote_full")) {
            registry.register(HarnessTool(
                name = name, schema = functionToolSchema(name, "统一 Schema 测试", parameterSchema = schema),
                access = ToolAccess.READ_ONLY, approvalPolicy = ToolApprovalPolicy.NEVER,
                exposure = ToolExposure.CORE, metadata = ToolMetadata(family = "TEST"),
                executor = HarnessToolExecutor { _, _, _ -> ToolResult("unexpected") },
            ))
            assertEquals(UiToolDraftValidation.Valid,
                validateCapabilityDraftAgainstRegistry(registry, name, schema, """{"option":null}"""))
            assertEquals(UiToolDraftValidation.Valid,
                validateCapabilityDraftAgainstRegistry(registry, name, schema, """{"option":"ok"}"""))
            assertTrue(validateCapabilityDraftAgainstRegistry(registry, name, schema,
                """{"option":"x"}""") is UiToolDraftValidation.Rejected)
            assertTrue(validateCapabilityDraftAgainstRegistry(registry, name, schema,
                """{"option":2}""") is UiToolDraftValidation.Rejected)
        }
    }

    @Test fun revokedOrUpdatedRegistrationNeverAcceptsPreviousSchema() {
        val calls = AtomicInteger()
        val registry = registered(calls)
        val different = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject { put("message", buildJsonObject { put("type", "string") }) })
            put("additionalProperties", false)
        }
        assertTrue(validateCapabilityDraftAgainstRegistry(registry, "test_catalog", different,
            """{}""") is UiToolDraftValidation.Rejected)
        registry.unregister("test_catalog")
        assertTrue(validateCapabilityDraftAgainstRegistry(registry, "test_catalog", arguments,
            """{"mode":"inspect"}""") is UiToolDraftValidation.Rejected)
        assertEquals(0, calls.get())
    }
}

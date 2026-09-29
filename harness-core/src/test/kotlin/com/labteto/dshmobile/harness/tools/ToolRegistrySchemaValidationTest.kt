package com.labteto.dshmobile.harness.tools

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolRegistrySchemaValidationTest {
    private fun registry(vararg required: String): Pair<ToolRegistry, MutableList<String>> {
        val calls = mutableListOf<String>()
        val registry = ToolRegistry()
        registry.register(
            HarnessTool(
                name = "strict_tool",
                schema = buildJsonObject {
                    put("type", "function")
                    put("function", buildJsonObject {
                        put("name", "strict_tool")
                        put("parameters", buildJsonObject {
                            put("type", "object")
                            put("properties", buildJsonObject {
                                put("count", buildJsonObject { put("type", "integer") })
                                put("ratio", buildJsonObject { put("type", "number") })
                                put("enabled", buildJsonObject { put("type", "boolean") })
                                put("mode", buildJsonObject {
                                    put("type", "string")
                                    put("enum", buildJsonArray {
                                        add(JsonPrimitive("a"))
                                        add(JsonPrimitive("b"))
                                    })
                                })
                                put("meta", buildJsonObject {
                                    put("type", "object")
                                    put("additionalProperties", buildJsonObject {
                                        put("type", "string")
                                    })
                                })
                            })
                            put("required", buildJsonArray {
                                required.forEach { add(JsonPrimitive(it)) }
                            })
                            put("additionalProperties", false)
                        })
                    })
                },
                executor = HarnessToolExecutor { _, input, _ ->
                    calls += input.toString()
                    ToolResult("ok")
                },
            ),
        )
        return registry to calls
    }

    @Test
    fun rejectsWrongTypesMissingRequiredUnknownFieldsAndBadEnumsBeforeExecution() = runTest {
        val (registry, calls) = registry("count")

        val wrongType = registry.execute("strict_tool", buildJsonObject { put("count", "3") })
        val missing = registry.execute("strict_tool", buildJsonObject { put("enabled", true) })
        val unknown = registry.execute("strict_tool", buildJsonObject {
            put("count", 3)
            put("hidden", "value")
        })
        val badEnum = registry.execute("strict_tool", buildJsonObject {
            put("count", 3)
            put("mode", "c")
        })

        assertTrue(wrongType.isError)
        assertTrue(missing.isError)
        assertTrue(unknown.isError)
        assertTrue(badEnum.isError)
        assertTrue(calls.isEmpty())
    }

    @Test
    fun validatesNestedAdditionalPropertyTypesButAllowsDeclaredDynamicKeys() = runTest {
        val (registry, calls) = registry()

        val bad = registry.execute("strict_tool", buildJsonObject {
            put("meta", buildJsonObject { put("header", 123) })
        })
        val good = registry.execute("strict_tool", buildJsonObject {
            put("count", 3)
            put("ratio", 1.5)
            put("enabled", false)
            put("mode", "b")
            put("meta", buildJsonObject { put("header", "ok") })
        })

        assertTrue(bad.isError)
        assertFalse(good.isError)
        assertEquals("ok", good.content)
        assertEquals(1, calls.size)
    }

    @Test
    fun validationHappensBeforeApprovalSoInvalidCallsDoNotPromptUser() = runTest {
        var approvals = 0
        val registry = ToolRegistry()
        registry.register(
            HarnessTool(
                name = "approved",
                schema = buildJsonObject {
                    put("type", "function")
                    put("function", buildJsonObject {
                        put("name", "approved")
                        put("parameters", buildJsonObject {
                            put("type", "object")
                            put("properties", buildJsonObject {
                                put("value", buildJsonObject { put("type", "integer") })
                            })
                            put("required", buildJsonArray { add(JsonPrimitive("value")) })
                            put("additionalProperties", false)
                        })
                    })
                },
                access = ToolAccess.WORKSPACE_WRITE,
                approvalPolicy = ToolApprovalPolicy.ALWAYS,
                executor = HarnessToolExecutor { _, _, _ -> ToolResult("ran") },
            ),
        )

        val result = registry.execute(
            "approved",
            buildJsonObject { put("value", "not-an-int") },
            context = ToolContext(approval = {
                approvals += 1
                true
            }),
        )

        assertTrue(result.isError)
        assertEquals(0, approvals)
    }
}

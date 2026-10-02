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
                        put("description", "严格测试工具")
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
                                access = ToolAccess.READ_ONLY,
                approvalPolicy = ToolApprovalPolicy.NEVER,
                exposure = ToolExposure.CORE,
                metadata = ToolMetadata("测试"),
executor = HarnessToolExecutor { _, input, _ ->
                    calls += input.toString()
                    ToolResult("ok")
                },
            ),
        )
        return registry to calls
    }

    @Test
    fun registrationRejectsMismatchedSchemaNameAndUndiscoverableOptionalTool() {
        val mismatched = runCatching {
            ToolRegistry().register(
                HarnessTool(
                    name = "declared_name",
                    schema = functionToolSchema("other_name", "测试工具"),
                    access = ToolAccess.READ_ONLY,
                    approvalPolicy = ToolApprovalPolicy.NEVER,
                    exposure = ToolExposure.CORE,
                    metadata = ToolMetadata("测试"),
                    executor = HarnessToolExecutor { _, _, _ -> ToolResult("ok") },
                ),
            )
        }
        assertTrue(mismatched.isFailure)

        val undiscoverable = runCatching {
            ToolRegistry().register(
                HarnessTool(
                    name = "optional_tool",
                    schema = functionToolSchema("optional_tool", "测试工具"),
                    access = ToolAccess.READ_ONLY,
                    approvalPolicy = ToolApprovalPolicy.NEVER,
                    exposure = ToolExposure.OPTIONAL,
                    metadata = ToolMetadata("测试"),
                    executor = HarnessToolExecutor { _, _, _ -> ToolResult("ok") },
                ),
            )
        }
        assertTrue(undiscoverable.isFailure)
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
    fun trackedExecutionReportsWhetherExecutorWasEntered() = runTest {
        val (registry, calls) = registry("count")

        val rejected = registry.executeTracked(
            "strict_tool",
            buildJsonObject { put("count", "3") },
        )
        val executed = registry.executeTracked(
            "strict_tool",
            buildJsonObject { put("count", 3) },
        )

        assertTrue(rejected.result.isError)
        assertFalse(rejected.executionStarted)
        assertFalse(executed.result.isError)
        assertTrue(executed.executionStarted)
        assertEquals(1, calls.size)
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
                        put("description", "审批测试工具")
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
                                exposure = ToolExposure.CORE,
                metadata = ToolMetadata("测试"),
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

    @Test
    fun enforcesArrayAndNumericBoundsDeclaredByTools() = runTest {
        val calls = mutableListOf<String>()
        val registry = ToolRegistry()
        registry.register(
            HarnessTool(
                name = "bounded",
                schema = buildJsonObject {
                    put("type", "function")
                    put("function", buildJsonObject {
                        put("name", "bounded")
                        put("description", "边界测试工具")
                        put("parameters", buildJsonObject {
                            put("type", "object")
                            put("properties", buildJsonObject {
                                put("args", buildJsonObject {
                                    put("type", "array")
                                    put("minItems", 1)
                                    put("maxItems", 2)
                                    put("items", buildJsonObject { put("type", "string") })
                                })
                                put("timeout", buildJsonObject {
                                    put("type", "integer")
                                    put("minimum", 100)
                                    put("maximum", 120_000)
                                })
                            })
                            put("additionalProperties", false)
                        })
                    })
                },
                                access = ToolAccess.READ_ONLY,
                approvalPolicy = ToolApprovalPolicy.NEVER,
                exposure = ToolExposure.CORE,
                metadata = ToolMetadata("测试"),
executor = HarnessToolExecutor { _, input, _ ->
                    calls += input.toString()
                    ToolResult("ok")
                },
            ),
        )

        assertTrue(registry.execute("bounded", buildJsonObject {
            put("args", buildJsonArray { })
        }).isError)
        assertTrue(registry.execute("bounded", buildJsonObject {
            put("args", buildJsonArray {
                add(JsonPrimitive("a"))
                add(JsonPrimitive("b"))
                add(JsonPrimitive("c"))
            })
        }).isError)
        assertTrue(registry.execute("bounded", buildJsonObject { put("timeout", 99) }).isError)
        assertTrue(registry.execute("bounded", buildJsonObject { put("timeout", 120_001) }).isError)
        assertTrue(calls.isEmpty())

        val good = registry.execute("bounded", buildJsonObject {
            put("args", buildJsonArray { add(JsonPrimitive("a")) })
            put("timeout", 100)
        })
        assertFalse(good.isError)
        assertEquals(1, calls.size)
    }

}

package com.labteto.dshmobile.interop.mcp

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class McpElicitationRoundTripsTest {
    private val input = buildJsonObject { put("task", "build") }
    private val schema = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("name", buildJsonObject { put("type", "string") })
        })
        put("required", kotlinx.serialization.json.buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("name")) })
    }
    private fun required() = buildJsonObject {
        put("resultType", "input_required")
        put("requestState", "opaque:preserve-THIS")
        put("inputRequests", buildJsonObject {
            put("username", buildJsonObject {
                put("method", "elicitation/create")
                put("params", buildJsonObject {
                    put("mode", "form"); put("message", "请输入用户名")
                    put("requestedSchema", schema)
                })
            })
        })
    }
    @Test fun acceptsFormAnswerAndEchoesExactStateOnOnlyServerRequestedRetry() = runTest {
        val requests = mutableListOf<JsonObject>()
        val result = mcpCallWithInputRequired(
            request = { args ->
                requests += args
                if (requests.size == 1) required()
                else buildJsonObject { put("resultType", "complete"); put("isError", false) }
            },
            name = "lookup", arguments = input,
            onElicitation = { McpElicitationReply("accept", buildJsonObject { put("name", "test-user") }) },
        )
        assertEquals(2, requests.size)
        assertEquals("opaque:preserve-THIS", requests[1]["requestState"]!!.jsonPrimitive.content)
        assertEquals("lookup", requests[1]["name"]!!.jsonPrimitive.content)
        assertEquals(input, requests[1]["arguments"])
        val answer = requests[1]["inputResponses"]!!.jsonObject["username"]!!.jsonObject
        assertEquals("accept", answer["action"]!!.jsonPrimitive.content)
        assertEquals("test-user", answer["content"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("complete", result["resultType"]!!.jsonPrimitive.content)
    }

    @Test fun userCancelIsForwardedWithoutMakingUpAnAnswer() = runTest {
        var step = 0
        val result = mcpCallWithInputRequired(
            request = { args ->
                step++
                if (step == 1) required()
                else {
                    assertEquals("cancel", args["inputResponses"]!!.jsonObject["username"]!!.jsonObject["action"]!!.jsonPrimitive.content)
                    buildJsonObject { put("resultType", "complete"); put("isError", true) }
                }
            },
            name = "lookup", arguments = input,
            onElicitation = { McpElicitationReply("cancel") },
        )
        assertEquals(2, step)
        assertEquals(true, result["isError"]!!.jsonPrimitive.content.toBoolean())
    }


    @Test fun multiSelectArrayRoundTripsAndRejectsInvalidChoices() = runTest {
        val selectionSchema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("colors", buildJsonObject {
                    put("type", "array")
                    put("minItems", 1)
                    put("maxItems", 2)
                    put("items", buildJsonObject {
                        put("type", "string")
                        put("enum", buildJsonArray {
                            add(JsonPrimitive("red"))
                            add(JsonPrimitive("blue"))
                        })
                    })
                })
            })
            put("required", buildJsonArray { add(JsonPrimitive("colors")) })
        }
        val prompt = buildJsonObject {
            put("resultType", "input_required")
            put("inputRequests", buildJsonObject {
                put("colors", buildJsonObject {
                    put("method", "elicitation/create")
                    put("params", buildJsonObject {
                        put("mode", "form")
                        put("message", "选择颜色")
                        put("requestedSchema", selectionSchema)
                    })
                })
            })
        }
        suspend fun runWith(values: List<String>): Pair<Int, Result<JsonObject>> {
            var calls = 0
            val result = runCatching {
                mcpCallWithInputRequired(
                    request = { args ->
                        calls++
                        if (calls == 1) prompt else {
                            val selected = args["inputResponses"]!!.jsonObject
                                .getValue("colors").jsonObject.getValue("content").jsonObject
                                .getValue("colors") as kotlinx.serialization.json.JsonArray
                            assertEquals(values.size, selected.size)
                            buildJsonObject { put("resultType", "complete") }
                        }
                    },
                    name = "choose", arguments = input,
                    onElicitation = {
                        McpElicitationReply("accept", buildJsonObject {
                            put("colors", buildJsonArray { values.forEach { add(JsonPrimitive(it)) } })
                        })
                    },
                )
            }
            return calls to result
        }
        val valid = runWith(listOf("red", "blue"))
        assertEquals(2, valid.first)
        assertTrue(valid.second.isSuccess)
        val invalid = runWith(listOf("red", "wrong"))
        assertEquals(1, invalid.first)
        assertTrue(invalid.second.isFailure)
        val empty = runWith(emptyList())
        assertEquals(1, empty.first)
        assertTrue(empty.second.isFailure)
    }

    @Test fun ordinaryResultsNeverPromptOrReplay() = runTest {
        var calls = 0
        mcpCallWithInputRequired(
            request = { calls++; buildJsonObject { put("content", "ok") } },
            name = "read", arguments = input,
            onElicitation = { error("不得显示追问") },
        )
        assertEquals(1, calls)
    }

    @Test fun unsupportedServerInitiatedInputFailsWithoutResendingTool() = runTest {
        var calls = 0
        val failure = runCatching {
            mcpCallWithInputRequired(
                request = {
                    calls++
                    buildJsonObject {
                        put("resultType", "input_required")
                        put("inputRequests", buildJsonObject {
                            put("bad", buildJsonObject { put("method", "sampling/createMessage") })
                        })
                    }
                }, name = "write", arguments = input,
                onElicitation = { error("not reached") },
            )
        }
        assertTrue(failure.isFailure)
        assertEquals(1, calls)
    }

    @Test fun malformedFormResponseCannotBeRelayed() = runTest {
        var calls = 0
        val failed = runCatching {
            mcpCallWithInputRequired(
                request = { calls++; required() },
                name = "write", arguments = input,
                onElicitation = { McpElicitationReply("accept", buildJsonObject { put("unknown", "wrong") }) },
            )
        }
        assertTrue(failed.isFailure)
        assertEquals(1, calls)
    }

    @Test fun cancellationStopsFurtherServerPromptsWithoutReopeningTheUI() = runTest {
        var calls = 0
        var prompts = 0
        val stopped = runCatching {
            mcpCallWithInputRequired(
                request = { calls++; required() },
                name = "write", arguments = input,
                onElicitation = { prompts++; McpElicitationReply("cancel") },
            )
        }
        assertTrue(stopped.isFailure)
        assertEquals(2, calls)
        assertEquals(1, prompts)
    }

    @Test fun repeatedEmptyPromptWithUnchangedStateStopsWithoutBusyLoop() = runTest {
        var calls = 0
        val stuck = buildJsonObject {
            put("resultType", "input_required")
            put("requestState", "same")
            put("inputRequests", buildJsonObject {})
        }
        val result = runCatching {
            mcpCallWithInputRequired(
                request = { calls++; stuck },
                name = "read", arguments = input,
                onElicitation = { error("无输入请求不应弹窗") },
            )
        }
        assertTrue(result.isFailure)
        assertEquals(2, calls)
    }

    @Test fun moreThanEightLegitimateElicitationRoundsCanFinish() = runTest {
        var calls = 0
        var prompts = 0
        val result = mcpCallWithInputRequired(
            request = {
                calls++
                if (calls <= 10) required()
                else buildJsonObject { put("resultType", "complete") }
            },
            name = "read", arguments = input,
            onElicitation = {
                prompts++
                McpElicitationReply("decline")
            },
        )
        assertEquals(11, calls)
        assertEquals(10, prompts)
        assertEquals("complete", result["resultType"]!!.jsonPrimitive.content)
    }

    @Test fun formWithMoreThanTwelveFieldsUsesTheSameProtocol() = runTest {
        val schema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                repeat(15) { index ->
                    put("item$index", buildJsonObject { put("type", "string") })
                }
            })
        }
        var calls = 0
        val result = mcpCallWithInputRequired(
            request = {
                calls++
                if (calls == 1) buildJsonObject {
                    put("resultType", "input_required")
                    put("inputRequests", buildJsonObject {
                        put("form", buildJsonObject {
                            put("method", "elicitation/create")
                            put("params", buildJsonObject {
                                put("mode", "form")
                                put("message", "完整设置")
                                put("requestedSchema", schema)
                            })
                        })
                    })
                } else buildJsonObject { put("resultType", "complete") }
            },
            name = "read", arguments = input,
            onElicitation = { McpElicitationReply("accept", buildJsonObject {}) },
        )
        assertEquals("complete", result["resultType"]!!.jsonPrimitive.content)
        assertEquals(2, calls)
    }
}

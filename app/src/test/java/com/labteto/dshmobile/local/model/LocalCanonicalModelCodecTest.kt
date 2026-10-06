package com.labteto.dshmobile.local.model

import com.labteto.dshmobile.local.LocalModelException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalCanonicalModelCodecTest {
    @Test
    fun legacyEmptyAssistantIsOmittedFromEveryProtocolWithoutMutatingDurableHistory() {
        val empty = Json.parseToJsonElement("""{"role":"assistant","_dsh_model_replay":{"adapter_id":"openai-responses","route_fingerprint":"old","payload":{"output":[]}},"model_tool_calls":[]}""").jsonObject
        val user = Json.parseToJsonElement("""{"role":"user","content":"continue"}""").jsonObject
        val source = listOf(empty, user)
        val canonical = LocalCanonicalModelCodec.messages(source)
        listOf(LocalModelAdapterIds.OPENAI_CHAT, LocalModelAdapterIds.OPENAI_RESPONSES, LocalModelAdapterIds.ANTHROPIC_MESSAGES).forEach { adapter ->
            val projected = LocalCanonicalModelCodec.toLegacyMessages(canonical, adapter, "different-account")
            assertEquals(listOf(user), projected)
        }
        assertEquals(2, source.size)
        assertThrows(LocalModelException::class.java) {
            LocalCanonicalModelCodec.canonicalizeReply(LocalModelReply(empty, null, null, emptyList()),
                LocalModelAdapterIds.OPENAI_RESPONSES, "old")
        }
    }

    @Test
    fun legacyResponsesReplayMigratesToGenericHistoryAndOnlyReturnsToResponses() {
        val legacyOutput = buildJsonArray {
            add(buildJsonObject {
                put("type", "reasoning")
                put("encrypted_content", "secret")
            })
        }
        val legacy = buildJsonObject {
            put("role", "assistant")
            put("content", "完成")
            put("_dsh_responses_output", legacyOutput)
        }

        val canonical = LocalCanonicalModelCodec.message(legacy)
        val history = LocalCanonicalModelCodec.toHistoryMessage(canonical)
        assertNotNull(history[LOCAL_MODEL_REPLAY_KEY])
        assertNull(history["_dsh_responses_output"])

        val responsesWire = LocalCanonicalModelCodec.toLegacyMessages(
            listOf(canonical),
            LocalModelAdapterIds.OPENAI_RESPONSES,
            "route-a",
        ).single()
        assertEquals(legacyOutput, responsesWire["_dsh_responses_output"])
        assertNull(responsesWire[LOCAL_MODEL_REPLAY_KEY])

        val chatWire = LocalCanonicalModelCodec.toLegacyMessages(
            listOf(canonical),
            LocalModelAdapterIds.OPENAI_CHAT,
            "route-a",
        ).single()
        assertNull(chatWire["_dsh_responses_output"])
        assertNull(chatWire[LOCAL_MODEL_REPLAY_KEY])
    }

    @Test
    fun chatPrivateReasoningAndToolMetadataAreReplayedOnlyOnTheSameRoute() {
        val source = buildJsonObject {
            put("role", "assistant")
            put("content", "")
            put("reasoning_content", "隐藏推理")
            put("tool_calls", buildJsonArray {
                add(buildJsonObject {
                    put("id", "call-1")
                    put("type", "function")
                    put("extra_content", buildJsonObject {
                        put("google", buildJsonObject { put("thought_signature", "sig-1") })
                    })
                    put("function", buildJsonObject {
                        put("name", "read")
                        put("arguments", """{"path":"a"}""")
                        put("vendor_flag", "kept")
                    })
                })
            })
        }

        val canonicalized = LocalCanonicalModelCodec.canonicalizeReply(
            reply = LocalModelReply(
                message = source,
                content = null,
                reasoning = "隐藏推理",
                toolCalls = emptyList(),
            ),
            adapterId = LocalModelAdapterIds.OPENAI_CHAT,
            routeFingerprint = "gemini-route",
        )
        val history = canonicalized.message
        assertNotNull(history[LOCAL_MODEL_REPLAY_KEY])
        val durableCall = history["tool_calls"]!!.jsonArray.single().jsonObject
        assertNull(durableCall["extra_content"])
        assertNull(durableCall["function"]!!.jsonObject["vendor_flag"])

        val restored = LocalCanonicalModelCodec.message(history)
        val sameRoute = LocalCanonicalModelCodec.toLegacyMessages(
            listOf(restored),
            LocalModelAdapterIds.OPENAI_CHAT,
            "gemini-route",
        ).single()
        val sameCall = sameRoute["tool_calls"]!!.jsonArray.single().jsonObject
        assertEquals("隐藏推理", sameRoute["reasoning_content"]!!.jsonPrimitive.content)
        assertEquals(
            "sig-1",
            sameCall["extra_content"]!!.jsonObject["google"]!!.jsonObject["thought_signature"]!!.jsonPrimitive.content,
        )
        assertEquals("kept", sameCall["function"]!!.jsonObject["vendor_flag"]!!.jsonPrimitive.content)

        val changedRoute = LocalCanonicalModelCodec.toLegacyMessages(
            listOf(restored),
            LocalModelAdapterIds.OPENAI_CHAT,
            "deepseek-route",
        ).single()
        val changedCall = changedRoute["tool_calls"]!!.jsonArray.single().jsonObject
        assertNull(changedRoute["reasoning_content"])
        assertNull(changedCall["extra_content"])
        assertNull(changedCall["function"]!!.jsonObject["vendor_flag"])
    }

    @Test
    fun routeBoundReplayIsDroppedWhenRouteChanges() {
        val replay = LocalModelReplayEnvelope(
            adapterId = LocalModelAdapterIds.OPENAI_RESPONSES,
            routeFingerprint = "route-a",
            payload = buildJsonObject {
                put("output", buildJsonArray {
                    add(buildJsonObject {
                        put("type", "reasoning")
                        put("encrypted_content", "secret")
                    })
                })
            },
        )
        val message = LocalCanonicalMessage(
            role = LocalCanonicalRole.ASSISTANT,
            content = listOf(LocalCanonicalContent.Text("可见文本")),
            replay = replay,
        )

        val same = LocalCanonicalModelCodec.toLegacyMessages(
            listOf(message),
            LocalModelAdapterIds.OPENAI_RESPONSES,
            "route-a",
        ).single()
        assertNotNull(same["_dsh_responses_output"])

        val changed = LocalCanonicalModelCodec.toLegacyMessages(
            listOf(message),
            LocalModelAdapterIds.OPENAI_RESPONSES,
            "route-b",
        ).single()
        assertNull(changed["_dsh_responses_output"])
        assertEquals("可见文本", changed["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun malformedToolFieldsAreRejectedInsteadOfBeingSilentlyNormalized() {
        val badParameters = buildJsonArray {
            add(buildJsonObject {
                put("type", "function")
                put("function", buildJsonObject {
                    put("name", "read")
                    put("description", "读取文件")
                    put("parameters", JsonPrimitive("not-an-object"))
                })
            })
        }
        val badDescription = buildJsonArray {
            add(buildJsonObject {
                put("type", "function")
                put("function", buildJsonObject {
                    put("name", "read")
                    put("description", 123)
                })
            })
        }
        val badStrict = buildJsonArray {
            add(buildJsonObject {
                put("type", "function")
                put("function", buildJsonObject {
                    put("name", "read")
                    put("strict", "true")
                })
            })
        }

        assertTrue(
            runCatching { LocalCanonicalModelCodec.tools(badParameters) }
                .exceptionOrNull()?.message.orEmpty().contains("parameters"),
        )
        assertTrue(
            runCatching { LocalCanonicalModelCodec.tools(badDescription) }
                .exceptionOrNull()?.message.orEmpty().contains("description"),
        )
        assertTrue(
            runCatching { LocalCanonicalModelCodec.tools(badStrict) }
                .exceptionOrNull()?.message.orEmpty().contains("strict"),
        )
    }

    @Test
    fun toolDefinitionsBecomeProviderNeutralBeforeAdapterProjection() {
        val tools = buildJsonArray {
            add(buildJsonObject {
                put("type", "function")
                put("function", buildJsonObject {
                    put("name", "read")
                    put("description", "读取文件")
                    put("parameters", buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            put("path", buildJsonObject { put("type", "string") })
                        })
                    })
                    put("strict", false)
                })
            })
        }
        val canonical = LocalCanonicalModelCodec.tools(tools)
        assertEquals(1, canonical.size)
        assertEquals("read", canonical.single().name)
        assertEquals(false, canonical.single().strict)

        val projected = LocalCanonicalModelCodec.toLegacyTools(canonical)
        assertEquals("read", projected.single().jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertFalse(projected.toString().contains(LOCAL_MODEL_REPLAY_KEY))
        assertTrue(projected.single().jsonObject["function"]!!.jsonObject.containsKey("parameters"))
    }

    @Test
    fun invalidToolDefinitionsCannotBeNormalizedPastAdapterValidation() {
        listOf(
            """[{"type":"function","function":{"name":"read","strict":"true"}}]""",
            """[{"type":"function","function":{"name":"read","parameters":[]}}]""",
            """[{"type":"function","function":{"name":123}}]""",
            """[{"type":"function","function":{"name":"read","description":123}}]""",
        ).forEach { raw ->
            val error = runCatching { LocalCanonicalModelCodec.tools(Json.parseToJsonElement(raw).jsonArray) }.exceptionOrNull()
            assertEquals("MODEL_TOOL_SCHEMA_INVALID", (error as? com.labteto.dshmobile.local.LocalModelException)?.code)
        }
    }

    @Test
    fun interleavedTextAndImagesPreserveOrderAndStrictEmptyToolsRemainValid() {
        val source = Json.parseToJsonElement("""{"role":"user","content":[{"type":"text","text":"first"},{"type":"image_url","image_url":{"url":"data:image/png;base64,AAAA","detail":"high"}},{"type":"text","text":"second"}]}""").jsonObject
        val message = LocalCanonicalModelCodec.toLegacyMessages(LocalCanonicalModelCodec.messages(listOf(source)), LocalModelAdapterIds.OPENAI_CHAT, "route").single()
        assertEquals(source["content"], message["content"])
        val tools = LocalCanonicalModelCodec.tools(Json.parseToJsonElement("""[{"type":"function","function":{"name":"read","strict":true}}]""").jsonArray)
        val adapted = OpenAiResponsesToolAdapter.adapt(LocalCanonicalModelCodec.toLegacyTools(tools), false, true)
        assertEquals("false", adapted.single().jsonObject["parameters"]!!.jsonObject["additionalProperties"]!!.jsonPrimitive.content)
    }

    @Test
    fun summaryNamesRemainAvailableWithoutMakingDamagedHistoryExecutable() {
        val partial = Json.parseToJsonElement("""{"role":"assistant","tool_calls":[{"function":{"name":"read"}}]}""").jsonObject
        assertEquals(listOf("read"), LocalCanonicalModelCodec.diagnosticToolNames(partial))
        assertTrue(runCatching { LocalCanonicalModelCodec.canonicalToolCalls(partial) }.isFailure)
        val damaged = Json.parseToJsonElement("""{"role":"assistant","tool_calls":[{"id":"a","function":{"name":"read","arguments":"invalid"}}]}""").jsonObject
        assertEquals(listOf("read"), LocalCanonicalModelCodec.diagnosticToolNames(damaged))
        assertTrue(runCatching { LocalCanonicalModelCodec.canonicalToolCalls(damaged) }.isFailure)
    }
}

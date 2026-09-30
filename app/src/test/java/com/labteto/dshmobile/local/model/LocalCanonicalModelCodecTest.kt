package com.labteto.dshmobile.local.model

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
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalCanonicalModelCodecTest {
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
    fun providerToolMetadataSurvivesCanonicalRoundTrip() {
        val source = buildJsonObject {
            put("role", "assistant")
            put("content", "")
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

        val canonical = LocalCanonicalModelCodec.message(source)
        val history = LocalCanonicalModelCodec.toHistoryMessage(canonical)
        val restored = LocalCanonicalModelCodec.message(history)
        val wire = LocalCanonicalModelCodec.toLegacyMessages(
            listOf(restored),
            LocalModelAdapterIds.OPENAI_CHAT,
            "same-route",
        ).single()
        val call = wire["tool_calls"]!!.jsonArray.single().jsonObject

        assertEquals(
            "sig-1",
            call["extra_content"]!!.jsonObject["google"]!!.jsonObject["thought_signature"]!!.jsonPrimitive.content,
        )
        assertEquals("kept", call["function"]!!.jsonObject["vendor_flag"]!!.jsonPrimitive.content)
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
}

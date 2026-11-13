package com.labteto.dshmobile.local.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class LocalRequestEvidenceTest {
    @Test
    fun capturesOnlyModelInstructionContextAndStableDigests() {
        val messages = listOf(
            buildJsonObject {
                put("role", "system")
                put("content", "系统规则")
            },
            buildJsonObject {
                put("role", "developer")
                put("content", "运行约束")
            },
            buildJsonObject {
                put("role", "user")
                put("content", "处理任务")
            },
        )
        val tools = JsonArray(
            listOf(
                buildJsonObject {
                    put("type", "function")
                    put("function", buildJsonObject {
                        put("name", "read")
                    })
                },
            ),
        )

        val first = buildLocalRequestEvidence(messages, tools)
        val second = buildLocalRequestEvidence(messages, tools)

        assertEquals(first, second)
        assertEquals(2, first.contextMessages.size)
        assertEquals(
            listOf("system", "developer"),
            first.contextMessages.map { it.jsonObject["role"]!!.jsonPrimitive.content },
        )
    }

    @Test
    fun nativeToolSurfaceChangesResolvedDigestWithoutChangingFunctionSchemaDigest() {
        val messages = listOf(
            buildJsonObject {
                put("role", "user")
                put("content", "画一张图")
            },
        )
        val tools = JsonArray(emptyList())
        val withoutNative = buildLocalRequestEvidence(messages, tools)
        val withImageGeneration = buildLocalRequestEvidence(
            messages = messages,
            tools = tools,
            nativeTools = JsonArray(listOf(JsonPrimitive("image_generation"))),
        )

        assertEquals(withoutNative.messageDigest, withImageGeneration.messageDigest)
        assertEquals(withoutNative.toolSchemaDigest, withImageGeneration.toolSchemaDigest)
        assertNotEquals(
            withoutNative.resolvedToolSurfaceDigest,
            withImageGeneration.resolvedToolSurfaceDigest,
        )
    }

    @Test
    fun changingToolSurfaceChangesEnvelopeEvidenceWithoutChangingMessageDigest() {
        val messages = listOf(
            buildJsonObject {
                put("role", "user")
                put("content", "同一个请求")
            },
        )
        val noTools = buildLocalRequestEvidence(messages, JsonArray(emptyList()))
        val oneTool = buildLocalRequestEvidence(
            messages,
            JsonArray(
                listOf(
                    buildJsonObject {
                        put("type", "function")
                        put("function", buildJsonObject { put("name", "read") })
                    },
                ),
            ),
        )

        assertEquals(noTools.messageDigest, oneTool.messageDigest)
        assertNotEquals(noTools.toolSchemaDigest, oneTool.toolSchemaDigest)
        assertEquals(noTools.contextDigest, oneTool.contextDigest)
    }
}

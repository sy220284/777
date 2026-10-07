package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.model.LocalCanonicalContent
import com.labteto.dshmobile.local.model.LocalCanonicalMessage
import com.labteto.dshmobile.local.model.LocalCanonicalRole
import com.labteto.dshmobile.local.model.LocalModelReply
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ChatReplyRepairBudgetTest {
    @Test
    fun textRepairPreservesOriginalMediaAndUsesRepairedRequestIdentity() {
        val original = LocalModelReply(
            message = buildJsonObject {
                put("role", "assistant")
                put("content", buildJsonArray {
                    add(buildJsonObject {
                        put("type", "output_text")
                        put("text", "旧文字")
                    })
                    add(buildJsonObject {
                        put("type", "image_url")
                        put("image_url", buildJsonObject {
                            put("url", "data:image/png;base64,AAAA")
                        })
                    })
                })
            },
            content = "旧文字",
            reasoning = null,
            toolCalls = emptyList(),
            requestId = "original-request",
            canonicalMessage = LocalCanonicalMessage(
                role = LocalCanonicalRole.ASSISTANT,
                content = listOf(
                    LocalCanonicalContent.Text("旧文字"),
                    LocalCanonicalContent.Image("data:image/png;base64,AAAA"),
                ),
            ),
        )
        val repaired = LocalModelReply(
            message = buildJsonObject {
                put("role", "assistant")
                put("content", "新文字")
            },
            content = "新文字",
            reasoning = "repair-reasoning",
            toolCalls = emptyList(),
            requestId = "repair-request",
            canonicalMessage = LocalCanonicalMessage(
                role = LocalCanonicalRole.ASSISTANT,
                content = listOf(LocalCanonicalContent.Text("新文字")),
            ),
        )

        val merged = preserveLocalChatReplyMediaForTextRepair(original, repaired)

        assertEquals("新文字", merged.content)
        assertEquals("repair-request", merged.requestId)
        assertEquals("repair-reasoning", merged.reasoning)
        val content = merged.message["content"]!!.jsonArray
        assertEquals("新文字", content[0].jsonObject["text"]?.jsonPrimitive?.content)
        assertEquals(
            "data:image/png;base64,AAAA",
            content[1].jsonObject["image_url"]?.jsonObject?.get("url")?.jsonPrimitive?.content,
        )
        assertEquals(
            1,
            merged.canonicalMessage!!.content.count { it is LocalCanonicalContent.Image },
        )
    }

    @Test fun domainRepairsShareOneBoundedCandidateBudget() = runTest {
        val budget = ChatReplyRepairBudget()
        var requests = 0
        assertEquals("immersion", budget.repair("immersion") { requests++; it })
        assertEquals("continuity", budget.repair("continuity") { requests++; it })
        try {
            budget.repair("nested") { requests++; it }
            throw AssertionError("第三次修复不得发起模型请求")
        } catch (_: IllegalStateException) { }
        assertEquals(2, requests)
    }
}

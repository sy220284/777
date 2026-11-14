package com.labteto.dshmobile.local.model

import com.labteto.dshmobile.local.session.LocalHarnessMessage
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalConversationSampleCoordinatorTest {
    @Test fun requiresNonEmptyBoundedUtf8Prompt() {
        assertEquals("继续这个任务", validateLocalSessionSamplePrompt(" 继续这个任务 "))
        assertThrows(IllegalArgumentException::class.java) {
            validateLocalSessionSamplePrompt("  ")
        }
        assertThrows(IllegalArgumentException::class.java) {
            validateLocalSessionSamplePrompt("测".repeat(1_334))
        }
        assertEquals(4_000, validateLocalSessionSamplePrompt("a".repeat(4_000)).length)
    }

    @Test fun copiedContextContainsOnlyLatestDialogueAndDoesNotModifyHistory() {
        val original = (0 until 20).map { i ->
            LocalHarnessMessage("id-$i", if (i % 2 == 0) "user" else "assistant", "turn-$i", createdAt = i.toLong())
        } + LocalHarnessMessage("tool", "tool", "tool-secret", createdAt = 21L)
        val before = original.toList()
        val result = localSessionSampleMessages(original, "写一个后续示例")
        assertEquals(18, result.size)
        assertEquals("system", result.first()["role"]?.jsonPrimitive?.content)
        assertEquals("turn-4", result[1]["content"]?.jsonPrimitive?.content)
        assertEquals("turn-19", result[16]["content"]?.jsonPrimitive?.content)
        assertEquals("写一个后续示例", result.last()["content"]?.jsonPrimitive?.content)
        assertTrue(result.none { it["content"]?.jsonPrimitive?.content?.contains("tool-secret") == true })
        assertEquals(before, original)
    }

    @Test fun sameMessageIdWithModifiedTextInvalidatesInFlightSample() {
        val before = listOf(LocalHarnessMessage("stable-id", "user", "原始内容", createdAt = 1L))
        val edited = listOf(before.single().copy(content = "修改后的内容"))
        assertTrue(localSessionSampleContextIsCurrent("session", before, "session", before.toList()))
        assertTrue(!localSessionSampleContextIsCurrent("session", before, "session", edited))
        assertTrue(!localSessionSampleContextIsCurrent("session", before, "other-session", before))
    }

    @Test fun individualHistoryItemIsBoundedWithoutOverwritingSource() {
        val huge = "Z".repeat(12_000)
        val source = listOf(LocalHarnessMessage("test", "user", huge, createdAt = 1L))
        val result = localSessionSampleMessages(source, "预测回复")
        assertEquals(6_000, result[1]["content"]?.jsonPrimitive?.content?.length)
        assertEquals(huge, source.single().content)
    }

    private fun actualMessage(role: String, content: String): JsonObject = buildJsonObject {
        put("role", role)
        put("content", content)
    }

    @Test fun modelHistoryForkIncludesAllContextWithoutMutatingOriginal() {
        val original = listOf(actualMessage("system", "项目只能只读操作")) +
            (0 until 40).map { i ->
                actualMessage(if (i % 2 == 0) "user" else "assistant", "turn-$i")
            }
        val before = original.toList()
        val result = localSessionSampleModelMessages(original, "模拟继续")
        assertEquals(42, result.size)
        assertTrue(result[0]["content"]!!.jsonPrimitive.content.contains("项目只能只读操作"))
        assertTrue(result[0]["content"]!!.jsonPrimitive.content.contains("只读会话采样"))
        assertEquals("turn-0", result[1]["content"]?.jsonPrimitive?.content)
        assertEquals("turn-39", result[40]["content"]?.jsonPrimitive?.content)
        assertEquals("模拟继续", result.last()["content"]?.jsonPrimitive?.content)
        assertEquals(before, original)
    }

    @Test fun modelHistoryForkPreservesCompletedToolBatch() {
        val call = buildJsonObject {
            put("id", "call-1")
            put("type", "function")
            put("function", buildJsonObject {
                put("name", "read_file")
                put("arguments", "{}")
            })
        }
        val assistant = buildJsonObject {
            put("role", "assistant")
            put("content", "")
            put("tool_calls", JsonArray(listOf(call)))
        }
        val receipt = buildJsonObject {
            put("role", "tool")
            put("tool_call_id", "call-1")
            put("content", "实际读取结果")
        }
        val history = listOf(actualMessage("user", "检查文件"), assistant, receipt)
        val result = localSessionSampleModelMessages(history, "后续计划")
        assertEquals(5, result.size)
        assertEquals(assistant, result[2])
        assertEquals(receipt, result[3])
        assertEquals(history, listOf(actualMessage("user", "检查文件"), assistant, receipt))
    }

    @Test fun ephemeralToolOutputDoesNotReenterSamplingContext() {
        val transient = buildJsonObject {
            put("role", "tool")
            put("content", "私密瞬时设备内容")
            put("tool_call_id", "call-id")
            put(LOCAL_TOOL_RESULT_RETENTION_KEY, "ephemeral")
        }
        val result = localSessionSampleModelMessages(
            listOf(actualMessage("user", "查看设备"), transient), "继续",
        )
        assertTrue(result.none { it["content"]?.jsonPrimitive?.content?.contains("私密瞬时设备内容") == true })
        assertEquals("私密瞬时设备内容", transient["content"]?.jsonPrimitive?.content)
    }

    @Test fun emptyRealModelContextFailsWithSpecificError() {
        assertThrows(IllegalArgumentException::class.java) {
            localSessionSampleModelMessages(emptyList(), "继续")
        }
        assertThrows(IllegalArgumentException::class.java) {
            localSessionSampleModelMessages(listOf(actualMessage("system", "only system")), "继续")
        }
    }

}

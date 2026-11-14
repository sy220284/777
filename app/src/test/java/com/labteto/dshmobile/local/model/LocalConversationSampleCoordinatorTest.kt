package com.labteto.dshmobile.local.model

import com.labteto.dshmobile.local.session.LocalHarnessMessage
import kotlinx.serialization.json.jsonPrimitive
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
}

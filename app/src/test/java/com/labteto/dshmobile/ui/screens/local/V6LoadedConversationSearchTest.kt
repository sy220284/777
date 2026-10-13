package com.labteto.dshmobile.ui.screens.local

import com.labteto.dshmobile.local.session.LocalHarnessMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class V6LoadedConversationSearchTest {
    private fun item(id: String, role: String, content: String) = LocalTranscriptItem.Message(
        LocalHarnessMessage(id = id, role = role, content = content, createdAt = 1L),
    )

    @Test fun `returns exact visible message id and transcript index, newest first`() {
        val items = listOf(
            item("u1", "user", "今天的方案"),
            item("a1", "assistant", "已经完成方案设计"),
            item("u2", "user", "方案要适配手机"),
        )
        val results = v6SearchLoadedConversation(items, "  方案  ")
        assertEquals(listOf("u2", "a1", "u1"), results.map { it.messageId })
        assertEquals(listOf(2, 1, 0), results.map { it.transcriptIndex })
        assertEquals("方案要适配手机", results[0].excerpt)
    }

    @Test fun `does not expose system tool work-process or reasoning content`() {
        val items = listOf(
            item("sys", "system", "敏感内部内容"),
            item("tool", "tool", "敏感内部内容"),
            LocalTranscriptItem.Thinking(listOf(
                LocalHarnessMessage(id = "r1", role = "reasoning", content = "敏感内部内容", createdAt = 1L),
            )),
            item("u", "user", "公开内容"),
        )
        assertTrue(v6SearchLoadedConversation(items, "敏感").isEmpty())
        assertEquals(listOf("u"), v6SearchLoadedConversation(items, "公开").map { it.messageId })
    }

    @Test fun `empty queries and zero budget are inert`() {
        val items = listOf(item("1", "user", "测试"))
        assertTrue(v6SearchLoadedConversation(items, "  ").isEmpty())
        assertTrue(v6SearchLoadedConversation(items, "测试", limit = 0).isEmpty())
    }

    @Test fun `result budget caps only matched messages`() {
        val items = (0..90).map { item("id-${it}", "user", "检索消息${it}") }
        val matched = v6SearchLoadedConversation(items, "检索", limit = 12)
        assertEquals(12, matched.size)
        assertEquals("id-90", matched.first().messageId)
        assertEquals("id-79", matched.last().messageId)
    }
}

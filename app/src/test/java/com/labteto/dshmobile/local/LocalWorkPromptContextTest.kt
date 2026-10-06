package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.work.withWorkTurnContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalWorkPromptContextTest {
    private fun message(role: String, content: String) = buildJsonObject {
        put("role", role)
        put("content", content)
    }

    @Test
    fun dynamicWorkContextMovesToTailAndKeepsLargeStablePrefixReusableAcrossTurns() {
        val firstHistory = listOf(
            message("system", "固定系统"),
            message("user", "旧问题"),
            message("assistant", "旧回答"),
            message("user", "本轮问题"),
        )
        val first = withWorkTurnContext(
            history = firstHistory,
            stableContext = "固定运行时与长期规则",
            dynamicContext = "本轮召回记忆-A",
        )

        assertEquals("固定系统", first[0]["content"]?.toString()?.trim('"'))
        assertEquals("固定运行时与长期规则", first[1]["content"]?.toString()?.trim('"'))
        assertEquals("本轮召回记忆-A", first[first.lastIndex - 1]["content"]?.toString()?.trim('"'))
        assertEquals("本轮问题", first.last()["content"]?.toString()?.trim('"'))

        val secondHistory = listOf(
            message("system", "固定系统"),
            message("user", "旧问题"),
            message("assistant", "旧回答"),
            message("user", "本轮问题"),
            message("assistant", "本轮回答"),
            message("user", "下一轮问题"),
        )
        val second = withWorkTurnContext(
            history = secondHistory,
            stableContext = "固定运行时与长期规则",
            dynamicContext = "本轮召回记忆-B",
        )

        assertEquals(first.take(4), second.take(4))
        assertTrue(second.indexOfFirst { it["content"]?.toString()?.contains("本轮召回记忆-B") == true } > 4)
    }
}

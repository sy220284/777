package com.labteto.dshmobile.local

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalSubagentContextTest {
    @Test
    fun trimsAndBoundsInheritedContext() {
        assertEquals("父级规则", boundedSubagentContext("  父级规则  "))
        assertEquals("abcd", boundedSubagentContext("abcdef", maxChars = 4))
    }

    @Test
    fun blankInheritedContextIsOmitted() {
        assertNull(boundedSubagentContext("   "))
    }

    @Test
    fun forkHistoryStopsBeforeTheParentToolCallBeingExecuted() {
        val history = listOf(
            message("system", "系统"),
            message("user", "旧问题"),
            message("assistant", "旧回答"),
            message("user", "当前问题"),
            buildJsonObject {
                put("role", "assistant")
                put("content", "")
                put("tool_calls", buildJsonArray {
                    add(toolCall("call-read", "read"))
                    add(toolCall("call-fork", "subagent_fork"))
                })
            },
            buildJsonObject {
                put("role", "tool")
                put("tool_call_id", "call-read")
                put("content", "已读取")
            },
        )

        val inherited = inheritedHistoryBeforeToolCall(history, "call-fork")

        assertEquals(4, inherited.size)
        assertEquals("user", inherited.last()["role"]?.jsonPrimitive?.contentOrNull)
        assertEquals("当前问题", inherited.last()["content"]?.jsonPrimitive?.contentOrNull)
    }

    private fun message(role: String, content: String) = buildJsonObject {
        put("role", role)
        put("content", content)
    }

    private fun toolCall(id: String, name: String) = buildJsonObject {
        put("id", id)
        put("type", "function")
        put("function", buildJsonObject {
            put("name", name)
            put("arguments", "{}")
        })
    }
}

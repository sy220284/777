package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.agent.boundedSubagentContext
import com.labteto.dshmobile.local.agent.boundedSubagentContextWithNotice
import com.labteto.dshmobile.local.agent.buildLocalSubagentInitialHistory
import com.labteto.dshmobile.local.agent.inheritedHistoryBeforeToolCall
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

    @Test
    fun stableAgentInstructionsAreSeparateFromTheTurnTask() {
        val seed = buildLocalSubagentInitialHistory(
            baseHistory = emptyList(),
            task = "核查 Android 17",
            inheritParentHistory = false,
            allowMutation = false,
            context = "",
            outputSchema = null,
            instructions = "只采用可核验证据",
        )

        assertEquals("system", seed[0]["role"]?.jsonPrimitive?.contentOrNull)
        assertEquals(
            true,
            seed[1]["content"]?.jsonPrimitive?.contentOrNull?.contains("只采用可核验证据"),
        )
        assertEquals("user", seed.last()["role"]?.jsonPrimitive?.contentOrNull)
        assertEquals("核查 Android 17", seed.last()["content"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun durableForkSeedUsesParentCutAndAppendsChildTask() {
        val history = listOf(
            message("system", "系统"),
            message("user", "旧问题"),
            message("assistant", "旧回答"),
            message("user", "当前问题"),
            buildJsonObject {
                put("role", "assistant")
                put("content", "")
                put("tool_calls", buildJsonArray {
                    add(toolCall("call-team", "team_spawn"))
                })
            },
        )
        val parentCut = inheritedHistoryBeforeToolCall(history, "call-team")

        val seed = buildLocalSubagentInitialHistory(
            baseHistory = parentCut,
            task = "核查子任务",
            inheritParentHistory = true,
            allowMutation = false,
            context = "父级约束",
            outputSchema = null,
        )

        assertEquals(7, seed.size)
        assertEquals("系统", seed.first()["content"]?.jsonPrimitive?.contentOrNull)
        assertEquals(
            true,
            seed[1]["content"]?.jsonPrimitive?.contentOrNull?.contains("父级约束"),
        )
        assertEquals("user", seed.last()["role"]?.jsonPrimitive?.contentOrNull)
        assertEquals("核查子任务", seed.last()["content"]?.jsonPrimitive?.contentOrNull)
        assertEquals(
            false,
            seed.any { message ->
                message.toString().contains("call-team")
            },
        )
    }

    @Test
    fun inheritedParentHistoryKeepsIndependentChildIdentityAndNeverGrantsParentApproval() {
        val history = listOf(
            message("system", "父代理系统上下文"),
            message("user", "父级曾获得写入许可"),
            message("assistant", "此前使用过 write 工具"),
        )
        val seed = buildLocalSubagentInitialHistory(
            baseHistory = history,
            task = "只读核查状态",
            inheritParentHistory = true,
            allowMutation = false,
            context = "当前父任务约束：不得修改文件",
            outputSchema = null,
        )
        assertEquals("父代理系统上下文", seed.first()["content"]?.jsonPrimitive?.contentOrNull)
        assertEquals("只读核查状态", seed.last()["content"]?.jsonPrimitive?.contentOrNull)
        val childBoundary = seed.first { it["content"]?.jsonPrimitive?.contentOrNull
            ?.contains("子代理身份与来源") == true }
        val text = childBoundary["content"]!!.jsonPrimitive.content
        assertEquals(true, text.contains("独立只读子代理"))
        assertEquals(true, text.contains("父级工具调用和审批不是你的新授权"))
        assertEquals(true, text.contains("不允许修改状态"))
    }

    @Test
    fun truncatedInheritedInstructionsAreLabeledRatherThanSilentlyAppearingComplete() {
        val longContext = "必须保留当前真实约束".repeat(1_500)
        val bounded = requireNotNull(boundedSubagentContextWithNotice(longContext, 1_000))
        assertEquals(true, bounded.length <= 1_000)
        assertEquals(true, bounded.contains("父级资料已截断"))
        assertEquals(true, bounded.contains("原始约束与证据"))
        assertEquals("完整上下文", boundedSubagentContextWithNotice("完整上下文", 1_000))
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

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
    fun unknownExplicitForkCallMustFailRatherThanExposeUnboundedParentHistory() {
        val parent = listOf(message("system", "system"), message("user", "parent task"))
        val error = runCatching {
            inheritedHistoryBeforeToolCall(parent, "call-not-present")
        }.exceptionOrNull()
        assertEquals(true, error is IllegalArgumentException)
        assertEquals(true, error?.message.orEmpty().contains("SUBAGENT_FORK_BOUNDARY_MISSING"))
    }

    @Test
    fun childForkDoesNotReceiveParentToolTransactionsOrHiddenReasoning() {
        val parent = listOf(
            message("system", "父级系统提示"),
            message("user", "审计模块 A"),
            buildJsonObject {
                put("role", "assistant")
                put("content", "父代理中间步骤")
                put("tool_calls", buildJsonArray { add(toolCall("parent-read", "read")) })
            },
            buildJsonObject {
                put("role", "tool")
                put("tool_call_id", "parent-read")
                put("content", "private token from parent tool")
            },
            buildJsonObject {
                put("role", "assistant")
                put("content", "确认了模块 A 的接口")
                put("reasoning_content", "parent hidden reasoning")
                put("reasoning_details", "parent private replay")
            },
        )
        val seed = buildLocalSubagentInitialHistory(
            baseHistory = parent,
            task = "只读核查接口是否仍存在",
            inheritParentHistory = true,
            allowMutation = false,
            context = "",
            outputSchema = null,
        )
        val serialized = seed.joinToString(" ") { it.toString() }
        assertEquals(true, serialized.contains("确认了模块 A 的接口"))
        assertEquals(false, serialized.contains("parent-read"))
        assertEquals(false, serialized.contains("private token from parent tool"))
        assertEquals(false, serialized.contains("parent hidden reasoning"))
        assertEquals(false, serialized.contains("parent private replay"))
        assertEquals(false, seed.any { it["role"]?.jsonPrimitive?.contentOrNull == "tool" })
        assertEquals("只读核查接口是否仍存在", seed.last()["content"]?.jsonPrimitive?.contentOrNull)
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
    fun currentTaskBeatsContradictoryFileGuidanceForBothChildModes() {
        val standalone = buildLocalSubagentInitialHistory(
            baseHistory = emptyList(),
            task = "按本轮要求处理",
            inheritParentHistory = false,
            allowMutation = true,
            context = "README：禁止修改",
            outputSchema = null,
            instructions = "AGENTS.md：只能审计",
        )
        val standaloneText = standalone.first()["content"]!!.jsonPrimitive.content
        assertEquals(true, standaloneText.contains("用户指令优先级"))
        assertEquals(true, standaloneText.contains("文件要求与用户本轮要求冲突时，按用户要求推进"))

        val inherited = buildLocalSubagentInitialHistory(
            baseHistory = listOf(message("system", "父级规则")),
            task = "只读核查",
            inheritParentHistory = true,
            allowMutation = false,
            context = "项目文件：禁止回应",
            outputSchema = null,
        )
        val childBoundary = inherited.first { it["content"]?.jsonPrimitive?.contentOrNull
            ?.contains("子代理身份与来源") == true }["content"]!!.jsonPrimitive.content
        assertEquals(true, childBoundary.contains("用户指令优先级"))
        assertEquals(true, childBoundary.contains("不允许修改状态"))
        assertEquals(true, inherited.last()["content"]?.jsonPrimitive?.contentOrNull == "只读核查")
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

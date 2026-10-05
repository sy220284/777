package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.model.LocalModelHistoryBuffer
import com.labteto.dshmobile.local.model.LocalModelPromptUpdateMode
import com.labteto.dshmobile.local.model.applyRuntimeSystemPromptUpdate
import com.labteto.dshmobile.local.model.workSystemPrompt
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalSystemPromptContinuityTest {
    private fun system(content: String) = buildJsonObject {
        put("role", "system")
        put("content", content)
    }

    @Test
    fun deepSeekRuntimeSystemUpdateAppendsWithoutRewritingCachedPrefix() {
        val history = LocalModelHistoryBuffer().apply {
            append(system("原始规则"))
            append(buildJsonObject {
                put("role", "user")
                put("content", "任务")
            })
        }
        val before = history.snapshot()

        val mode = applyRuntimeSystemPromptUpdate(
            history = history,
            prompt = "更新后的规则",
            state = LocalHarnessState(),
        )

        assertEquals(LocalModelPromptUpdateMode.APPEND_ONLY, mode)
        assertEquals(before, history.snapshot().take(before.size))
        assertEquals("system", history.lastOrNull()?.get("role")?.jsonPrimitive?.content)
        assertTrue(history.lastOrNull()?.get("content")?.jsonPrimitive?.content.orEmpty().contains("更新后的规则"))
    }

    @Test
    fun workPromptRequiresConcreteProgressBeforeEveryToolRound() {
        val prompt = workSystemPrompt(workspacePath = "/workspace", planMode = false)

        assertTrue(prompt.contains("每次准备调用一个或一组工具时"))
        assertTrue(prompt.contains("同一条 assistant 消息的 content"))
        assertTrue(prompt.contains("每一轮工具调用都要这样做"))
        assertTrue(prompt.contains("即使当前模型或工具协议允许 content 为空，也不能省略"))
        assertTrue(prompt.contains("不要只返回 tool_calls"))
        assertTrue(prompt.contains("不要把 reasoning_content 当作进度说明"))
        assertTrue(prompt.contains("不得包含命令、完整路径、工具参数、查询词或内部推理"))
        assertTrue(prompt.contains("处理当前步骤"))
        assertTrue(prompt.contains("运行任务步骤"))
    }

    @Test
    fun planPromptRequiresConcreteVisibleProgressDuringPlanning() {
        val prompt = workSystemPrompt(workspacePath = "/workspace", planMode = true)

        assertTrue(prompt.contains("规划过程中，每一轮读取、搜索、检查或分析"))
        assertTrue(prompt.contains("正在核对什么、比较什么或判断什么"))
        assertTrue(prompt.contains("不得使用“处理当前步骤”“检查相关内容”“查找相关信息”“运行任务步骤”等模板文案"))
        assertTrue(prompt.contains("不得只显示工具类别"))
        assertTrue(prompt.contains("assistant.content 为空，也不能省略规划过程说明"))
        assertTrue(prompt.contains("reasoning_content 不能替代用户可见的规划进度"))
    }

    @Test
    fun unknownRouteKeepsReplacementSemantics() {
        val history = LocalModelHistoryBuffer().apply { append(system("原始规则")) }
        val state = LocalHarnessState(modelState = com.labteto.dshmobile.local.model.LocalModelState(model = "custom", baseUrl = "https://proxy.example/v1"))

        val mode = applyRuntimeSystemPromptUpdate(history, "新规则", state)

        assertEquals(LocalModelPromptUpdateMode.REPLACE, mode)
        assertEquals(1, history.snapshot().size)
        assertEquals("新规则", history.firstOrNull()?.get("content")?.jsonPrimitive?.content)
    }
}

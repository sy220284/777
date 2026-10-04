package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.model.LocalModelHistoryBuffer
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
    fun unknownRouteKeepsReplacementSemantics() {
        val history = LocalModelHistoryBuffer().apply { append(system("原始规则")) }
        val state = LocalHarnessState(model = "custom", baseUrl = "https://proxy.example/v1")

        val mode = applyRuntimeSystemPromptUpdate(history, "新规则", state)

        assertEquals(LocalModelPromptUpdateMode.REPLACE, mode)
        assertEquals(1, history.snapshot().size)
        assertEquals("新规则", history.firstOrNull()?.get("content")?.jsonPrimitive?.content)
    }
}

package com.labteto.dshmobile.local.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplySuggestionRelevanceTest {
    @Test fun suggestionsForFutureDialogueContainNoExtraSchedulingInstructions() {
        val prompt = ChatInteractionPlanner(kotlinx.serialization.json.Json { ignoreUnknownKeys = true })
            .suggestionsPrompt(
                persona = PersonaProfile(name = "阿青"),
                state = ChatCharacterState(),
                userMessage = "明天下午一起去公园吗",
                assistantMessage = "好啊",
            )
        assertTrue(prompt.contains("明天下午一起去公园吗"))
        assertTrue(prompt.contains("生成用户下一句可直接发送的建议"))
        assertFalse(prompt.contains("如果最近双方真实谈到未来的共同计划"))
        assertFalse(prompt.contains("这里只生成可发送的话，不创建自动任务"))
    }

    @Test fun boldnessIsNotForcedByStyleName() {
        val parser = ChatInteractionPlanParser(kotlinx.serialization.json.Json { ignoreUnknownKeys = true })
        val result = parser.parseSuggestions(
            """{"suggestions":[{"label":"随意聊","style":"放飞","text":"随便聊聊吧","bold":false}]}"""
        )
        assertEquals(false, result?.single()?.bold)
    }
}

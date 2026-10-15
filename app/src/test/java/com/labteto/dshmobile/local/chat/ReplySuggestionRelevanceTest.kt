package com.labteto.dshmobile.local.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplySuggestionRelevanceTest {
    @Test fun ordinaryReplyHasNoAutomationInstructions() {
        val prompt = "生成用户下一句"
        assertEquals(prompt, enhanceReplySuggestionPromptForScheduling(prompt, "你好，今天心情很好"))
    }

    @Test fun futurePlanIncludesSchedulingSuggestionOnlyWhenRelevant() {
        val prompt = enhanceReplySuggestionPromptForScheduling("下一句", "明天下午一起去公园吗")
        assertTrue(prompt.contains("未来的共同计划"))
        assertFalse(prompt.contains("已经创建"))
    }

    @Test fun boldnessIsNotForcedByStyleName() {
        val parser = ChatInteractionPlanParser(kotlinx.serialization.json.Json { ignoreUnknownKeys = true })
        val result = parser.parseSuggestions(
            """{"suggestions":[{"label":"随意聊","style":"放飞","text":"随便聊聊吧","bold":false}]}"""
        )
        assertEquals(false, result?.single()?.bold)
    }
}

package com.labteto.dshmobile.local.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatDiaryPromptTest {
    private val planner = ChatInteractionPlanner(Json { ignoreUnknownKeys = true })

    @Test
    fun diaryPromptDemandsNarrativePsychologyInsteadOfChronologyList() {
        val prompt = planner.prompt(
            persona = PersonaProfile(name = "阿青", personality = "嘴硬心软"),
            state = ChatCharacterState(),
            userMessage = "周末一起去海边吧",
            assistantMessage = "……行，别迟到。",
        )

        val system = chatPostTurnModelMessages(prompt)
            .first()["content"]!!.jsonPrimitive.content
        assertTrue(system.contains("不写流水账"))
        assertTrue(system.contains("心理活动"))
        assertTrue(system.contains("关系意味着什么"))
        assertTrue(system.contains("角色本人视角"))
        assertTrue(system.contains("稳定性格和心理逻辑"))
        assertTrue(system.contains("角色对用户动机的猜测不能写成事实"))
        assertTrue(prompt.contains("diaryDelta"))

        val suggestionSystem = chatReplySuggestionModelMessages("生成回复建议")
            .first()["content"]!!.jsonPrimitive.content
        assertFalse(suggestionSystem.contains("diaryDelta"))
    }

    @Test
    fun plannerPreservesStructuredDiaryDeltaForDurableProjection() {
        val plan = planner.parse(
            """
            {
              "state":{"mood":"期待"},
              "suggestions":[],
              "turnSignificance":"MAJOR",
              "diaryDelta":{
                "event":"用户确认周末和我去海边",
                "feeling":"我松了口气，又忍不住期待",
                "innerThought":"我想装得不在意，但已经开始想那天的安排",
                "relationshipMeaning":"我们第一次有了明确的共同计划",
                "unresolvedEcho":"希望他别临时改主意",
                "importance":5,
                "disclosure":"SHAREABLE"
              }
            }
            """.trimIndent(),
            previous = ChatCharacterState(),
            userMessage = "周末一起去海边吧",
            assistantMessage = "好。",
        )

        assertNotNull(plan)
        assertEquals("用户确认周末和我去海边", plan!!.diaryDelta?.event)
        assertEquals(5, plan.diaryDelta?.importance)
        assertTrue(plan.diaryDelta?.innerThought?.contains("装得不在意") == true)
    }
}

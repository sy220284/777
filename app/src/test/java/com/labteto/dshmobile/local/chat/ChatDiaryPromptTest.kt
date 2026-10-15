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
    fun diaryPromptAllowsEventOnlyRecordsAndMarksUncertainPsychology() {
        val prompt = planner.prompt(
            persona = PersonaProfile(name = "阿青", portrait = "嘴硬心软，但不会为了证明这一点每轮都表演出来。"),
            state = ChatCharacterState(),
            userMessage = "周末一起去海边吧",
            assistantMessage = "……行，别迟到。",
        )

        val system = chatPostTurnModelMessages(prompt)
            .first()["content"]!!.jsonPrimitive.content
        assertTrue(system.contains("不写流水账"))
        assertTrue(system.contains("精炼成稿"))
        assertTrue(system.contains("完整的当前版本"))
        assertTrue(system.contains("心理活动"))
        assertTrue(system.contains("可以只记录 event"))
        assertTrue(system.contains("subjectiveEvidence"))
        assertTrue(system.contains("privateReflection"))
        assertTrue(system.contains("个人判断"))
        assertTrue(system.contains("保留不确定性"))
        assertFalse(system.contains("至少一项非空"))
        assertTrue(system.contains("PRIVATE"))
        assertTrue(system.contains("该信息只属于当前人物"))
        assertTrue(system.contains("其他人物的所知以实际公开事件为准"))
        assertTrue(system.contains("SHAREABLE"))
        assertTrue(system.contains("尚未在群聊公开"))
        assertTrue(system.contains("PUBLIC"))
        assertTrue(system.contains("明确说"))
        assertTrue(prompt.contains("diaryDelta"))

        val suggestionSystem = chatReplySuggestionModelMessages("生成回复建议")
            .first()["content"]!!.jsonPrimitive.content
        assertFalse(suggestionSystem.contains("diaryDelta"))
    }

    @Test
    fun threeDistinctPersonasKeepTheirOwnValuesInTheDiaryGenerationPrompt() {
        val personalities = listOf(
            "习惯隐忍，独处时会反复权衡是否信任别人",
            "外向爽快，遇到误会会先追问缘由",
            "理性克制，重视承诺也尊重边界",
        )
        val prompts = personalities.mapIndexed { index, identity ->
            planner.prompt(
                persona = PersonaProfile(name = "人物$index", coreIdentity = identity),
                state = ChatCharacterState(),
                userMessage = "周末去看电影吧",
                assistantMessage = "好，我记下了。",
            )
        }
        assertEquals(3, prompts.distinct().size)
        prompts.forEachIndexed { index, prompt ->
            assertTrue(prompt.contains(personalities[index]))
            personalities.forEachIndexed { other, description ->
                if (other != index) assertFalse(prompt.contains(description))
            }
            val instruction = chatPostTurnModelMessages(prompt)
                .first()["content"]!!.jsonPrimitive.content
            assertTrue(instruction.contains("privateReflection"))
            assertTrue(instruction.contains("角色说话方式取决于自身性格"))
        }
    }

    @Test
    fun standaloneThoughtfulDiaryPatchIsParsedButNoneIsNot() {
        val reflection = "我一直以为沉默能让我更安全。今天想起来，原来我也很在乎自己的感受和决定。"
        fun payload(significance: String, voice: String): String = """
            {
              "state": {},
              "turnSignificance": "$significance",
              "suggestions": [],
              "diaryDelta": {
                "event": "那次沉默",
                "privateReflection": "$voice",
                "importance": 2,
                "disclosure": "PRIVATE"
              }
            }
        """.trimIndent()
        val valid = planner.parse(
            payload("MINOR", reflection), ChatCharacterState(),
            "那次沉默让我想了很久", "我也许该学着说出自己的想法",
        )
        assertNotNull(valid)
        assertEquals(reflection, valid!!.diaryDelta?.privateReflection)
        assertEquals(null, planner.parse(
            payload("NONE", reflection), ChatCharacterState(),
            "那次沉默让我想了很久", "好",
        ))
        assertEquals(null, planner.parse(
            payload("MINOR", "好"), ChatCharacterState(),
            "那次沉默让我想了很久", "好",
        ))
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
                "privateReflection":"我嘴上说着没关系，心里却已经开始期待那一天。",
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
        assertTrue(plan.diaryDelta?.privateReflection?.contains("开始期待") == true)
    }
}

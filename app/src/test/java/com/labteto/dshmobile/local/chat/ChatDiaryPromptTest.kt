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
        assertTrue(system.contains("privateReflection"))
        assertTrue(system.contains("个人判断"))
        assertTrue(system.contains("主观联想"))
        assertTrue(system.contains("第一人称"))
        assertTrue(system.contains("长短、节奏"))
        assertTrue(system.contains("角色说话方式取决于自身性格"))
        assertTrue(system.contains("天马行空"))
        assertTrue(system.contains("有趣"))
        // The prompt is a creative direction, not an aesthetic form to fill out.
        assertFalse(system.contains("60～200"))
        assertFalse(system.contains("必须使用固定结构"))
        assertFalse(system.contains("必须使用“我觉得"))
        assertFalse(system.contains("至少一项非空"))
        assertTrue(system.contains("只记录 event"))
        assertTrue(system.contains("subjectiveEvidence"))
        assertTrue(system.contains("PRIVATE"))
        assertTrue(system.contains("该信息只属于当前人物"))
        assertTrue(system.contains("其他人物的所知以实际公开事件为准"))
        assertTrue(system.contains("SHAREABLE"))
        assertTrue(system.contains("尚未在群聊公开"))
        assertTrue(system.contains("PUBLIC"))
        assertTrue(system.contains("明确"))
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
    fun creativeWritingGuidanceHasNoRequiredMoodStructureOrFixedDiaryLength() {
        val instruction = CHAT_DIARY_SYSTEM_INSTRUCTION
        assertTrue(instruction.contains("灵感方向"))
        assertTrue(instruction.contains("俏皮"))
        assertTrue(instruction.contains("理智冷静"))
        assertTrue(instruction.contains("天马行空"))
        assertTrue(instruction.contains("碎碎念"))
        assertTrue(instruction.contains("轻轻一句"))
        assertTrue(instruction.contains("不要求情绪转折"))
        assertFalse(instruction.contains("约60～200"))
        assertFalse(instruction.contains("只能以"))
        assertFalse(instruction.contains("三段式"))
        // Memory safeguards stay separate from prose style.
        assertTrue(instruction.contains("不能把想象中的新事件"))
        assertTrue(instruction.contains("subjectiveEvidence"))
        assertTrue(instruction.contains("PRIVATE"))
        assertTrue(instruction.contains("PUBLIC"))
        assertTrue(instruction.contains("NONE 一律 diaryDelta=null"))
    }

    @Test
    fun samePersonCanCarryDifferentDailyMoodsIntoCreativeDiaryContext() {
        val persona = PersonaProfile(
            name = "阿青",
            coreIdentity = "细心又独立，偶尔会在心里开些荒唐的小玩笑",
        )
        val sunny = planner.prompt(
            persona = persona,
            state = ChatCharacterState(mood = "轻松兴奋"),
            userMessage = "今天终于把作业交了",
            assistantMessage = "总算能休息一下。",
        )
        val quiet = planner.prompt(
            persona = persona,
            state = ChatCharacterState(mood = "安静疲惫"),
            userMessage = "今天终于把作业交了",
            assistantMessage = "总算能休息一下。",
        )
        assertTrue(sunny.contains("轻松兴奋"))
        assertTrue(quiet.contains("安静疲惫"))
        assertTrue(sunny.contains(persona.coreIdentity))
        assertTrue(quiet.contains(persona.coreIdentity))
        assertFalse(sunny == quiet)
        val diaryInstruction = chatPostTurnModelMessages(sunny)
            .first()["content"]!!.jsonPrimitive.content
        assertTrue(diaryInstruction.contains("同一人物"))
        assertTrue(diaryInstruction.contains("当天的处境"))
    }

    @Test
    fun standaloneThoughtfulDiaryPatchIsParsedButNoneIsNot() {
        val reflection = "我一直以为沉默能让我更安全。今天想起来，原来我也很在乎自己的感受和决定。"
        fun payload(significance: String, voice: String): String = """
            {
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
    fun aSinglePlayfulSentenceCanBeAViableImportantDiaryPatch() {
        val patch = """
            {
              "turnSignificance": "MINOR",
              "diaryDelta": {
                "event": "说好了",
                "privateReflection": "我那双鞋估计已经开始蹦跶了！",
                "importance": 3
              }
            }
        """.trimIndent()
        val plan = planner.parse(
            patch, ChatCharacterState(),
            userMessage = "周末一起出去散步，说好了",
            assistantMessage = "说好了。",
        )
        assertNotNull(plan)
        assertEquals("我那双鞋估计已经开始蹦跶了！", plan!!.diaryDelta?.privateReflection)
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

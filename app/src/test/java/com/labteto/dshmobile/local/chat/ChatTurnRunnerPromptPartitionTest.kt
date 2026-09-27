package com.labteto.dshmobile.local.chat

import java.io.File
import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChatTurnRunnerPromptPartitionTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun stablePersonaFactsStaySeparateFromPerTurnState() {
        val runner = ChatTurnRunner(
            personaStore = ChatPersonaStore(File(temporary.root, "personas.json"), json),
            relationshipEngine = ChatRelationshipEngine(),
            loreEngine = CharacterLoreEngine(),
        )
        val persona = PersonaProfile(
            name = "阿青",
            identity = "剑客",
            personality = "嘴硬心软",
            hardConstraints = listOf("不替用户做决定"),
            bannedPhrases = listOf("作为AI"),
            corrections = listOf("不会主动解释系统规则"),
        )
        val state = ChatCharacterState(
            mood = "不高兴",
            relationshipState = "亲近",
            recentImpression = "还记得刚才的争执",
            scene = ChatSceneState(
                sceneTime = "夜晚",
                location = "院子",
                participants = listOf("用户", "阿青"),
                positions = listOf("两人坐在石桌旁"),
                activeActions = listOf("继续聊天"),
                lastSceneChange = "从屋内来到院子",
            ),
            continuity = ChatContinuityState(
                recurringEvents = listOf("多次表示继续留在院中，目前地点未变化"),
                decisions = listOf("明早九点出发"),
            ),
        )

        val context = runner.prepareProfile(
            persona = persona,
            state = state,
            userInput = "你还生气吗",
        )

        assertTrue(context.stablePrompt.contains("身份：剑客"))
        assertTrue(context.stablePrompt.contains("性格：嘴硬心软"))
        assertFalse(context.stablePrompt.contains("禁用表达"))
        assertFalse(context.stablePrompt.contains("对白参考"))
        assertFalse(context.stablePrompt.contains("【当前状态】"))
        assertFalse(context.stablePrompt.contains("用户纠正"))
        assertFalse(context.stablePrompt.contains("地点=院子"))

        assertTrue(context.dynamicPrompt.contains("【用户纠正｜最高优先】"))
        assertTrue(context.dynamicPrompt.contains("情绪=不高兴"))
        assertTrue(context.dynamicPrompt.contains("近期印象：还记得刚才的争执"))
        assertTrue(context.dynamicPrompt.contains("【当前场景｜硬连续性】"))
        assertTrue(context.dynamicPrompt.contains("地点=院子"))
        assertTrue(context.dynamicPrompt.contains("两人坐在石桌旁"))
        assertTrue(context.dynamicPrompt.contains("多次表示继续留在院中"))
        assertTrue(context.dynamicPrompt.contains("没有明确移动、时间推进或场景切换时"))
        assertTrue(context.prompt.contains(context.stablePrompt))
        assertTrue(context.prompt.contains(context.dynamicPrompt))
    }
}

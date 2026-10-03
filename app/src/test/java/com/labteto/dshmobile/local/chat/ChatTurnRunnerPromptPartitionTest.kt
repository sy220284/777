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
            portrait = "剑客，嘴硬心软",
            hardConstraints = listOf("不替用户做决定"),
            bannedPhrases = listOf("作为AI"),
            corrections = listOf(
                "不会主动解释系统规则",
                "嘴硬但会服软，不善于直接拒绝",
            ),
        )
        val state = ChatCharacterState(
            physicalState = "刚下班，有点累",
            mood = "不高兴",
            relationshipState = "亲近",
            recentImpression = "还记得刚才的争执",
            currentAgenda = "在收拾桌上的东西",
        )
        val shared = ChatContextState(
            scene = ChatSceneState(
                sceneTime = "夜晚",
                location = "院子",
                participants = listOf("用户", "阿青"),
                positions = listOf("两人坐在石桌旁"),
                activeActions = listOf("继续聊天"),
            ),
            continuity = ChatContinuityState(
                recentEvents = listOf("两人来到院中继续聊天"),
                decisions = listOf("明早九点出发"),
            ),
        )

        val context = runner.prepareProfile(
            persona = persona,
            state = state,
            context = shared,
            userInput = "你还生气吗",
        )

        assertTrue(context.stablePrompt.contains("剑客"))
        assertTrue(context.stablePrompt.contains("嘴硬心软"))
        assertFalse(context.stablePrompt.contains("禁用表达"))
        assertFalse(context.stablePrompt.contains("对白参考"))
        assertFalse(context.stablePrompt.contains("【当前状态】"))
        assertFalse(context.stablePrompt.contains("用户纠正"))
        assertFalse(context.stablePrompt.contains("地点=院子"))
        assertFalse(context.stablePrompt.contains("刚下班，有点累"))
        assertFalse(context.stablePrompt.contains("在收拾桌上的东西"))

        assertTrue(context.dynamicPrompt.contains("【此刻】"))
        assertTrue(context.dynamicPrompt.contains("身体：刚下班，有点累"))
        assertTrue(context.dynamicPrompt.contains("正在做：在收拾桌上的东西"))
        assertTrue(context.dynamicPrompt.contains("【本轮模式】"))
        assertTrue(context.dynamicPrompt.contains("不是台词模板"))
        assertTrue(context.dynamicPrompt.contains("用户明确纠正（硬约束）"))
        assertTrue(context.dynamicPrompt.contains("脑内推理可以比说出口完整"))
        assertFalse(context.dynamicPrompt.contains("【角色表达解释】"))
        assertTrue(context.dynamicPrompt.contains("情绪：不高兴"))
        assertTrue(context.dynamicPrompt.contains("你目前怎么看对方：还记得刚才的争执"))
        assertTrue(context.dynamicPrompt.contains("【当前场景｜硬连续性】"))
        assertTrue(context.dynamicPrompt.contains("地点=院子"))
        assertFalse(context.dynamicPrompt.contains("两人坐在石桌旁"))
        assertTrue(context.dynamicPrompt.contains("两人来到院中继续聊天"))
        assertTrue(context.dynamicPrompt.contains("人物位置、进行中动作和物件以最近原始对话为准"))
        assertTrue(context.dynamicPrompt.contains("若没有明确移动、时间推进或合理叙事跳切"))
        assertTrue(context.prompt.contains(context.stablePrompt))
        assertTrue(context.prompt.contains(context.dynamicPrompt))
    }

    @Test
    fun emptyDefaultPersonaUsesNeutralUnboundChatPrompt() {
        val runner = ChatTurnRunner(
            personaStore = ChatPersonaStore(File(temporary.root, "personas-unbound.json"), json),
            relationshipEngine = ChatRelationshipEngine(),
            loreEngine = CharacterLoreEngine(),
        )
        val context = runner.prepareProfile(
            persona = PersonaProfile(),
            state = ChatCharacterState(relationshipState = "熟悉中"),
            context = ChatContextState(),
            userInput = "你好",
        )

        assertTrue(PersonaProfile().isUnboundChatPersona())
        assertFalse(PersonaProfile(name = "阿青").isUnboundChatPersona())
        assertTrue(context.stablePrompt.contains("当前未选择人物角色"))
        assertFalse(context.stablePrompt.contains("【角色】默认角色"))
        assertFalse(context.dynamicPrompt.contains("关系=熟悉中"))
    }

    @Test
    fun genericRestraintStaysAFreeModeSignalInsteadOfHardcodedExpressionRecipe() {
        val runner = ChatTurnRunner(
            personaStore = ChatPersonaStore(File(temporary.root, "personas-restraint.json"), json),
            relationshipEngine = ChatRelationshipEngine(),
            loreEngine = CharacterLoreEngine(),
        )
        val context = runner.prepareProfile(
            persona = PersonaProfile(
                name = "阿青",
                portrait = "克制谨慎，表达含蓄",
            ),
            state = ChatCharacterState(),
            context = ChatContextState(),
            userInput = "你好",
        )

        assertFalse(context.dynamicPrompt.contains("【角色表达解释】"))
        assertTrue(context.dynamicPrompt.contains("【本轮模式】"))
        assertTrue(context.dynamicPrompt.contains("表达："))
    }

    @Test
    fun continuationInputIsInjectedIntoGenerationPrompt() {
        val runner = ChatTurnRunner(
            personaStore = ChatPersonaStore(File(temporary.root, "personas-continuation.json"), json),
            relationshipEngine = ChatRelationshipEngine(),
            loreEngine = CharacterLoreEngine(),
        )

        val context = runner.prepareProfile(
            persona = PersonaProfile(name = "阿青"),
            state = ChatCharacterState(),
            context = ChatContextState(scene = ChatSceneState(location = "院子")),
            userInput = "继续",
        )

        assertTrue(context.dynamicPrompt.contains("【继续】"))
        assertTrue(context.dynamicPrompt.contains("不无过渡跳时、换场或加人"))
    }

}

package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.model.LocalModelReply
import java.io.File
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
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
    fun expressionPreferenceMissesPreserveTheExactCompletedReply() = runTest {
        val runner = ChatTurnRunner(ChatPersonaStore(File(temporary.root, "preference.json"), json),
            ChatRelationshipEngine(), CharacterLoreEngine())
        val reply = LocalModelReply(message = JsonObject(emptyMap()),
            content = "亲爱的，你刚才问的我还没想好。", reasoning = null, toolCalls = emptyList())
        val events = mutableListOf<String>()
        val result = runner.finalizeReply(PersonaProfile(name = "阿青", bannedPhrases = listOf("亲爱的")),
            reply, recordUsage = {}, onGuardEvent = { action, _ -> events += action })
        assertSame(reply, result)
        assertEquals(listOf("preference-miss"), events)
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
            coreIdentity = "剑客，嘴硬心软",
            facts = listOf(CharacterFact("temperament", CharacterFactCategories.PERSONALITY,
                "嘴硬心软", provenance = CharacterFactProvenance.USER_CREATED)),
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
            currentUserImpression = "还记得刚才的争执",
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
        assertFalse(context.dynamicPrompt.contains("【本轮模式】"))
        assertTrue(context.stablePrompt.contains("人物修订资料"))
        assertTrue(context.stablePrompt.contains("嘴硬但会服软"))
        assertFalse(context.dynamicPrompt.contains("脑内推理"))
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
    fun authoredVoiceAndJudgmentRemainCompleteUnderLongBiography() {
        val runner = ChatTurnRunner(
            personaStore = ChatPersonaStore(File(temporary.root, "persona-voice-budget.json"), json),
            relationshipEngine = ChatRelationshipEngine(),
            loreEngine = CharacterLoreEngine(),
        )
        val voice = "话很少，但偶尔会用干巴巴的笑话掩饰担心。"
        val values = "宁可暂时争执，也不拿没有证据的推测当结论。"
        val persona = PersonaProfile(
            name = "阿青",
            coreIdentity = "独立的书店店主",
            facts = listOf(
                CharacterFact("voice-unique", CharacterFactCategories.VOICE_STYLE,
                    voice, provenance = CharacterFactProvenance.USER_CREATED),
                CharacterFact("values-unique", CharacterFactCategories.VALUES_AND_TRADEOFFS,
                    values, provenance = CharacterFactProvenance.USER_CREATED),
                CharacterFact("long-biography", CharacterFactCategories.BIOGRAPHY,
                    "在旧城区经营书店多年。".repeat(150),
                    provenance = CharacterFactProvenance.USER_CREATED),
            ),
        )
        val response = runner.prepareProfile(persona, userInput = "最近怎么样？")
        assertTrue(response.stablePrompt.contains(voice))
        assertTrue(response.stablePrompt.contains(values))
        assertFalse(response.stablePrompt.contains("【角色表达脚本】"))
    }

    @Test
    fun explicitTopicChangeDoesNotReinjectUnrelatedCurrentImpression() {
        val runner = ChatTurnRunner(
            personaStore = ChatPersonaStore(File(temporary.root, "persona-new-topic.json"), json),
            relationshipEngine = ChatRelationshipEngine(),
            loreEngine = CharacterLoreEngine(),
        )
        val persona = PersonaProfile(name = "阿青", coreIdentity = "喜欢研究天文的园丁")
        val state = ChatCharacterState(
            currentUserImpression = "他昨天答应赴约却失约，我还没想通",
        )
        val ordinary = runner.prepareProfile(persona, state = state,
            userInput = "你还在生气吗？")
        assertTrue(ordinary.dynamicPrompt.contains("你目前怎么看对方"))
        val redirected = runner.prepareProfile(persona, state = state,
            userInput = "先换个话题，聊聊天文吧")
        assertFalse(redirected.dynamicPrompt.contains("你目前怎么看对方"))
        assertTrue(redirected.stablePrompt.contains("喜欢研究天文"))
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
    fun personalityIsAvailableWithoutAReplyRecipe() {
        val runner = ChatTurnRunner(
            personaStore = ChatPersonaStore(File(temporary.root, "personas-restraint.json"), json),
            relationshipEngine = ChatRelationshipEngine(),
            loreEngine = CharacterLoreEngine(),
        )
        val context = runner.prepareProfile(
            persona = PersonaProfile(
                name = "阿青",
                coreIdentity = "旅人",
                facts = listOf(CharacterFact("temperament", CharacterFactCategories.PERSONALITY,
                    "克制谨慎，表达含蓄", provenance = CharacterFactProvenance.USER_CREATED)),
            ),
            state = ChatCharacterState(),
            context = ChatContextState(),
            userInput = "你好",
        )

        assertFalse(context.dynamicPrompt.contains("【角色表达解释】"))
        assertFalse(context.dynamicPrompt.contains("【本轮模式】"))
        assertTrue(context.stablePrompt.contains("克制谨慎，表达含蓄"))
    }

    @Test
    fun continuationKeepsSceneBackgroundWithoutAReactionDirective() {
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

        assertTrue(context.dynamicPrompt.contains("地点=院子"))
        assertFalse(context.dynamicPrompt.contains("只推进下一拍"))
    }

    @Test
    fun finalizePreservesTheCharactersStructureAndRepeatedWords() = runTest {
        val runner = ChatTurnRunner(
            ChatPersonaStore(File(temporary.root, "personas-finalize.json"), json),
            ChatRelationshipEngine(), CharacterLoreEngine(),
        )
        val content = "我理解你的感受。为什么？你想知道什么？要听我解释吗？\n" +
            "1. 我答应明天来。\n2. 我答应明天来。"
        val reply = LocalModelReply(
            message = JsonObject(emptyMap()), content = content, reasoning = null, toolCalls = emptyList(),
        )
        var usageRecords = 0
        val finalized = runner.finalizeReply(
            persona = PersonaProfile(name = "阿青"), reply = reply,
            recordUsage = { usageRecords++ }, recentAssistantReplies = listOf(content),
        )
        assertSame(reply, finalized)
        assertEquals(content, finalized.content)
        assertEquals(1, usageRecords)
    }

}

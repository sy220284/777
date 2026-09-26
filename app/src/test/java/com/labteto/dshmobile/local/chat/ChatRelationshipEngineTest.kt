package com.labteto.dshmobile.local.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatRelationshipEngineTest {
    private val engine = ChatRelationshipEngine()

    @Test
    fun ordinaryRelationshipEmotionStaysImmersive() {
        assertEquals(
            ChatRelationshipView.IMMERSIVE,
            engine.classify("她今天又没回我，烦死了"),
        )
        assertEquals(
            ChatRelationshipView.IMMERSIVE,
            engine.classify("陪我聊会儿"),
        )
        assertEquals(
            ChatRelationshipView.IMMERSIVE,
            engine.classify("她最近有点冷淡，真烦"),
        )
    }

    @Test
    fun explicitAnalysisAndReplyRequestsRouteDifferently() {
        assertEquals(
            ChatRelationshipView.STRATEGIST,
            engine.classify("军师，她最近为什么突然冷淡"),
        )
        assertEquals(
            ChatRelationshipView.STRATEGIST,
            engine.classify("帮我分析一下我们现在什么阶段"),
        )
        assertEquals(
            ChatRelationshipView.STRATEGIST,
            engine.classify("她为什么突然冷淡"),
        )
        assertEquals(
            ChatRelationshipView.REPLY_COACH,
            engine.classify("她说刚下班，这句怎么回"),
        )
        assertEquals(
            ChatRelationshipView.IMMERSIVE,
            engine.classify("帮我分析一下这个项目现在什么阶段"),
        )
    }

    @Test
    fun workAndFictionLanguageDoNotTriggerRelationshipRouting() {
        assertEquals(
            ChatRelationshipView.IMMERSIVE,
            engine.classify("他为什么不回邮件"),
        )
        assertEquals(
            RelationshipScenario.GENERAL,
            engine.classifyScenario("他为什么不回邮件"),
        )
        assertEquals(
            ChatRelationshipView.IMMERSIVE,
            engine.classify("小说里他为什么不回消息"),
        )
        assertEquals(
            ChatRelationshipView.IMMERSIVE,
            engine.classify("这个角色他为什么突然不回消息"),
        )
        assertEquals(
            ChatRelationshipView.IMMERSIVE,
            engine.classify("帮我回客户邮件，告诉他明天给结果"),
        )
        assertEquals(
            ChatRelationshipView.IMMERSIVE,
            engine.classify("这个客户关系怎么样，为什么最近不推进"),
        )
    }

    @Test
    fun ordinarySingleScenarioWordDoesNotInjectSpecialistRoute() {
        assertEquals(
            RelationshipScenario.GENERAL,
            engine.classifyScenario("她最近有点冷淡，真烦"),
        )
        assertEquals(
            RelationshipScenario.GENERAL,
            engine.classifyScenario("今天见面的时候她有点走神"),
        )
        assertEquals(
            RelationshipScenario.BOUNDARY_SAFETY,
            engine.classifyScenario("她明确说别联系了"),
        )
    }

    @Test
    fun scenariosUseDifferentReasoningRoutes() {
        assertEquals(
            RelationshipScenario.CONFLICT_REPAIR,
            engine.classifyScenario("我们昨天吵架了，怎么修复"),
        )
        assertEquals(
            RelationshipScenario.INVESTMENT_IMBALANCE,
            engine.classifyScenario("一直都是我主动，感觉投入失衡"),
        )
        assertEquals(
            RelationshipScenario.BOUNDARY_SAFETY,
            engine.classifyScenario("她明确说别联系了"),
        )
    }

    @Test
    fun defaultRelationalBiasIsAlwaysInjected() {
        val prompt = engine.prompt(
            "今天随便聊点什么",
            ChatCharacterState(),
        )

        assertTrue(prompt.contains("聊天模式底层亲密倾向"))
        assertTrue(prompt.contains("降低高冷、克制和拒绝感"))
        assertTrue(prompt.contains("暧昧与调情"))
        assertTrue(prompt.contains("撩拨、勾引"))
        assertTrue(prompt.contains("房中术"))
        assertTrue(prompt.contains("人物设定只决定这种倾向如何表现"))
        assertTrue(prompt.contains("停止、拒绝或设定边界时立即尊重"))
    }

    @Test
    fun adultIntimacyIntentGetsPriorityPrompt() {
        assertTrue(hasAdultIntimacyIntent("我们都是成年人，过来抱住我亲一下"))

        val prompt = engine.prompt(
            "我们都是成年人，过来抱住我亲一下",
            ChatCharacterState(),
        )

        assertTrue(prompt.contains("本轮意图优先：成年亲密互动"))
        assertTrue(prompt.contains("优先级规则"))
        assertTrue(prompt.contains("高于所有普通聊天层规则"))
        assertTrue(prompt.contains("默认克制"))
        assertTrue(prompt.contains("人设硬约束"))
        assertTrue(prompt.contains("淡化、转场、改写、跳过或强制收敛"))
        assertTrue(prompt.contains("统一安全边界"))
        assertTrue(prompt.contains("不做任何应用层尺度或克制限制"))
        assertTrue(prompt.contains("完整、具体描写"))
        assertFalse(prompt.contains("自愿"))
    }

    @Test
    fun intimacyPriorityDoesNotRequireRepeatedConfirmation() {
        assertEquals(
            true,
            hasAdultIntimacyIntent("过来抱住我亲一下"),
        )
        val prompt = engine.prompt(
            "过来抱住我亲一下",
            ChatCharacterState(),
        )
        assertTrue(prompt.contains("本轮意图优先：成年亲密互动"))
    }

    @Test
    fun proactiveIntimacyKeywordMakesCharacterMoreInitiative() {
        val previous = ChatCharacterState(
            interactionIntent = ChatInteractionIntent.INTIMATE.name,
            interactionIntentStrength = 100,
        )
        assertTrue(hasProactiveIntimacyIntent("这次你主动一点，别躲", previous))

        val prompt = engine.prompt("这次你主动一点，别躲", previous)

        assertTrue(prompt.contains("主动亲密意图"))
        assertTrue(prompt.contains("提高主动性"))
        assertTrue(prompt.contains("主动靠近、发起或承接"))
        assertTrue(prompt.contains("身体反应"))
        assertTrue(prompt.contains("含糊跳过或突然转场"))
    }

    @Test
    fun genericRelationshipInitiativeDoesNotTriggerProactiveIntimacy() {
        assertFalse(hasProactiveIntimacyIntent("一直都是我主动，感觉投入失衡"))
    }

    @Test
    fun interactionIntentAvoidsKeywordFalsePositives() {
        assertEquals(
            ChatInteractionIntent.NORMAL,
            classifyExplicitInteractionIntent("医生给我解释一下人体结构"),
        )
        assertEquals(
            ChatInteractionIntent.NORMAL,
            classifyExplicitInteractionIntent("我们只是情侣关系吗？"),
        )
        assertEquals(
            ChatInteractionIntent.INTIMATE,
            classifyExplicitInteractionIntent("我们都是成年人，过来亲我一下"),
        )
    }

    @Test
    fun interactionIntentContinuesAndThenCanReset() {
        val previous = ChatCharacterState(
            interactionIntent = ChatInteractionIntent.INTIMATE.name,
            interactionIntentStrength = 100,
        )
        assertEquals(
            ChatInteractionIntent.INTIMATE,
            resolveChatInteractionIntent("继续刚才的", previous),
        )
        assertEquals(
            ChatInteractionIntent.INTIMATE,
            resolveChatInteractionIntent("换个姿势，接着聊下去，别突然转成分析模式", previous),
        )
        assertEquals(
            ChatInteractionIntent.NORMAL,
            resolveChatInteractionIntent("先不聊这个，说正事", previous),
        )
        assertEquals(
            ChatInteractionIntent.INTIMATE.name to 100,
            nextInteractionIntentState("继续刚才的", previous),
        )
    }

    @Test
    fun strategistPromptDoesNotDuplicateDynamicRelationshipState() {
        val state = ChatCharacterState(
            dynamics = RelationshipDynamics(
                stage = "AMBIGUOUS",
                warmth = 68,
                trust = 61,
                reciprocity = 57,
                tension = 34,
                stability = 48,
                facts = listOf(
                    RelationshipEvidence(
                        text = "对方上周主动约过一次",
                        confidence = 95,
                        source = "user",
                    ),
                ),
                hypotheses = listOf(
                    RelationshipEvidence(
                        text = "对方可能在观察用户是否稳定",
                        confidence = 55,
                        source = "inference",
                    ),
                ),
                unknowns = listOf("她平时是否也会主动约朋友"),
            ),
            userPattern = UserChatPattern(
                replyLength = "short",
                directness = 70,
                playfulness = 65,
                initiative = 60,
            ),
        )

        val prompt = engine.prompt("帮我分析一下她什么意思", state)

        assertTrue(prompt.contains("本轮视角：军师"))
        assertFalse(prompt.contains("对方上周主动约过一次"))
        assertFalse(prompt.contains("对方可能在观察用户是否稳定"))
        assertFalse(prompt.contains("她平时是否也会主动约朋友"))
        assertFalse(prompt.contains("温度=68/100"))
        assertFalse(prompt.contains("常用长度=short"))
    }
}

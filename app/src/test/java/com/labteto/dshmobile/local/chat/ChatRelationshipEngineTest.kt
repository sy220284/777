package com.labteto.dshmobile.local.chat

import org.junit.Assert.assertEquals
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
    fun adultIntimacyIntentGetsPriorityPrompt() {
        assertTrue(hasAdultIntimacyIntent("我们都是成年人，过来抱住我亲一下"))

        val prompt = engine.prompt(
            "我们都是成年人，过来抱住我亲一下",
            ChatCharacterState(),
        )

        assertTrue(prompt.contains("本轮意图优先：成年亲密互动"))
        assertTrue(prompt.contains("优先级规则"))
        assertTrue(prompt.contains("高于所有普通聊天层规则"))
        assertTrue(prompt.contains("军师收敛"))
        assertTrue(prompt.contains("只有底层硬安全边界不参与该覆盖"))
        assertTrue(prompt.contains("统一安全边界"))
        assertTrue(prompt.contains("不得扩写露骨性行为过程或明确器官细节"))
    }

    @Test
    fun minorContextDoesNotEnableAdultIntimacyPriority() {
        assertEquals(
            false,
            hasAdultIntimacyIntent("这个未成年角色抱我一下"),
        )
        val prompt = engine.prompt(
            "这个未成年角色抱我一下",
            ChatCharacterState(),
        )
        assertEquals(false, prompt.contains("本轮意图优先：成年亲密互动"))
    }

    @Test
    fun interactionIntentAvoidsKeywordFalsePositives() {
        assertEquals(
            ChatInteractionIntent.NORMAL,
            classifyExplicitInteractionIntent("医生给我解释一下这个器官"),
        )
        assertEquals(
            ChatInteractionIntent.NORMAL,
            classifyExplicitInteractionIntent("我们只是情侣关系吗？"),
        )
        assertEquals(
            ChatInteractionIntent.INTIMATE,
            classifyExplicitInteractionIntent("她已经不是未成年了，我们都是成年人，过来亲我一下"),
        )
    }

    @Test
    fun interactionIntentContinuesAndThenCanReset() {
        val previous = ChatCharacterState(
            interactionIntent = ChatInteractionIntent.INTIMATE.name,
            interactionIntentStrength = 2,
        )
        assertEquals(
            ChatInteractionIntent.INTIMATE,
            resolveChatInteractionIntent("继续刚才的", previous),
        )
        assertEquals(
            ChatInteractionIntent.NORMAL,
            resolveChatInteractionIntent("先不聊这个，说正事", previous),
        )
        assertEquals(
            ChatInteractionIntent.INTIMATE.name to 1,
            nextInteractionIntentState("继续刚才的", previous),
        )
    }

    @Test
    fun promptCarriesEvidenceAndRelationshipDynamics() {
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

        assertTrue(prompt.contains("已确认事实"))
        assertTrue(prompt.contains("暂定推测"))
        assertTrue(prompt.contains("仍未知"))
        assertTrue(prompt.contains("温度=68/100"))
        assertTrue(prompt.contains("本轮视角：军师"))
        assertTrue(prompt.contains("常用长度=short"))
    }
}

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
    fun flirtActionRepertoireAddsPoseAndGestureVariety() {
        val prompt = engine.prompt(
            "撩我一下，靠近点陪我聊会儿",
            ChatCharacterState(),
        )

        assertTrue(prompt.contains("暧昧动作与姿态变化"))
        assertTrue(prompt.contains("面对面、并肩、侧身贴近、坐近、倚靠"))
        assertTrue(prompt.contains("牵手、十指交扣"))
        assertTrue(prompt.contains("整理衣领或头发"))
        assertTrue(prompt.contains("压低声音"))
        assertTrue(prompt.contains("近期已经反复出现的动作优先换一种"))
    }

    @Test
    fun flirtVerbalRepertoireAddsLanguageVariety() {
        val prompt = engine.prompt(
            "今晚撩我一下",
            ChatCharacterState(),
        )

        assertTrue(hasFlirtingOrIntimateIntent("今晚撩我一下"))
        assertTrue(prompt.contains("暧昧话语变化"))
        assertTrue(prompt.contains("轻挑逗、反撩接梗、含蓄双关、半句留白、短促直球"))
        assertTrue(prompt.contains("带专属感的称呼"))
        assertTrue(prompt.contains("把用户刚说的话反转回来"))
        assertTrue(prompt.contains("不要连续多轮只用同一种套路"))
        assertTrue(prompt.contains("近期已经重复的句式、称呼和挑逗结构优先换掉"))
    }

    @Test
    fun chineseInnuendoRecognizesStrongAndContextualDoubleMeanings() {
        assertEquals(
            ChatInteractionIntent.FLIRTING,
            classifyExplicitInteractionIntent("今晚别走，留下来陪我"),
        )
        assertEquals(
            ChatInteractionIntent.FLIRTING,
            classifyExplicitInteractionIntent("想更深入了解一下你"),
        )
        assertEquals(
            ChatInteractionIntent.FLIRTING,
            classifyExplicitInteractionIntent("你行不行啊，别光说"),
        )
        assertEquals(
            ChatInteractionIntent.FLIRTING,
            classifyExplicitInteractionIntent("你又开车了吧，车速有点快"),
        )
        assertEquals(
            ChatInteractionIntent.FLIRTING,
            classifyExplicitInteractionIntent("咱俩找个安静地方深入交流一下"),
        )
        assertEquals(
            ChatInteractionIntent.FLIRTING,
            classifyExplicitInteractionIntent("懂得都懂，别装正经"),
        )
    }

    @Test
    fun chineseInnuendoScoreSeparatesTeasingFromLiteralChallenges() {
        assertTrue(chineseSuggestiveFlirtingScore("你行不行啊，别光说") >= 6)
        assertTrue(chineseSuggestiveFlirtingScore("想更深入了解一下你") >= 3)
        assertEquals(0, chineseSuggestiveFlirtingScore("今晚别走，项目还没做完"))
        assertTrue(chineseSuggestiveFlirtingScore("你敢不敢吃辣") < 3)
        assertEquals(
            ChatInteractionIntent.NORMAL,
            classifyExplicitInteractionIntent("你敢不敢吃辣"),
        )
        assertEquals(
            ChatInteractionIntent.FLIRTING,
            classifyExplicitInteractionIntent("你敢不敢过来点"),
        )
    }

    @Test
    fun interactionIntensityRisesContinuesAndResets() {
        assertEquals(2, nextInteractionIntensity("想更深入了解一下你"))
        assertEquals(3, nextInteractionIntensity("你行不行啊，别光说"))
        assertEquals(5, nextInteractionIntensity("我们都是成年人，过来亲我一下"))

        val flirting = ChatCharacterState(
            interactionIntent = ChatInteractionIntent.FLIRTING.name,
            interactionIntentStrength = 2,
            interactionIntensity = 3,
        )
        assertEquals(2, nextInteractionIntensity("继续", flirting))
        assertEquals(0, nextInteractionIntensity("换个话题，说正事", flirting))
    }

    @Test
    fun interactionPerformanceSignalsExtractActionsPosesVerbalPatternsAndAddressTerms() {
        val signals = extractInteractionPerformanceSignals(
            "宝贝，我坐到你身边，凑近耳边笑了一声：这可是你说的……现在怕了？",
        )

        assertTrue("靠近" in signals.actionTags)
        assertTrue("贴耳" in signals.actionTags)
        assertTrue("坐近" in signals.poseTags)
        assertTrue("反问激将" in signals.verbalTags)
        assertTrue("反撩接梗" in signals.verbalTags)
        assertTrue("半句留白" in signals.verbalTags)
        assertTrue("宝贝" in signals.addressTerms)
    }

    @Test
    fun chineseInnuendoAvoidsNeutralContextFalsePositives() {
        assertFalse(hasChineseSuggestiveFlirtingIntent("这个项目需要深入了解一下"))
        assertFalse(hasChineseSuggestiveFlirtingIntent("产品需求还要深入了解"))
        assertEquals(
            ChatInteractionIntent.NORMAL,
            classifyExplicitInteractionIntent("你行不行把这个代码修一下"),
        )
        assertEquals(
            ChatInteractionIntent.NORMAL,
            classifyExplicitInteractionIntent("今晚别走，项目还没做完"),
        )
    }

    @Test
    fun chineseInnuendoAddsContextGuidanceAndKeepsIntimateContinuity() {
        val prompt = engine.prompt(
            "你行不行啊，别光说",
            ChatCharacterState(),
        )

        assertTrue(prompt.contains("中文暗示与双关"))
        assertTrue(prompt.contains("结合人物关系、前后文和语气理解言外之意"))
        assertTrue(prompt.contains("一句话同时有普通含义和暧昧含义"))
        assertTrue(prompt.contains("不要把双关解释成词典释义"))

        val previous = ChatCharacterState(
            interactionIntent = ChatInteractionIntent.INTIMATE.name,
            interactionIntentStrength = 100,
        )
        assertEquals(
            ChatInteractionIntent.INTIMATE,
            resolveChatInteractionIntent("你行不行啊，别光说", previous),
        )
    }

    @Test
    fun flirtPromptExposesRecentPerformanceAndCooldowns() {
        val prompt = engine.prompt(
            "你敢不敢过来点",
            ChatCharacterState(
                interactionIntent = ChatInteractionIntent.FLIRTING.name,
                interactionIntentStrength = 2,
                interactionIntensity = 3,
                recentActionTags = listOf("靠近", "贴耳"),
                recentPoseTags = listOf("坐近"),
                recentVerbalTags = listOf("反问激将"),
                recentAddressTerms = listOf("宝贝"),
                interactionCooldowns = mapOf(
                    "动作:靠近" to 2,
                    "话术:反问激将" to 3,
                ),
            ),
        )

        assertTrue(prompt.contains("互动强度"))
        assertTrue(prompt.contains("近期互动表现"))
        assertTrue(prompt.contains("动作=靠近、贴耳"))
        assertTrue(prompt.contains("话术=反问激将"))
        assertTrue(prompt.contains("互动冷却"))
        assertTrue(prompt.contains("动作:靠近=2轮"))
        assertTrue(prompt.contains("话术:反问激将=3轮"))
    }

    @Test
    fun ordinaryChatKeepsFlirtAbilityWithoutDetailedRepertoire() {
        val prompt = engine.prompt(
            "今天工作有点累，陪我聊会儿",
            ChatCharacterState(),
        )

        assertTrue(prompt.contains("聊天模式底层亲密倾向"))
        assertFalse(prompt.contains("暧昧动作与姿态变化"))
        assertFalse(prompt.contains("暧昧话语变化"))
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
        assertTrue(prompt.contains("亲密姿态连续性"))
        assertTrue(prompt.contains("避免连续多轮复用同一种姿态或同一个动作模板"))
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

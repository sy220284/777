package com.labteto.dshmobile.local.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatRelationshipEngineTest {
    private val engine = ChatRelationshipEngine()



    @Test
    fun retiredStrategistStateInStoredConversationFallsBackToNormal() {
        val oldState = ChatCharacterState(
            interactionIntent = "STRATEGIST",
            interactionIntentStrength = 1,
        )
        assertEquals(
            ChatInteractionIntent.NORMAL,
            resolveChatInteractionIntent("她什么意思", oldState),
        )
        assertEquals(
            ChatInteractionIntent.NORMAL.name to 0,
            nextInteractionIntentState("她什么意思", oldState),
        )
        assertEquals(
            engine.prompt("她什么意思", oldState),
            engine.prompt("/军师 她什么意思", oldState),
        )
    }


    @Test
    fun scenarioKeywordsDoNotChooseTheCharactersAttitude() {
        val background = engine.prompt("你好", ChatCharacterState())
        listOf("她最近有点冷淡，真烦", "今天见面时她有点走神", "她明确说别联系了").forEach {
            assertEquals(background, engine.prompt(it, ChatCharacterState()))
        }
    }

    @Test
    fun conflictsAndImbalanceDoNotInstallOneSharedReasoningRoute() {
        val background = engine.prompt("你好", ChatCharacterState())
        listOf("我们昨天吵架了，怎么修复", "一直都是我主动，感觉投入失衡", "我们复合吧").forEach {
            assertEquals(background, engine.prompt(it, ChatCharacterState()))
        }
    }

    @Test
    fun ordinaryRelationshipQuestionsReceiveBackgroundWithoutASharedAttitude() {
        val state = ChatCharacterState(relationshipState = "亲近",
            dynamics = RelationshipDynamics(unresolvedConflict = "昨天的失约"))
        val prompt = engine.prompt("你是不是不在乎我", state)
        assertTrue(prompt.contains("当前关系：亲近"))
        assertTrue(prompt.contains("昨天的失约"))
        assertFalse(prompt.contains("积极承接"))
        assertFalse(prompt.contains("军师"))
    }

    @Test
    fun flirtingRestoresActionRepertoire() {
        val prompt = engine.prompt("靠近点，陪我聊会", ChatCharacterState())
        assertTrue(prompt.contains("互动变化"))
        assertTrue(prompt.contains("互动强度"))
    }

    @Test
    fun requestGuidanceChangesWithoutRewritingRelationshipFacts() {
        val state = ChatCharacterState(relationshipState = "亲近")
        val normal = engine.prompt("今天怎么样", state)
        val flirting = engine.prompt("今晚撩我一下", state)
        assertTrue(normal.contains("当前关系：亲近"))
        assertTrue(flirting.contains("当前关系：亲近"))
        assertFalse(normal.contains("互动变化"))
        assertTrue(flirting.contains("互动变化"))
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
        assertEquals(4, nextInteractionIntensity("你行不行啊，别光说"))
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
    fun chineseInnuendoRestoresContextGuidance() {
        assertTrue(hasChineseSuggestiveFlirtingIntent("你行不行啊，别光说"))
        val prompt = engine.prompt("你行不行啊，别光说", ChatCharacterState())
        assertTrue(prompt.contains("【中文双关】"))
        assertTrue(prompt.contains("言外之意"))
    }

    @Test
    fun recentInteractionTagsRestoreVariationWithoutRetiredCooldowns() {
        val state = ChatCharacterState(interactionIntensity = 5, recentActionTags = listOf("靠近"),
            recentPoseTags = listOf("坐近"), interactionCooldowns = mapOf("动作:靠近" to 3))
        val prompt = engine.prompt("今晚撩我一下", state)
        assertTrue(prompt.contains("动作=靠近"))
        assertTrue(prompt.contains("姿态=坐近"))
        assertFalse(prompt.contains("【冷却】"))
    }

    @Test
    fun relationshipFactsOnlyAppearWithEvidenceAndCommandsDoNotRoute() {
        val empty = ChatCharacterState()
        assertTrue(engine.prompt("今天怎么样", empty).contains("【互动倾向】"))
        assertEquals(engine.prompt("今天怎么样", empty), engine.prompt("/reply", empty))
        val recorded = ChatCharacterState(dynamics = RelationshipDynamics(
            facts = listOf(RelationshipEvidence("师父答应下周带我练剑", confidence = 95, source = "dialogue")),
            sharedObjects = listOf("一起修好的木剑"),
        ))
        val prompt = engine.prompt("今天做什么", recorded)
        assertTrue(prompt.contains("【人物关系与共同经历】"))
        assertTrue(prompt.contains("师父答应下周带我练剑"))
        assertTrue(prompt.contains("一起修好的木剑"))
        assertEquals(prompt, engine.prompt("/reply", recorded))
    }

    @Test
    fun ordinaryChatRestoresDefaultRelationalBias() {
        val prompt = engine.prompt("今天工作有点累", ChatCharacterState())
        assertTrue(prompt.contains("【互动倾向】"))
        assertFalse(prompt.contains("【本轮意图优先：成年亲密互动】"))
        assertFalse(prompt.contains("【互动变化】"))
    }

    @Test
    fun adultIntimacyRestoresExplicitPriorityAndUnifiedBoundary() {
        val prompt = engine.prompt("我们都是成年人，过来抱住我亲一下", ChatCharacterState())
        assertTrue(prompt.contains("【本轮意图优先：成年亲密互动】"))
        assertTrue(prompt.contains("优先级规则"))
        assertTrue(prompt.contains("【统一安全边界】"))
        assertTrue(prompt.contains("仅限成年角色"))
    }

    @Test
    fun adultIntimacyRestoresPoseContinuity() {
        val prompt = engine.prompt("过来抱住我亲一下", ChatCharacterState())
        assertTrue(prompt.contains("【亲密姿态连续性】"))
        assertTrue(prompt.contains("避免连续多轮复用"))
    }

    @Test
    fun existingIntimacyRestoresProactiveGuidanceOnRequest() {
        val state = ChatCharacterState(interactionIntent = ChatInteractionIntent.INTIMATE.name,
            interactionIntentStrength = 100)
        val prompt = engine.prompt("这次你主动一点，别躲", state)
        assertTrue(prompt.contains("【主动亲密意图】"))
        assertTrue(prompt.contains("【统一安全边界】"))
    }

    @Test
    fun explicitRefusalDoesNotBecomeIntimateIntent() {
        assertEquals(
            ChatInteractionIntent.NORMAL,
            classifyExplicitInteractionIntent("别抱我"),
        )
        assertEquals(
            ChatInteractionIntent.NORMAL,
            classifyExplicitInteractionIntent("不要亲我"),
        )

        val previous = ChatCharacterState(
            interactionIntent = ChatInteractionIntent.INTIMATE.name,
            interactionIntentStrength = 100,
            interactionIntensity = 5,
        )
        assertEquals(
            ChatInteractionIntent.NORMAL,
            resolveChatInteractionIntent("别碰我，停下来", previous),
        )
        assertEquals(0, nextInteractionIntensity("别碰我，停下来", previous))
    }

    @Test
    fun interactionResetWordDoesNotMatchInsideUnrelatedText() {
        val previous = ChatCharacterState(
            interactionIntent = ChatInteractionIntent.FLIRTING.name,
            interactionIntentStrength = 2,
            interactionIntensity = 3,
        )

        assertFalse(isInteractionResetIntent("预算了三万，接着聊"))
        assertTrue(isInteractionResetIntent("算了，换个话题"))
        assertEquals(
            ChatInteractionIntent.FLIRTING,
            resolveChatInteractionIntent("继续，预算了三万", previous),
        )
    }

    @Test
    fun intimateContextDoesNotStickToUnrelatedNewTopic() {
        val previous = ChatCharacterState(
            interactionIntent = ChatInteractionIntent.INTIMATE.name,
            interactionIntentStrength = 100,
            interactionIntensity = 5,
        )

        assertEquals(
            ChatInteractionIntent.NORMAL,
            resolveChatInteractionIntent("今天天气怎么样", previous),
        )
        assertEquals(
            ChatInteractionIntent.NORMAL.name to 0,
            nextInteractionIntentState("今天天气怎么样", previous),
        )
        assertEquals(0, nextInteractionIntensity("今天天气怎么样", previous))
    }

    @Test
    fun flirtingContinuationExpiresInsteadOfStickingForever() {
        val previous = ChatCharacterState(
            interactionIntent = ChatInteractionIntent.FLIRTING.name,
            interactionIntentStrength = 1,
            interactionIntensity = 2,
        )

        assertEquals(
            ChatInteractionIntent.NORMAL.name to 0,
            nextInteractionIntentState("继续", previous),
        )
    }

    @Test
    fun genericRelationshipInitiativeDoesNotTriggerProactiveIntimacy() {
        assertFalse(hasProactiveIntimacyIntent("一直都是我主动，感觉投入失衡"))
    }

    @Test
    fun analysisOfQuotedIntimacyDoesNotBecomeAnIntimateActionRequest() {
        listOf(
            "她说亲我一下是什么意思？",
            "帮我分析一下她说亲我一下",
            "分析一下这句过来抱住我",
            "她让我亲她一下，这该怎么判断？",
        ).forEach { input ->
            assertEquals(ChatInteractionIntent.NORMAL, classifyExplicitInteractionIntent(input))
            assertEquals(0, nextInteractionIntensity(input))
        }
        assertEquals(
            ChatInteractionIntent.INTIMATE,
            classifyExplicitInteractionIntent("别分析了，过来亲我一下"),
        )
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
    fun formerStrategistCommandDoesNotAddInstructionsOrDiscardRelationshipFacts() {
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

        val prompt = engine.prompt("/strategy 她什么意思", state)

        assertEquals(engine.prompt("她什么意思", state), prompt)
        assertFalse(prompt.contains("用户显式调用关系分析"))
        assertEquals(ChatInteractionIntent.NORMAL, classifyExplicitInteractionIntent("帮我分析"))
        assertEquals(ChatInteractionIntent.NORMAL, classifyExplicitInteractionIntent("什么意思"))
        assertTrue(prompt.contains("对方上周主动约过一次"))
        assertTrue(prompt.contains("对方可能在观察用户是否稳定"))
        assertTrue(prompt.contains("她平时是否也会主动约朋友"))
        assertFalse(prompt.contains("温度=68/100"))
        assertFalse(prompt.contains("常用长度=short"))
    }
}

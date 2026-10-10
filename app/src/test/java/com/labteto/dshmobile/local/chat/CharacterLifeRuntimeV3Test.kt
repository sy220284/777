package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.model.estimateModelTokens
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CharacterLifeRuntimeV3Test {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun durableUserImpressionDoesNotExpireByTurnCount() {
        val planner = ChatInteractionPlanner(json)
        val persona = PersonaProfile(name = "阿青")
        var state = ChatCharacterState(
            recentImpression = "用户最近很忙",
            currentUserImpression = "用户最近很忙",
        )

        repeat(12) {
            state = planner.parse(
                text = """{"state":{},"suggestions":[],"turnSignificance":"MINOR","diaryDelta":null}""",
                previous = state,
                userMessage = "嗯，继续聊",
                assistantMessage = "好。",
                persona = persona,
            )!!.state
        }

        assertEquals("用户最近很忙", state.currentUserImpression)
        assertTrue(state.recentImpression.isBlank())
    }

    @Test
    fun lifeRuntimeAdvancesWithElapsedTimeWithoutInventingNewFacts() {
        val previousZone = java.util.TimeZone.getDefault()
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"))
        try {
            val persona = PersonaProfile(
                name = "叶澜",
                lifeContext = "白天在出版社工作；晚上会整理插画稿；周末会去看外婆。",
            )
            val first = advanceCharacterLife(
                persona = persona,
                state = ChatCharacterState(currentAgenda = "手里还有一版封面没交"),
                now = 12L * 60L * 60L * 1_000L,
            )
            val second = advanceCharacterLife(
                persona = persona,
                state = ChatCharacterState(
                    currentAgenda = "手里还有一版封面没交",
                    lifeState = first,
                ),
                now = 18L * 60L * 60L * 1_000L,
            )

            assertNotEquals(first.currentBeat, second.currentBeat)
            assertTrue(second.activeEvents.any { it.summary.contains("封面") })
            assertTrue(second.activeEvents.all { it.summary in persona.lifeContext || !it.source.equals("persona") })
        } finally {
            java.util.TimeZone.setDefault(previousZone)
        }
    }

    @Test
    fun attentionAndModePreferCharacterSpecificFocusWithoutLockingReplyShape() {
        val persona = PersonaProfile(
            name = "叶澜",
            portrait = "做插画，思路灵动，偶尔会从一个细节联想到别处。",
            attentionBiases = listOf("画画和构图的细节"),
            perceptionBlindSpots = listOf("容易忽略别人拐弯表达的情绪"),
        )
        val state = ChatCharacterState(
            physicalState = "刚下班，很累",
            lifeState = CharacterLifeState(currentBeat = "晚上会整理插画稿"),
        )
        val input = "我妈刚才又催我回家，不过我今天画了一张新的构图，线条终于顺了。"
        val attention = resolveCharacterAttention(persona, state, input)
        val mode = resolveCharacterMode(persona, state, input, attention)

        assertTrue(attention.noticed.any { it.contains("画") || it.contains("构图") || it.contains("线条") })
        assertTrue(mode.focus.isNotEmpty())
        assertTrue(mode.vector.association > 50)
        assertTrue(renderCharacterModePrompt(mode).contains("不是台词模板"))
    }

    @Test
    fun mutableTraitsChangeSlowlyAndRemainBoundedAcrossLongRuns() {
        val persona = PersonaProfile(
            name = "陈拾",
            mutableTraits = listOf("愿意求助"),
        )
        var state = ChatCharacterState(initiative = 70, shareDesire = 65)

        repeat(250) {
            val current = state.copy(
                initiative = 70,
                shareDesire = 65,
                currentUserImpression = "现在更愿意在需要时开口求助",
            )
            val evolution = evolveCharacterEvolution(
                persona = persona,
                previous = state,
                current = current,
                significance = "MINOR",
                userMessage = "需要的时候可以求助，不用总自己扛。",
                assistantMessage = "这次我愿意求助。",
            )
            state = current.copy(evolution = evolution)
        }

        val repeated = state.evolution.traitStates.getValue("愿意求助")
        assertEquals(1, repeated.evidenceCount)
        assertEquals(50, repeated.currentWeight)

        listOf(
            "今天我愿意求助，请你帮我看看。",
            "这次遇到麻烦，我会主动求助。",
            "我发现愿意求助其实没有那么难。",
        ).forEach { assistant ->
            val current = state.copy(initiative = 70, shareDesire = 65)
            val evolution = evolveCharacterEvolution(
                persona = persona,
                previous = state,
                current = current,
                significance = "MINOR",
                userMessage = "需要帮忙就说。",
                assistantMessage = assistant,
            )
            state = current.copy(evolution = evolution)
        }

        val trait = state.evolution.traitStates.getValue("愿意求助")
        assertEquals(4, trait.evidenceCount)
        assertTrue(trait.currentWeight > trait.baseline)
        assertTrue(trait.currentWeight in 20..80)
        assertTrue(state.evolution.initiativeBaseline in 0..100)
        assertTrue(state.evolution.opennessBaseline in 0..100)
        assertTrue(state.evolution.securityBaseline in 0..100)
    }

    @Test
    fun unrelatedLongMessageDoesNotManufactureBlindSpot() {
        val persona = PersonaProfile(
            name = "叶澜",
            perceptionBlindSpots = listOf("容易忽略别人拐弯表达的情绪"),
        )
        val input = "今天主要在整理项目代码和测试结果，顺便把几个文件名统一了一遍。".repeat(4)

        val attention = resolveCharacterAttention(persona, ChatCharacterState(), input)

        assertTrue(attention.possibleBlindSpot.isBlank())
    }

    @Test
    fun busyStateCompressesSmallTalkButCriticalQuestionRestoresCoverage() {
        val persona = PersonaProfile(name = "陈拾")
        val state = ChatCharacterState(physicalState = "刚下班，很累")
        val casualInput = "今天路上人好多"
        val questionInput = "你为什么没回我刚才的问题？"
        val casual = resolveCharacterMode(
            persona, state, casualInput, resolveCharacterAttention(persona, state, casualInput),
        )
        val question = resolveCharacterMode(
            persona, state, questionInput, resolveCharacterAttention(persona, state, questionInput),
        )

        assertTrue(casual.vector.compression > question.vector.compression)
        assertTrue(question.vector.coverage > casual.vector.coverage)
        assertTrue(question.vector.analysis > casual.vector.analysis)
        assertTrue(question.criticalInput)
    }

    @Test
    fun sameCharacterCanMoveBetweenLooseAndFocusedModesByContext() {
        val persona = PersonaProfile(
            name = "阿青",
            portrait = "灵动爱开玩笑，但遇到正事会认真。",
            voiceSamples = listOf("等下，我突然想到个东西。", "你先说重点。"),
        )
        val state = ChatCharacterState()
        val casualText = "我刚看到一只猫追着塑料袋跑，笑死"
        val factualText = "你必须告诉我，明天几点出发？"
        val casual = resolveCharacterMode(
            persona, state, casualText, resolveCharacterAttention(persona, state, casualText),
        )
        val factual = resolveCharacterMode(
            persona, state, factualText, resolveCharacterAttention(persona, state, factualText),
        )

        assertTrue(casual.vector.association > factual.vector.association)
        assertTrue(casual.vector.playfulness > factual.vector.playfulness)
        assertTrue(casual.vector.freedom > factual.vector.freedom)
        assertTrue(factual.vector.coverage > casual.vector.coverage)
    }

    @Test
    fun gamePersonaKeepsSourceTimelineAndWorldInStablePrompt() {
        val persona = PersonaProfile(
            name = "神里绫华",
            portrait = "神里家的大小姐，负责社奉行事务。",
            franchise = "原神",
            timelinePosition = "稻妻已与旅行者相识",
            worldSetting = "提瓦特大陆的稻妻，社奉行承担文化礼仪事务。",
        )
        val projection = CharacterRuntimeProjector(
            relationshipEngine = ChatRelationshipEngine(),
            loreEngine = CharacterLoreEngine(),
        ).project(
            persona = persona,
            state = ChatCharacterState(),
            context = ChatContextState(),
            userInput = "早上好",
            storyContext = null,
        )
        assertTrue(projection.stablePrompt.contains("原作来源：原神"))
        assertTrue(projection.stablePrompt.contains("当前剧情阶段：稻妻已与旅行者相识"))
        assertTrue(projection.stablePrompt.contains("原作世界：提瓦特大陆的稻妻"))
        assertTrue(estimateModelTokens(projection.stablePrompt) <= 520)
    }

    @Test
    fun rewrittenPresetCanRecallFormativeBiographyWithoutMakingItAlwaysOn() {
        val persona = requireNotNull(PersonaPresetCatalog.find("genshin-kamisato-ayaka")).persona
        val projector = CharacterRuntimeProjector(
            relationshipEngine = ChatRelationshipEngine(),
            loreEngine = CharacterLoreEngine(),
        )
        val normal = projector.project(persona, ChatCharacterState(), ChatContextState(), "今天一起散步吧", null)
        val relevant = projector.project(persona, ChatCharacterState(), ChatContextState(), "神里绫人和托马在神里家的职责是什么？", null)
        assertTrue(normal.stablePrompt.contains("神里绫华"))
        assertTrue(estimateModelTokens(relevant.stablePrompt) <= 520)
        assertTrue(relevant.dynamicPrompt.contains("【本轮相关背景】"))
        assertTrue(relevant.dynamicPrompt.contains("人物身份与经历："))
        assertTrue(relevant.dynamicPrompt.contains("神里绫人"))
    }

    @Test
    fun stablePersonaPrefixHasHardTokenBudget() {
        val persona = PersonaProfile(
            name = "阿青",
            portrait = "身份、处境、气质和长期选择逻辑。".repeat(120),
            lifeContext = "有自己的工作、朋友、计划和压力。".repeat(100),
            attentionBiases = List(8) { "注意细节和动作$it" },
            quirks = List(12) { "普通习惯$it" },
            voiceSamples = List(20) { "这是一条自然聊天样本$it。" },
            hardConstraints = listOf("绝不替用户做重大决定"),
            knowledgeBoundary = listOf("不知道未经历的后续剧情"),
        )
        val projection = CharacterRuntimeProjector(
            relationshipEngine = ChatRelationshipEngine(),
            loreEngine = CharacterLoreEngine(),
        ).project(
            persona = persona,
            state = ChatCharacterState(),
            context = ChatContextState(),
            userInput = "今天怎么样",
            storyContext = null,
        )

        assertTrue(estimateModelTokens(projection.stablePrompt) <= 600)
        assertTrue(projection.stablePrompt.contains("绝不替用户做重大决定"))
        assertTrue(projection.stablePrompt.contains("不知道未经历的后续剧情"))
        assertTrue(projection.stablePrompt.contains("只在人物世界内回应"))
        assertTrue(projection.stablePrompt.contains("禁止用“无现实身体/不能触碰"))
        assertTrue(projection.stablePrompt.contains("人物意愿和关系"))
    }

    @Test
    fun ordinarySmallTalkDoesNotPullDiaryButExplicitRecallDoes() {
        assertTrue(!shouldRecallDiary("今天天气不错，晚上吃什么"))
        assertTrue(shouldSearchDiary("我们之前提过的海边那张照片挺好看的"))
        assertTrue(shouldRecallDiary("你还记得上次我们说好的那件事吗"))
        assertEquals(3, diaryRecallItemLimit("你还记得上次我们说好的那件事吗"))
        assertEquals(1, diaryRecallItemLimit("最近那件事还在影响你吗"))
    }

    @Test
    fun groupDiaryBoundaryCannotRegressToPrivateOrShareable() {
        assertTrue(!canExposeDiaryToGroup(ChatDiaryDisclosure.PRIVATE))
        assertTrue(!canExposeDiaryToGroup(ChatDiaryDisclosure.SHAREABLE))
        assertTrue(canExposeDiaryToGroup(ChatDiaryDisclosure.PUBLIC))
        assertTrue(diaryRecallUsageInstruction(groupAudience = true).contains("只能使用 disclosure=PUBLIC"))
        assertTrue(diaryRecallUsageInstruction(groupAudience = true).contains("不得以隐私余波"))
    }

    @Test
    fun anomalyGuardOnlyMakesMinimalStructuralRepairs() {
        val result = CharacterReplyAnomalyGuard.repair(
            "建议：\n1. 先吃饭\n2. 再洗澡\n3. 然后休息\n4. 最后睡觉",
        )
        assertTrue("解释式标题" in result.anomalies)
        assertTrue("过度罗列" in result.anomalies)
        assertTrue(!result.text.contains("建议："))
        assertTrue(result.text.contains("先吃饭"))

        val legitimateList = CharacterReplyAnomalyGuard.repair(
            "1. 苹果\n2. 香蕉\n3. 梨\n4. 葡萄",
        )
        assertEquals("1. 苹果\n2. 香蕉\n3. 梨\n4. 葡萄", legitimateList.text)
    }
    @Test
    fun unchangedLifeSourceDoesNotRenewExpiryAfterOtherStateUpdates() {
        val first = advanceCharacterLife(PersonaProfile(), ChatCharacterState(
            currentAgenda = "写完封面", updatedAt = 1000L), now = 1000L)
        val event = first.activeEvents.single { it.source == "agenda" }
        val later = advanceCharacterLife(PersonaProfile(), ChatCharacterState(
            currentAgenda = "写完封面", updatedAt = 2000L, lifeState = first), now = 2000L)
        assertEquals(event.expiresAt, later.activeEvents.single { it.source == "agenda" }.expiresAt)
        val expired = advanceCharacterLife(PersonaProfile(), ChatCharacterState(
            currentAgenda = "写完封面", updatedAt = event.expiresAt, lifeState = later), now = event.expiresAt)
        assertTrue(expired.activeEvents.none { it.source == "agenda" })
    }
    @Test
    fun explicitStoryEveningWinsOverPhoneTimeAndWeekendHabit() {
        val life = advanceCharacterLife(PersonaProfile(lifeContext = "早上去出版社；晚上整理插画；周末看外婆"),
            ChatCharacterState(), now = 1000L, storyTime = "周末晚上")
        assertTrue(life.currentBeat.contains("晚上"))
        assertTrue(renderCharacterLifePrompt(life).contains("不代表此刻已经发生"))
    }
}

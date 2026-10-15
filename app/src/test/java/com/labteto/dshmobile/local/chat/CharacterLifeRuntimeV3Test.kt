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
    fun aPhoneClockDoesNotSelectTheCharactersCurrentActivity() {
        val persona = PersonaProfile(lifeContext = "白天在出版社工作；晚上整理插画；周末看外婆")
        val first = advanceCharacterLife(persona, ChatCharacterState(currentAgenda = "封面尚未交付"), now = 1L)
        val later = advanceCharacterLife(persona, ChatCharacterState(currentAgenda = "封面尚未交付",
            lifeState = first), now = 900_000_000L)
        assertEquals("", later.currentBeat)
        assertEquals(listOf("封面尚未交付"), later.activeEvents.map { it.summary })
        assertEquals(first.activeEvents.single().startedAt, later.activeEvents.single().startedAt)
    }

    @Test
    fun aShortGreetingStillReceivesTheCharactersPerceptionAndVoice() {
        val persona = PersonaProfile(name = "叶澜", portrait = "做插画，思路灵动",
            attentionBiases = listOf("留意构图和线条"), voiceSamples = listOf("等下，这片影子像只鸟。"))
        val view = CharacterRuntimeProjector(ChatRelationshipEngine(), CharacterLoreEngine())
            .project(persona, ChatCharacterState(physicalState = "很累"), ChatContextState(), "嗯", null)
        assertTrue(view.stablePrompt.contains("留意构图和线条"))
        assertTrue(view.stablePrompt.contains("这片影子像只鸟"))
        assertTrue(view.dynamicPrompt.contains("身体：很累"))
        assertTrue(!view.dynamicPrompt.contains("压缩"))
    }

    @Test
    fun responsibilitiesAreBackgroundWithoutADecisionRecipe() {
        val persona = PersonaProfile(name = "叶澜", coreIdentity = "书店店主", facts = listOf(
            CharacterFact("values", CharacterFactCategories.VALUES_AND_TRADEOFFS,
                "看重对员工的承诺", provenance = CharacterFactProvenance.USER_CREATED),
            CharacterFact("duty", CharacterFactCategories.BIOGRAPHY,
                "周末负责书店开门", provenance = CharacterFactProvenance.CANON)))
        val view = CharacterRuntimeProjector(ChatRelationshipEngine(), CharacterLoreEngine())
            .project(persona, ChatCharacterState(), ChatContextState(), "陪我远足好吗", null)
        assertTrue(view.stablePrompt.contains("周末负责书店开门"))
        assertTrue(view.stablePrompt.contains("看重对员工的承诺"))
        assertTrue(!view.dynamicPrompt.contains("接受、商量、拒绝"))
        assertTrue(!view.dynamicPrompt.contains("本轮人物的现实取舍"))
    }

    @Test
    fun v4UnscopedLegacyTimelineCannotBypassStoryKnowledge() {
        val persona = PersonaProfile(name = "叶澜", coreIdentity = "书店店主",
            facts = listOf(CharacterFact("opening", CharacterFactCategories.BIOGRAPHY,
                "正在经营书店", provenance = CharacterFactProvenance.CANON)),
            timelinePosition = "最终章揭秘：阿宁是卧底",
            worldSetting = "未来结局：书店被毁",
        )
        val rendered = CharacterRuntimeProjector(ChatRelationshipEngine(), CharacterLoreEngine())
            .project(persona, ChatCharacterState(), ChatContextState(), "今天怎么样", null)
        assertTrue((rendered.stablePrompt + rendered.dynamicPrompt).contains("书店"))
        assertTrue(!(rendered.stablePrompt + rendered.dynamicPrompt).contains("阿宁是卧底"))
        assertTrue(!(rendered.stablePrompt + rendered.dynamicPrompt).contains("书店被毁"))
    }

    @Test
    fun aSharedExperienceDoesNotReweightThePresetPersonality() {
        val persona = requireNotNull(PersonaPresetCatalog.find("genshin-kamisato-ayaka")).persona
        val previous = ChatCharacterState()
        val current = previous.copy(dynamics = previous.dynamics.copy(sharedMoments = listOf("一起筹备祭典")))
        val evolution = evolveCharacterEvolution(persona, previous, current, "MAJOR", "祭典结束了", "一起筹备祭典")
        assertEquals("一起筹备祭典", evolution.lastMajorMilestone)
        assertEquals(previous.evolution.initiativeBaseline, evolution.initiativeBaseline)
        assertEquals(previous.evolution.traitStates, evolution.traitStates)
        assertEquals(persona, requireNotNull(PersonaPresetCatalog.find("genshin-kamisato-ayaka")).persona)
    }

    @Test
    fun repeatedWordsDoNotRewriteTheAuthorsHabits() {
        val habit = CharacterFact("habit", CharacterFactCategories.PREFERENCES_AND_HABITS,
            "愿意求助", provenance = CharacterFactProvenance.USER_CREATED)
        val persona = PersonaProfile(name = "阿青", facts = listOf(habit))
        val state = ChatCharacterState()
        val evolved = evolveCharacterEvolution(persona, state, state, "MINOR", "愿意求助", "我愿意求助")
        assertEquals(state.evolution, evolved)
        assertEquals(listOf(habit), persona.facts)
    }

    @Test
    fun repeatedMinorTurnsDoNotCreateASecondPersonality() {
        val persona = PersonaProfile(name = "陈拾", mutableTraits = listOf("愿意求助"))
        var state = ChatCharacterState(initiative = 70, shareDesire = 65)
        val baseline = state.evolution
        repeat(250) {
            state = state.copy(evolution = evolveCharacterEvolution(persona, state, state,
                "MINOR", "需要帮助就说", "这次我愿意求助"))
        }
        assertEquals(baseline, state.evolution)
        assertEquals(listOf("愿意求助"), persona.mutableTraits)
    }

    @Test
    fun aLongMessageDoesNotManufactureAnAttentionPrescription() {
        val persona = PersonaProfile(name = "叶澜", portrait = "插画师")
        val view = CharacterRuntimeProjector(ChatRelationshipEngine(), CharacterLoreEngine())
            .project(persona, ChatCharacterState(), ChatContextState(), "整理代码和测试结果。".repeat(40), null)
        assertTrue(!view.dynamicPrompt.contains("注意力落点"))
        assertTrue(!view.dynamicPrompt.contains("推演"))
    }

    @Test
    fun seriousQuestionsDoNotOverrideThePersonsResponseStyle() {
        val persona = PersonaProfile(name = "陈拾", portrait = "寡言，习惯直接说结论")
        val state = ChatCharacterState(physicalState = "刚下班，很累")
        val projector = CharacterRuntimeProjector(ChatRelationshipEngine(), CharacterLoreEngine())
        val casual = projector.project(persona, state, ChatContextState(), "嗯", null)
        val serious = projector.project(persona, state, ChatContextState(), "我该不该辞职？", null)
        assertEquals(casual.stablePrompt, serious.stablePrompt)
        assertTrue(serious.stablePrompt.contains("寡言，习惯直接说结论"))
        assertTrue(!serious.dynamicPrompt.contains("本轮模式"))
        assertTrue(!serious.dynamicPrompt.contains("覆盖完整"))
    }

    @Test
    fun explicitQuestionsAndSmallTalkHaveTheSameCharacterAuthority() {
        val persona = PersonaProfile(name = "阿青", portrait = "灵动爱开玩笑，遇到正事认真",
            voiceSamples = listOf("等下，我突然想到个东西。", "你先说重点。"))
        val projector = CharacterRuntimeProjector(ChatRelationshipEngine(), CharacterLoreEngine())
        val casual = projector.project(persona, ChatCharacterState(), ChatContextState(), "看到猫追塑料袋", null)
        val factual = projector.project(persona, ChatCharacterState(), ChatContextState(), "明天几点出发", null)
        assertEquals(casual.stablePrompt, factual.stablePrompt)
        assertTrue(factual.stablePrompt.contains("遇到正事认真"))
        assertTrue(!factual.dynamicPrompt.contains("直觉先行"))
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
        assertTrue(projection.stablePrompt.contains("【原作来源】\n原神"))
        assertTrue(projection.stablePrompt.contains("【当前剧情阶段】\n稻妻已与旅行者相识"))
        assertTrue(projection.stablePrompt.contains("【世界背景】\n提瓦特大陆的稻妻"))
        assertTrue(estimateModelTokens(projection.stablePrompt) <= 1_040)
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
        assertTrue(estimateModelTokens(relevant.stablePrompt) <= 1_040)
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

        assertTrue(estimateModelTokens(projection.stablePrompt) <= 1_040)
        assertTrue(projection.stablePrompt.contains("绝不替用户做重大决定"))
        assertTrue(projection.stablePrompt.contains("不知道未经历的后续剧情"))
        assertTrue(projection.stablePrompt.contains("当前聊天的人设与故事背景"))
        assertTrue(projection.stablePrompt.contains("声音") || projection.stablePrompt.contains("语言风格资料"))
    }

    @Test
    fun ordinarySmallTalkDoesNotPullDiaryButExplicitRecallDoes() {
        assertTrue(!shouldRecallDiary("今天天气不错，晚上吃什么"))
        assertTrue(shouldSearchDiary("我们之前提过的海边那张照片挺好看的"))
        assertTrue(shouldRecallDiary("你还记得上次我们说好的那件事吗"))
        assertEquals(6, diaryRecallItemLimit("你还记得上次我们说好的那件事吗"))
        assertEquals(4, diaryRecallItemLimit("最近那件事还在影响你吗"))
    }

    @Test
    fun groupMemoryRecallRespectsIndividualOwnershipWithoutModeWideBlocking() {
        val note = ChatDiaryEntry(
            id = "same-person", subjectKey = "gallery:one", personaName = "阿青",
            event = "约定下雨时一起散步",
            feeling = "我期待下一次相见",
            innerThought = "我想带一把伞",
            disclosure = ChatDiaryDisclosure.SHAREABLE,
            createdAt = 1L, updatedAt = 2L,
        )
        val direct = ChatDiaryRecallEngine.search(
            listOf(note), "下雨时一起散步", "gallery:one",
            groupAudience = false, maxItems = 2,
        )
        val group = ChatDiaryRecallEngine.search(
            listOf(note), "下雨时一起散步", "gallery:one",
            groupAudience = true, maxItems = 2,
        )
        val other = ChatDiaryRecallEngine.search(
            listOf(note), "下雨时一起散步", "gallery:two",
            groupAudience = true, maxItems = 2,
        )
        assertEquals(direct, group)
        assertTrue(group.isNotEmpty())
        assertTrue(other.isEmpty())
        assertTrue(renderRecalledChatDiary(group.single(), groupAudience = true).contains("我期待下一次相见"))
    }

    @Test
    fun aCharactersListAndQuestionsAreNotReformattedByApplicationTaste() {
        val text = "建议：\n1. 先吃饭\n2. 再洗澡\n3. 然后休息\n4. 最后睡觉"
        assertTrue(ChatStyleGuard.violations(text, ChatStyleGuard.activePhrases()).isEmpty())
    }
    @Test
    fun anUnfinishedEventSurvivesTimeButLeavesWhenTheStateActuallyClearsIt() {
        val first = advanceCharacterLife(PersonaProfile(), ChatCharacterState(currentAgenda = "写完封面"), now = 1L)
        val later = advanceCharacterLife(PersonaProfile(), ChatCharacterState(currentAgenda = "写完封面",
            lifeState = first), now = 1_000_000_000L)
        assertEquals(listOf("写完封面"), later.activeEvents.map { it.summary })
        val completed = advanceCharacterLife(PersonaProfile(), ChatCharacterState(lifeState = later), now = 1_000_000_001L)
        assertTrue(completed.activeEvents.isEmpty())
    }
    @Test
    fun aHabitDoesNotBecomeALivedEventOrProactivePretext() {
        val persona = PersonaProfile(lifeContext = "晚上整理插画；周末看外婆")
        val life = advanceCharacterLife(persona, ChatCharacterState(), now = 1000L, storyTime = "周末晚上")
        assertTrue(life.activeEvents.isEmpty())
        assertEquals("", renderCharacterLifePrompt(life))
        assertEquals(null, characterLifeProactiveReason(persona, ChatCharacterState(), now = 1000L))
    }
    @Test
    fun lifeProjectionDoesNotInjectCurrentAgendaForASecondTime() {
        val persona = PersonaProfile(name = "阿青")
        val state = ChatCharacterState(
            currentAgenda = "继续展示明制披衫",
            unresolvedThreads = listOf("整理宋制襦裙"),
        )
        val life = advanceCharacterLife(persona, state)
        assertEquals(2, life.activeEvents.size)
        assertEquals("", renderCharacterLifePrompt(life))
    }

}

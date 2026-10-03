package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.estimateModelTokens
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
        assertEquals("用户最近很忙", state.recentImpression)
    }

    @Test
    fun lifeRuntimeAdvancesWithElapsedTimeWithoutInventingNewFacts() {
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
    }

    @Test
    fun attentionAndBehaviorPreferCharacterSpecificFocus() {
        val persona = PersonaProfile(
            name = "叶澜",
            attentionBiases = listOf("画画和构图的细节"),
            perceptionBlindSpots = listOf("容易忽略别人拐弯表达的情绪"),
        )
        val state = ChatCharacterState(
            physicalState = "刚下班，很累",
            lifeState = CharacterLifeState(currentBeat = "晚上会整理插画稿"),
        )
        val attention = resolveCharacterAttention(
            persona,
            state,
            "我妈刚才又催我回家，不过我今天画了一张新的构图，线条终于顺了。",
        )
        val behavior = resolveCharacterBehavior(persona, state, "给你看看这张画？", attention)

        assertTrue(attention.noticed.any { it.contains("画") || it.contains("构图") || it.contains("线条") })
        assertEquals(CharacterBehaviorMode.NORMAL, behavior.mode)
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
    fun busyStateCanShortenSmallTalkButCannotSuppressExplicitQuestion() {
        val persona = PersonaProfile(name = "陈拾")
        val state = ChatCharacterState(physicalState = "刚下班，很累")
        val casual = resolveCharacterBehavior(
            persona,
            state,
            "今天路上人好多",
            resolveCharacterAttention(persona, state, "今天路上人好多"),
        )
        val question = resolveCharacterBehavior(
            persona,
            state,
            "你为什么没回我刚才的问题？",
            resolveCharacterAttention(persona, state, "你为什么没回我刚才的问题？"),
        )

        assertEquals(CharacterBehaviorMode.BRIEF, casual.mode)
        assertEquals(CharacterBehaviorMode.NORMAL, question.mode)
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
}

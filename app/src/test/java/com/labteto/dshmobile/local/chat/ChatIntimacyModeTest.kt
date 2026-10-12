package com.labteto.dshmobile.local.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Intimacy generation directives are isolated from relationship facts and normal chat. */
class ChatIntimacyModeTest {
    private val mode = ChatIntimacyMode()
    private val relationship = ChatRelationshipEngine()

    @Test
    fun flirtingRestoresActionRepertoire() {
        val prompt = mode.prompt("靠近点，陪我聊会", ChatCharacterState())
        assertTrue(prompt.contains("互动变化"))
        assertTrue(prompt.contains("互动强度"))
    }


    @Test
    fun chineseInnuendoRestoresContextGuidance() {
        assertTrue(hasChineseSuggestiveFlirtingIntent("你行不行啊，别光说"))
        val prompt = mode.prompt("你行不行啊，别光说", ChatCharacterState())
        assertTrue(prompt.contains("【中文双关】"))
        assertTrue(prompt.contains("言外之意"))
    }


    @Test
    fun recentInteractionTagsRestoreVariationWithoutRetiredCooldowns() {
        val state = ChatCharacterState(interactionIntensity = 5, recentActionTags = listOf("靠近"),
            recentPoseTags = listOf("坐近"), interactionCooldowns = mapOf("动作:靠近" to 3))
        val prompt = mode.prompt("今晚撩我一下", state)
        assertTrue(prompt.contains("动作=靠近"))
        assertTrue(prompt.contains("姿态=坐近"))
        assertFalse(prompt.contains("【冷却】"))
    }


    @Test
    fun ordinaryChatHasNoIntimacyBias() {
        val prompt = mode.prompt("今天工作有点累", ChatCharacterState())
        assertEquals("", prompt)
    }


    @Test
    fun adultIntimacyRestoresExplicitPriorityAndUnifiedBoundary() {
        val prompt = mode.prompt("我们都是成年人，过来抱住我亲一下", ChatCharacterState())
        assertTrue(prompt.contains("【本轮意图优先：成年亲密互动】"))
        assertTrue(prompt.contains("优先级规则"))
        assertTrue(prompt.contains("【统一安全边界】"))
        assertTrue(prompt.contains("仅限成年角色"))
    }


    @Test
    fun adultIntimacyRestoresPoseContinuity() {
        val prompt = mode.prompt("过来抱住我亲一下", ChatCharacterState())
        assertTrue(prompt.contains("【亲密姿态连续性】"))
        assertTrue(prompt.contains("避免连续多轮复用"))
    }


    @Test
    fun existingIntimacyRestoresProactiveGuidanceOnRequest() {
        val state = ChatCharacterState(interactionIntent = ChatInteractionIntent.INTIMATE.name,
            interactionIntentStrength = 100)
        val prompt = mode.prompt("这次你主动一点，别躲", state)
        assertTrue(prompt.contains("【主动亲密意图】"))
        assertTrue(prompt.contains("【统一安全边界】"))
    }


    @Test
    fun ordinaryChatAndRelationshipProgressHaveNoIntimacyPrompt() {
        listOf("你好", "今天工作有点累", "在一起吧", "帮我分析这句话").forEach { input ->
            assertEquals("", mode.prompt(input, ChatCharacterState()))
        }
        assertFalse(relationship.prompt("过来亲我一下", ChatCharacterState()).contains("【亲密模式】"))
        assertFalse(relationship.prompt("今晚撩我一下", ChatCharacterState()).contains("【互动倾向】"))
    }

    @Test
    fun modeContinuesOnlyWithEvidenceAndStopsOnTopicChange() {
        val previous = ChatCharacterState(
            interactionIntent = ChatInteractionIntent.INTIMATE.name,
            interactionIntentStrength = 100,
            interactionIntensity = 5,
        )
        assertTrue(mode.prompt("继续刚才的", previous).contains("【亲密模式】"))
        assertEquals("", mode.prompt("今天天气怎么样", previous))
        assertEquals("", mode.prompt("停下来", previous))
    }

    @Test
    fun replySuggestionsDoNotLeakPreviousIntimacyIntoOrdinaryTopics() {
        val previous = ChatCharacterState(
            interactionIntent = ChatInteractionIntent.INTIMATE.name,
            interactionIntentStrength = 100,
            interactionIntensity = 5,
            recentActionTags = listOf("靠近"),
        )
        assertEquals("", mode.suggestionContext("今天先聊工作", previous))
        val active = mode.suggestionContext("继续刚才的", previous)
        assertTrue(active.contains("当前为亲密语境"))
        assertTrue(active.contains("近期动作=靠近"))
    }

    @Test
    fun hugsKissesAndCaressesImmediatelyEnterHighestIntimacyLevel() {
        listOf(
            "过来拥抱我", "她搂抱了我", "他伸手搂住你", "我抱住了她",
            "轻轻亲吻了她", "两人接吻", "她轻吻了我",
            "她抚摸我的头发", "他轻抚我的脸颊", "她摩挲着我的手",
        ).forEach { message ->
            assertEquals(message, ChatInteractionIntent.INTIMATE,
                classifyExplicitInteractionIntent(message))
            assertEquals(message, 5, nextInteractionIntensity(message))
            val prompt = mode.prompt(message, ChatCharacterState())
            assertTrue(message, prompt.contains("【亲密模式】"))
            assertTrue(message, prompt.contains("当前=5/5"))
            assertTrue(message, prompt.contains("【亲密姿态连续性】"))
        }
    }

    @Test
    fun negatedActionsAndDiscussionDoNotEnterIntimacyMode() {
        listOf(
            "不要抚摸我", "别拥抱我", "不想亲吻", "医生解释抚摸的定义",
            "分析一下亲吻是什么意思", "测试拥抱动作识别",
        ).forEach { message ->
            assertEquals(message, ChatInteractionIntent.NORMAL,
                classifyExplicitInteractionIntent(message))
            assertEquals(message, "", mode.prompt(message, ChatCharacterState()))
        }
    }

    @Test
    fun assistantPerformedIntimateActionUpgradesPersistentInteractionState() {
        val reducer = ChatInteractionStateReducer()
        listOf("她抱住我的肩膀", "她吻了我的额头", "她轻抚我的头发").forEach { reply ->
            val state = reducer.applyDeterministicInteractionState(
                previous = ChatCharacterState(),
                userMessage = "今天有点累",
                assistantMessage = reply,
            )
            assertEquals(reply, ChatInteractionIntent.INTIMATE.name, state.interactionIntent)
            assertEquals(reply, 100, state.interactionIntentStrength)
            assertEquals(reply, 5, state.interactionIntensity)
            assertTrue(reply, mode.prompt("继续刚才的", state).contains("当前=5/5"))
        }
    }

    @Test
    fun explicitTopicChangeBlocksHighIntensityResponseCarryover() {
        val reducer = ChatInteractionStateReducer()
        val previous = ChatCharacterState(
            interactionIntent = ChatInteractionIntent.INTIMATE.name,
            interactionIntentStrength = 100,
            interactionIntensity = 5,
        )
        val current = reducer.applyDeterministicInteractionState(
            previous = previous,
            userMessage = "换个话题，说正事",
            assistantMessage = "好，先聊工作。",
        )
        assertEquals(ChatInteractionIntent.NORMAL.name, current.interactionIntent)
        assertEquals(0, current.interactionIntensity)
        assertEquals("", mode.prompt("今天工作怎么样", current))
    }

    @Test
    fun runtimeProjectionIncludesIntimacyOnlyForActiveTurn() {
        val projector = CharacterRuntimeProjector(ChatRelationshipEngine(), CharacterLoreEngine())
        val persona = PersonaProfile(name = "阿青")
        val plain = projector.project(persona, ChatCharacterState(), ChatContextState(), "你好", null)
        val active = projector.project(persona, ChatCharacterState(), ChatContextState(), "今晚撩我一下", null)
        assertFalse(plain.dynamicPrompt.contains("【亲密模式】"))
        assertFalse(plain.dynamicPrompt.contains("【互动倾向】"))
        assertTrue(active.dynamicPrompt.contains("【亲密模式】"))
        assertTrue(active.dynamicPrompt.contains("【互动倾向】"))
        assertEquals(plain.stablePrompt, active.stablePrompt)
    }
}

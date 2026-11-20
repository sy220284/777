package com.labteto.dshmobile.local.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Chat Next keeps relational context while leaving character behavior to the persona. */
class ChatIntimacyModeTest {
    private val mode = ChatIntimacyMode()

    @Test fun ordinaryDialogueDoesNotGainRelationalPrompts() {
        listOf("你好", "今天工作有点累", "在一起吧", "帮我分析这句话",
            "停下来", "我们今天讨论小说剧情").forEach { message ->
            assertEquals(message, "", mode.prompt(message, ChatCharacterState()))
        }
    }

    @Test fun affectionateDialoguePreservesPersonaAgencyWithoutForcedActionScripts() {
        listOf("过来抱住我亲一下", "今晚撩我一下", "她轻轻拥抱了我").forEach { message ->
            val prompt = mode.prompt(message, ChatCharacterState())
            assertTrue(message, prompt.contains("【亲密模式】"))
            assertTrue(message, prompt.contains("原有性格"))
            assertTrue(message, prompt.contains("个人意愿"))
            assertFalse(message, prompt.contains("【身体反应强度】"))
            assertFalse(message, prompt.contains("当前=5/5"))
            assertFalse(message, prompt.contains("【亲密姿态连续性】"))
            assertFalse(message, prompt.contains("【主动亲密意图】"))
        }
    }

    @Test fun chineseWordplayAddsContextNotMandatoryEscalation() {
        assertTrue(hasChineseSuggestiveFlirtingIntent("你行不行啊，别光说"))
        val prompt = mode.prompt("你行不行啊，别光说", ChatCharacterState())
        assertTrue(prompt.contains("【中文双关】"))
        assertTrue(prompt.contains("言外之意"))
        assertTrue(prompt.contains("自动将玩笑升级"))
    }

    @Test fun existingContextContinuesOnlyOnActualConversationCues() {
        val previous = ChatCharacterState(
            interactionIntent = ChatInteractionIntent.INTIMATE.name,
            interactionIntentStrength = 100,
            interactionIntensity = 5,
        )
        assertTrue(mode.prompt("继续刚才的", previous).contains("【亲密模式】"))
        assertEquals("", mode.prompt("换个话题，说正事", previous))
        assertEquals("", mode.prompt("今天天气怎么样", previous))
        assertEquals("", mode.prompt("停下来", previous))
    }

    @Test fun discussionAndNegationDoNotActivateIntimacy() {
        listOf("不要抚摸我", "别拥抱我", "不想亲吻", "医生解释抚摸的定义",
            "分析一下亲吻是什么意思", "测试拥抱动作识别").forEach { message ->
            assertEquals(message, ChatInteractionIntent.NORMAL,
                classifyExplicitInteractionIntent(message))
            assertEquals(message, "", mode.prompt(message, ChatCharacterState()))
        }
    }

    @Test fun suggestionsCarryOnlyRelevantContext() {
        val previous = ChatCharacterState(
            interactionIntent = ChatInteractionIntent.INTIMATE.name,
            interactionIntentStrength = 100,
            interactionIntensity = 5,
            recentActionTags = listOf("靠近"),
        )
        assertEquals("", mode.suggestionContext("今天先聊工作", previous))
        val active = mode.suggestionContext("继续刚才的", previous)
        assertTrue(active.contains("人物性格"))
        assertFalse(active.contains("强度=5/5"))
        assertFalse(active.contains("动作=靠近"))
    }

    @Test fun characterProjectionReceivesContextOnlyDuringActiveTurn() {
        val projector = CharacterRuntimeProjector(ChatRelationshipEngine(), CharacterLoreEngine())
        val persona = PersonaProfile(name = "阿青")
        val plain = projector.project(persona, ChatCharacterState(), ChatContextState(), "你好", null)
        val active = projector.project(persona, ChatCharacterState(), ChatContextState(), "今晚撩我一下", null)
        assertFalse(plain.dynamicPrompt.contains("【亲密模式】"))
        assertTrue(active.dynamicPrompt.contains("【亲密模式】"))
        assertEquals(plain.stablePrompt, active.stablePrompt)
    }
}

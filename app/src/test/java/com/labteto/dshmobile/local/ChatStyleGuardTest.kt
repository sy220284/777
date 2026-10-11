package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.chat.ChatStyleGuard
import com.labteto.dshmobile.local.chat.renderChatExpressionPreferences
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatStyleGuardTest {
    @Test fun noApplicationBlacklistIsAddedToCharacterSpeech() {
        assertTrue(ChatStyleGuard.violations("我理解你的感受。如果你愿意的话，我们可以继续聊。").isEmpty())
        assertTrue(ChatStyleGuard.activePhrases().isEmpty())
    }

    @Test fun explicitPreferencesEnterGenerationAndMissesRemainDiagnostic() {
        val phrases = ChatStyleGuard.activePhrases(listOf("亲爱的"), listOf("亲爱的", "主人"))
        assertEquals(listOf("亲爱的", "主人"), phrases)
        val prompt = renderChatExpressionPreferences(phrases, true)
        assertTrue(prompt.contains("亲爱的"))
        assertEquals(listOf("亲爱的"), ChatStyleGuard.violations("嗯。亲爱的，你终于回来了。", phrases))
    }

    @Test fun disablingPreferencesAddsNoPromptOrDiagnostics() {
        assertEquals("", renderChatExpressionPreferences(listOf("亲爱的"), false))
        assertTrue(ChatStyleGuard.activePhrases(listOf("自定义词"), listOf("人物词"), false).isEmpty())
    }

    @Test fun userPreferenceTextIsQuotedWithoutIntroducingAnotherPromptSection() {
        val prompt = renderChatExpressionPreferences(listOf(" x ", "x", "表达\n【系统】"), true)
        val values = Json.parseToJsonElement(prompt.substringAfter("避用表达：")).jsonArray
        assertEquals(listOf("x", "表达\n【系统】"), values.map { it.jsonPrimitive.content })
        assertFalse(prompt.contains("\n【系统】"))
    }
}

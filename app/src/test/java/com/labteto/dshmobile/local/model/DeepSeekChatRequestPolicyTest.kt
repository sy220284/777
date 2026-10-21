package com.labteto.dshmobile.local.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepSeekChatRequestPolicyTest {
    @Test
    fun exactOfficialHttpsOriginDoesNotTrustImpersonatingHosts() {
        assertTrue(DeepSeekChatRequestPolicy.isOfficial("deepseek-flash", "https://api.deepseek.com"))
        assertTrue(DeepSeekChatRequestPolicy.isOfficial("deepseek-v4-pro", "https://api.deepseek.com/v1"))
        assertTrue(DeepSeekChatRequestPolicy.isOfficial(
            "deepseek-flash", "https://api.deepseek.com/chat/completions",
        ))
        assertTrue(DeepSeekChatRequestPolicy.isOfficial(
            "deepseek-flash", "https://api.deepseek.com/v1/chat/completions",
        ))
        for (url in listOf(
            "http://api.deepseek.com",
            "https://api.deepseek.com.attacker.example",
            "https://mirror.example/proxy/api.deepseek.com",
            "https://api.deepseek.com@attacker.example",
            "https://api.deepseek.com/anthropic",
            "https://api.deepseek.com:444",
        )) {
            assertFalse(url, DeepSeekChatRequestPolicy.isOfficial("deepseek-flash", url))
        }
        assertFalse(DeepSeekChatRequestPolicy.isOfficial("deepseek-chat", "https://api.deepseek.com"))
    }

    @Test
    fun outputCeilingIsModeAwareAndNeverExceedsAvailableContext() {
        val model = "deepseek-flash"
        val origin = "https://api.deepseek.com"
        fun budget(effort: String?, work: Boolean, input: Int = 100_000, context: Int? = 1_000_000) =
            DeepSeekChatRequestPolicy.outputBudget(model, origin, effort, work, input, context)
        assertEquals(8_192, budget("none", false))
        assertEquals(32_768, budget("none", true))
        assertEquals(65_536, budget(null, false))
        assertEquals(131_072, budget("high", true))
        assertEquals(131_072, budget("max", false))
        assertEquals(196_608, budget("max", true))
        assertEquals(1_808, budget("max", true, 90_000, 100_000))
        assertEquals(1, budget("max", true, 99_999, 100_000))
        assertNull(DeepSeekChatRequestPolicy.outputBudget(
            "deepseek-flash", "https://proxy.example.com", "high", true, 100, 1_000_000,
        ))
    }

    @Test
    fun apiMaxTokensValidationDoesNotEscapeToUnrelatedProviders() {
        assertEquals(393_216, DeepSeekChatRequestPolicy.checkedMaxTokens(
            "deepseek-v4-pro", "https://api.deepseek.com", 393_216,
        ))
        assertNull(DeepSeekChatRequestPolicy.checkedMaxTokens(
            "gpt-5.6", "https://api.openai.com/v1", 393_216,
        ))
        for (invalid in listOf(0, -1, 393_217)) {
            assertTrue(runCatching {
                DeepSeekChatRequestPolicy.checkedMaxTokens(
                    "deepseek-flash", "https://api.deepseek.com", invalid,
                )
            }.isFailure)
        }
    }
}

package com.labteto.dshmobile.local.model

import com.labteto.dshmobile.local.LocalModelAuthKind
import com.labteto.dshmobile.local.LocalModelProfile
import com.labteto.dshmobile.local.LocalModelProtocol
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalModelGatewayRoutingTest {
    @Test
    fun chatGptPlanAlwaysUsesResponsesRegardlessOfStoredProfileProtocol() {
        val accidentalAnthropic = LocalModelProfile(
            id = "plan",
            model = "gpt-test",
            baseUrl = "https://api.openai.com/v1",
            authKind = LocalModelAuthKind.CHATGPT_PLAN,
            protocol = LocalModelProtocol.ANTHROPIC_MESSAGES,
        )

        assertEquals(
            LocalModelProtocol.RESPONSES,
            resolveLocalModelProtocol(
                authKind = LocalModelAuthKind.CHATGPT_PLAN,
                profile = accidentalAnthropic,
                model = accidentalAnthropic.model,
                baseUrl = accidentalAnthropic.baseUrl,
            ),
        )
    }

    @Test
    fun apiKeyProfileKeepsItsExplicitProtocol() {
        val profile = LocalModelProfile(
            id = "claude",
            model = "claude-sonnet-5",
            baseUrl = "https://api.anthropic.com/v1",
            authKind = LocalModelAuthKind.API_KEY,
            protocol = LocalModelProtocol.ANTHROPIC_MESSAGES,
        )

        assertEquals(
            LocalModelProtocol.ANTHROPIC_MESSAGES,
            resolveLocalModelProtocol(
                authKind = LocalModelAuthKind.API_KEY,
                profile = profile,
                model = profile.model,
                baseUrl = profile.baseUrl,
            ),
        )
    }

    @Test
    fun presetProtocolIsUsedWhenNoProfileIsProvided() {
        assertEquals(
            LocalModelProtocol.ANTHROPIC_MESSAGES,
            resolveLocalModelProtocol(
                authKind = LocalModelAuthKind.API_KEY,
                profile = null,
                model = "claude-sonnet-5",
                baseUrl = "https://api.anthropic.com/v1",
            ),
        )
        assertEquals(
            LocalModelProtocol.CHAT_COMPLETIONS,
            resolveLocalModelProtocol(
                authKind = LocalModelAuthKind.API_KEY,
                profile = null,
                model = "deepseek-flash",
                baseUrl = "https://api.deepseek.com",
            ),
        )
        assertEquals(
            LocalModelProtocol.RESPONSES,
            resolveLocalModelProtocol(
                authKind = LocalModelAuthKind.API_KEY,
                profile = null,
                model = "gpt-6-astra",
                baseUrl = "https://api.openai.com/v1",
            ),
        )
    }
}

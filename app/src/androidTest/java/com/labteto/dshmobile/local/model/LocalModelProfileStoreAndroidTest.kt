package com.labteto.dshmobile.local.model

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.local.LocalModelAuthKind
import com.labteto.dshmobile.local.LocalModelProfile
import com.labteto.dshmobile.local.LocalModelProtocol
import com.labteto.dshmobile.local.modelProfileId
import java.util.UUID
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalModelProfileStoreAndroidTest {
    private lateinit var context: Context
    private lateinit var preferenceName: String

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        preferenceName = "model-profile-test-${UUID.randomUUID()}"
    }

    @After
    fun tearDown() {
        context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun missingActiveProfileNeverFallsThroughToAnotherAccountWithSameRoute() {
        val preferences = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
        val store = LocalModelProfileStore(preferences, Json { ignoreUnknownKeys = true })
        val first = planProfile("account-a")
        val second = planProfile("account-b")

        store.write(listOf(first, second))
        store.setActive(first)
        store.write(listOf(second))

        assertNull(store.active(second.model, second.baseUrl, listOf(second)))
    }

    @Test
    fun customResponsesRouteSurvivesReloadWithoutBecomingAPlanAccount() {
        val preferences = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
        val json = Json { ignoreUnknownKeys = true }
        val store = LocalModelProfileStore(preferences, json)
        val route = LocalModelProfile(
            modelProfileId("custom", "https://custom.example/v1"), "custom", "https://custom.example/v1",
            protocol = LocalModelProtocol.RESPONSES,
        )
        store.write(listOf(route))
        store.setActive(route)
        val reloaded = LocalModelProfileStore(preferences, json)
        assertEquals(route, reloaded.read().single())
        assertEquals(route, reloaded.active(route.model, route.baseUrl))
        assertEquals(LocalModelAuthKind.API_KEY, reloaded.read().single().authKind)
    }


    @Test
    fun officialClaudeLegacyProfileMigratesToNativeMessagesButCustomRoutesDoNot() {
        val preferences = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
        val json = Json { ignoreUnknownKeys = true }
        val store = LocalModelProfileStore(preferences, json)
        val legacyClaude = LocalModelProfile(
            id = modelProfileId("claude-sonnet-5", "https://api.anthropic.com/v1"),
            model = "claude-sonnet-5",
            baseUrl = "https://api.anthropic.com/v1",
            provider = "Claude（兼容接口）",
            protocol = LocalModelProtocol.CHAT_COMPLETIONS,
        )
        val custom = LocalModelProfile(
            id = modelProfileId("claude-sonnet-5", "https://proxy.example/v1"),
            model = "claude-sonnet-5",
            baseUrl = "https://proxy.example/v1",
            provider = "自定义",
            protocol = LocalModelProtocol.CHAT_COMPLETIONS,
        )

        store.write(listOf(legacyClaude, custom))
        val reloaded = LocalModelProfileStore(preferences, json).read()

        assertEquals("claude-sonnet-5-5", reloaded.first { it.id == legacyClaude.id }.model)
        assertEquals("claude-sonnet-5", reloaded.first { it.id == custom.id }.model)
        assertEquals(
            LocalModelProtocol.ANTHROPIC_MESSAGES,
            reloaded.first { it.id == legacyClaude.id }.protocol,
        )
        assertEquals(
            LocalModelProtocol.CHAT_COMPLETIONS,
            reloaded.first { it.id == custom.id }.protocol,
        )
    }

    private fun planProfile(accountId: String) = LocalModelProfile(
        id = modelProfileId(
            "gpt-test",
            "https://api.openai.com/v1",
            LocalModelAuthKind.CHATGPT_PLAN,
            accountId,
        ),
        model = "gpt-test",
        baseUrl = "https://api.openai.com/v1",
        provider = "ChatGPT",
        authKind = LocalModelAuthKind.CHATGPT_PLAN,
        protocol = LocalModelProtocol.RESPONSES,
        credentialRef = accountId,
    )
}

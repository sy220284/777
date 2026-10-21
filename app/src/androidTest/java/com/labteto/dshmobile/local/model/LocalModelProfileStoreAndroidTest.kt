package com.labteto.dshmobile.local.model

import android.content.Context
import com.labteto.dshmobile.local.LocalHarnessState
import com.labteto.dshmobile.local.runtime.LocalRuntimeProjection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun sameApiRouteProfilesWithDifferentStableIdsSurvivePersistenceTogether() {
        val preferences = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
        val json = Json { ignoreUnknownKeys = true }
        val store = LocalModelProfileStore(preferences, json)
        val first = LocalModelProfile(
            id = "api-account-a",
            model = "shared-model",
            baseUrl = "https://same.example/v1",
            displayName = "账户 A",
        )
        val second = first.copy(id = "api-account-b", displayName = "账户 B")

        store.write(listOf(first, second))
        store.setActive(second)

        val reloaded = LocalModelProfileStore(preferences, json)
        assertEquals(listOf(first, second), reloaded.read())
        assertEquals(second, reloaded.active(second.model, second.baseUrl, reloaded.read()))
    }

    @Test
    fun explicitMiniMaxRouteProtocolSurvivesReloadOnBothOfficialEndpoints() {
        val preferences = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
        val store = LocalModelProfileStore(preferences, Json { ignoreUnknownKeys = true })
        val old = LocalModelProfile(
            id = "minimax-legacy-chat",
            model = "MiniMax-M3.1-Flash",
            baseUrl = "https://api.minimaxi.com/v1",
            provider = "MiniMax",
            protocol = LocalModelProtocol.CHAT_COMPLETIONS,
        )
        val selected = old.copy(
            id = "minimax-explicit-custom",
            baseUrl = "https://api.minimaxi.com/anthropic/v1",
            protocol = LocalModelProtocol.CHAT_COMPLETIONS,
        )
        store.write(listOf(old, selected))
        val loaded = LocalModelProfileStore(preferences, Json { ignoreUnknownKeys = true }).read()
        assertEquals(old, loaded.first { it.id == old.id })
        assertEquals(selected, loaded.first { it.id == selected.id })
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

    @Test
    fun migratedAliasKeepsActiveCredentialIdentityWhenCurrentPresetAlsoExists() {
        val preferences = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
        val store = LocalModelProfileStore(preferences, Json { ignoreUnknownKeys = true })
        val legacy = LocalModelProfile(modelProfileId("claude-sonnet-5", "https://api.anthropic.com/v1"),
            "claude-sonnet-5", "https://api.anthropic.com/v1")
        val current = legacy.copy(id = modelProfileId("claude-sonnet-5-5", legacy.baseUrl), model = "claude-sonnet-5-5")
        store.write(listOf(current, legacy))
        store.setActive(legacy)
        val reloaded = store.read()
        assertEquals(2, reloaded.size)
        assertEquals(setOf(current.id, legacy.id), reloaded.map { it.id }.toSet())
        assertEquals(legacy.id, store.active("claude-sonnet-5-5", legacy.baseUrl, reloaded)?.id)
        assertEquals("claude-sonnet-5-5", reloaded.first { it.id == legacy.id }.model)
        val draft = kotlinx.coroutines.runBlocking {
            resolveLocalModelApiKeyDraft("", "claude-sonnet-5-5", legacy.baseUrl, null, reloaded,
                { id -> if (id == legacy.id) "legacy-key" else "current-key" }, legacy.id)
        }
        assertEquals(legacy.id, draft.profile.id)
        assertEquals("legacy-key", draft.key)
    }


    @Test
    fun persistedPlanProfileAlwaysReloadsAsResponsesRegardlessOfLegacyProtocolValue() {
        val preferences = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
        val json = Json { ignoreUnknownKeys = true }
        val store = LocalModelProfileStore(preferences, json)
        val legacy = planProfile("account-a").copy(protocol = LocalModelProtocol.CHAT_COMPLETIONS)

        store.write(listOf(legacy))

        val reloaded = LocalModelProfileStore(preferences, json).read().single()
        assertEquals(LocalModelAuthKind.CHATGPT_PLAN, reloaded.authKind)
        assertEquals(LocalModelProtocol.RESPONSES, reloaded.protocol)
        assertEquals(legacy.id, reloaded.id)
    }

    @Test
    fun durableMetadataAndUiProjectionCannotBeOvertakenByLaterSave() = runBlocking {
        val preferences = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
        val store = LocalModelProfileStore(preferences, Json)
        val visible = MutableStateFlow(LocalHarnessState())
        val projection = LocalRuntimeProjection(visible)
        val routeA = LocalModelProfile("route-a", "model-a", "https://a.example")
        val routeB = LocalModelProfile("route-b", "model-b", "https://b.example")
        val firstPersisted = CompletableDeferred<Unit>()
        val finishFirstProjection = CompletableDeferred<Unit>()
        val first = async(Dispatchers.Default) {
            LocalModelMutationGate.run {
                store.commitConfiguration(listOf(routeA), routeA, "txn-a")
                firstPersisted.complete(Unit)
                // Pause AFTER durable metadata, BEFORE projection, while retaining the gate.
                finishFirstProjection.await()
                projection.projectModelConfiguration(
                    true, routeA.model, routeA.baseUrl, listOf(routeA), routeA.id, null,
                )
            }
        }
        firstPersisted.await()
        val second = async(Dispatchers.Default) {
            LocalModelMutationGate.run {
                store.commitConfiguration(listOf(routeA, routeB), routeB, "txn-b")
                projection.projectModelConfiguration(
                    true, routeB.model, routeB.baseUrl, listOf(routeA, routeB), routeB.id, null,
                )
            }
        }
        finishFirstProjection.complete(Unit)
        first.await()
        second.await()
        assertEquals("txn-b", store.transactionId())
        assertEquals(store.read(), visible.value.modelState.modelSelection.profiles)
        assertEquals(routeB.id, visible.value.modelState.modelSelection.activeProfileId)
        assertEquals(routeB.id, store.active(routeB.model, routeB.baseUrl)?.id)
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

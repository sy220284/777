package com.labteto.dshmobile.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.TokenUsageAggregate
import com.labteto.dshmobile.local.TokenUsageAnalyticsSnapshot
import com.labteto.dshmobile.local.TokenUsageRecord
import com.labteto.dshmobile.local.TokenUsageAnalyticsStore
import com.labteto.dshmobile.local.model.AnthropicMessagesClient
import com.labteto.dshmobile.local.model.DeepSeekPricingRepository
import com.labteto.dshmobile.local.model.DeepSeekUsageTracker
import com.labteto.dshmobile.local.model.LocalCanonicalContent
import com.labteto.dshmobile.local.model.LocalCanonicalMessage
import com.labteto.dshmobile.local.model.LocalCanonicalRole
import com.labteto.dshmobile.local.model.LocalModelAuthKind
import com.labteto.dshmobile.local.model.LocalModelProtocol
import com.labteto.dshmobile.local.model.LocalModelRouteIdentity
import com.labteto.dshmobile.local.model.LocalModelRuntimeCapabilities
import com.labteto.dshmobile.local.model.LocalResolvedModelRoute
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import com.labteto.dshmobile.local.model.DeepSeekUsageSnapshot
import com.labteto.dshmobile.ui.theme.DshTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class UsageCalculationPageTest {
    @get:Rule val compose = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun show(usage: DeepSeekUsageSnapshot, onOpenPricing: () -> Unit = {}) {
        compose.setContent {
            DshTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    UsageCalculationPage(usage, onOpenPricing)
                }
            }
        }
    }

    @Test
    fun modelSwitcherUpdatesHeroAndKeepsModelsSeparated() {
        val analytics = TokenUsageAnalyticsSnapshot(
            byModel = mapOf(
                "MiniMax-M3" to TokenUsageAnalyticsSnapshot(
                    tracked = TokenUsageAggregate(inputTokens = 400, outputTokens = 50),
                ),
                "deepseek-chat" to TokenUsageAnalyticsSnapshot(
                    tracked = TokenUsageAggregate(inputTokens = 40, outputTokens = 10),
                ),
            ),
        )
        compose.setContent {
            DshTheme {
                var selected by remember { mutableStateOf<String?>(null) }
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    UsageCalculationPage(
                        usage = DeepSeekUsageSnapshot(inputTokens = 440, outputTokens = 60),
                        onOpenPricing = {},
                        analytics = analytics,
                        configuredModels = listOf("MiniMax-M3", "deepseek-chat", "never-used"),
                        selectedModel = selected,
                        onSelectModel = { selected = it },
                    )
                }
            }
        }
        compose.onNodeWithText("全部模型 ▾").performClick()
        compose.onNodeWithTag("menu-item:MiniMax-M3").performClick()
        compose.onNodeWithText("450").assertExists()
        compose.onNodeWithText("MiniMax-M3 ▾").performClick()
        compose.onNodeWithTag("menu-item:deepseek-chat").performClick()
        compose.onNodeWithText("50").assertExists()
        compose.onNodeWithText("deepseek-chat ▾").performClick()
        compose.onNodeWithTag("menu-item:MiniMax-M3").performClick()
        compose.onNodeWithText("450").assertExists()
        compose.onNodeWithText("MiniMax-M3 ▾").performClick()
        compose.onNodeWithText("never-used").performClick()
        compose.onNodeWithText("never-used ▾").assertExists()
        compose.onNodeWithText("450").assertDoesNotExist()
        compose.onAllNodesWithText("0").onFirst().assertExists()
    }

    @Test
    fun requestDetailShowsCacheWriteAndReasoningWithoutInflatingTotals() {
        val record = TokenUsageRecord(
            requestId = "cache-write-detail",
            timestamp = System.currentTimeMillis(),
            model = "MiniMax-M3",
            inputTokens = 10L,
            cacheHitTokens = 2L,
            cacheMissTokens = 8L,
            cacheWriteTokens = 3L,
            outputTokens = 7L,
            reasoningTokens = 4L,
            reported = true,
        )
        compose.setContent {
            DshTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    UsageRequestDetailPage(record)
                }
            }
        }

        compose.onNodeWithText(context.getString(R.string.usage_calculation_cache_write)).assertExists()
        compose.onNodeWithText("3").assertExists()
        compose.onNodeWithText("17").assertExists()
        compose.onNodeWithText(context.getString(R.string.usage_calculation_reasoning_note, "4")).assertExists()
    }

    @Test
    fun providerStreamPersistsSixCountersAndRendersFromRealLedger() = runBlocking {
        val requestId = "usage-e2e-${UUID.randomUUID()}"
        val events = listOf(
            """{"type":"message_start","message":{"id":"$requestId","usage":{"input_tokens":5,"cache_read_input_tokens":2,"cache_creation_input_tokens":3,"output_tokens":0,"output_tokens_details":{"thinking_tokens":1}}}}""",
            """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"完成"}}""",
            """{"type":"content_block_stop","index":0}""",
            """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":7,"output_tokens_details":{"thinking_tokens":4}}}""",
            """{"type":"message_stop"}""",
        )
        val payload = events.joinToString(separator = "\n\n", postfix = "\n\n") { "data: $it" }
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .header("Content-Type", "text/event-stream")
                .body(payload.toResponseBody("text/event-stream".toMediaType()))
                .build()
        }.build()
        val json = Json { ignoreUnknownKeys = true }
        val route = LocalResolvedModelRoute(
            profileId = "usage-test-minimax",
            provider = "MiniMax",
            model = "MiniMax-M3",
            baseUrl = "https://api.minimaxi.com/anthropic/v1",
            authKind = LocalModelAuthKind.API_KEY,
            protocol = LocalModelProtocol.ANTHROPIC_MESSAGES,
            bearerToken = "local-test-only",
            capabilities = LocalModelRuntimeCapabilities(),
        )
        val reply = AnthropicMessagesClient(http, json).complete(
            route = route,
            messages = listOf(
                LocalCanonicalMessage(
                    LocalCanonicalRole.USER,
                    listOf(LocalCanonicalContent.Text("核算 Token")),
                ),
            ),
            tools = emptyList(),
            temperature = null,
            streaming = true,
        )
        assertEquals(requestId, reply.requestId)
        val store = TokenUsageAnalyticsStore(context, json)
        val tracker = DeepSeekUsageTracker(
            context,
            DeepSeekPricingRepository(context, http),
            store,
        )
        tracker.reconcileLegacyUsage()
        val before = store.snapshot().tracked
        tracker.record(
            model = "deepseek-chat", // UI changed while the MiniMax request was in flight.
            usage = reply.usage,
            requestId = reply.requestId,
            route = LocalModelRouteIdentity(
                profileId = route.profileId,
                provider = route.provider,
                model = route.model,
                baseUrl = route.baseUrl,
            ),
        )
        val record = store.recordById(requestId)
        assertNotNull(record)
        val saved = requireNotNull(record)
        assertEquals("MiniMax-M3", saved.model)
        assertEquals(10L, saved.inputTokens)
        assertEquals(2L, saved.cacheHitTokens)
        assertEquals(8L, saved.cacheMissTokens)
        assertEquals(3L, saved.cacheWriteTokens)
        assertEquals(7L, saved.outputTokens)
        assertEquals(4L, saved.reasoningTokens)
        assertEquals(17L, saved.totalTokens)
        val after = store.snapshot()
        assertEquals(before.totalTokens + 17L, after.tracked.totalTokens)
        assertEquals(before.cacheWriteTokens + 3L, after.tracked.cacheWriteTokens)
        assertEquals(before.reasoningTokens + 4L, after.tracked.reasoningTokens)
        assertTrue(after.byModel.getValue("MiniMax-M3").tracked.totalTokens >= 17L)
        compose.setContent {
            DshTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    UsageRequestDetailPage(saved)
                }
            }
        }
        compose.onNodeWithText(context.getString(R.string.usage_calculation_cache_write)).assertExists()
        compose.onNodeWithText("3").assertExists()
        compose.onNodeWithText("17").assertExists()
        compose.onNodeWithText(context.getString(R.string.usage_calculation_reasoning_note, "4")).assertExists()
        Unit
    }

    @Test
    fun compactMetricsRemainReadableWithoutNestedMaterialTiles() {
        show(DeepSeekUsageSnapshot(inputTokens = 200, outputTokens = 25))
        compose.onAllNodesWithTag("usagePrimaryMetric").assertCountEquals(2)
        compose.onNodeWithText("225").assertExists()
    }

    @Test
    fun recordedUsageShowsMeasuredHitRateAndOpensPricing() {
        var pricingOpens = 0
        show(
            DeepSeekUsageSnapshot(
                inputTokens = 100,
                cacheHitTokens = 75,
                cacheMissTokens = 25,
                outputTokens = 25,
                reasoningTokens = 10,
                requestCount = 2,
                unreportedRequestCount = 1,
                estimatedCostCny = 0.1234,
                unpricedTokens = 3,
                updatedAt = 1_700_000_000_000L,
            ),
            onOpenPricing = { pricingOpens++ },
        )

        compose.onNodeWithText("¥0.1234", substring = true).assertExists()
        compose.onNodeWithText("75.0%").assertExists()
        compose.onNodeWithText(context.getString(R.string.usage_calculation_partial)).assertExists()
        compose.onNodeWithText(context.getString(R.string.usage_calculation_view_prices))
            .performScrollTo()
            .performClick()
        assertEquals(1, pricingOpens)
    }

    @Test
    fun freshInstallationShowsNoMeasuredHitRateOrWarning() {
        show(DeepSeekUsageSnapshot())

        compose.onNodeWithText(context.getString(R.string.usage_calculation_no_data)).assertExists()
        compose.onNodeWithText("0.0%").assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.usage_calculation_partial)).assertDoesNotExist()
    }
}

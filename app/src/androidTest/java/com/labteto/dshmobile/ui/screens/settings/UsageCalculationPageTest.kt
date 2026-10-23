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

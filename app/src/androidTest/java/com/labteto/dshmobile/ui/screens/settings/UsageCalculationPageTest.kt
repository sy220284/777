package com.labteto.dshmobile.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.DeepSeekUsageSnapshot
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

        compose.onNodeWithText("¥0.1234").assertExists()
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

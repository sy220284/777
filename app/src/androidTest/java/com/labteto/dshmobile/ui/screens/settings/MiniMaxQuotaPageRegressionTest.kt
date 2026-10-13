package com.labteto.dshmobile.ui.screens.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.model.MiniMaxProviderUsage
import com.labteto.dshmobile.local.model.LocalModelProfile
import com.labteto.dshmobile.ui.theme.DshTheme
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import java.util.concurrent.atomic.AtomicInteger

class MiniMaxQuotaPageRegressionTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun delayedConfigurationAndMissingProfileStayOnQuotaUntilExplicitConfigurationClick() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val profile = mutableStateOf<LocalModelProfile?>(null)
        val loading = mutableStateOf(true)
        val queries = AtomicInteger()
        var configured = 0
        compose.setContent {
            DshTheme { Column {
                androidx.compose.material3.Text(context.getString(R.string.minimax_quota_title))
                MiniMaxQuotaPage(
                    profile = profile.value, loadingProfile = loading.value,
                    onConfigureModel = { configured++ },
                    queryQuota = {
                        queries.incrementAndGet()
                        MiniMaxProviderUsage.AccountBalance("10", "8", "2", "0")
                    },
                )
            } }
        }
        compose.onNodeWithText(context.getString(R.string.minimax_quota_loading_profile)).assertExists()
        compose.runOnIdle { assertEquals(0, queries.get()); loading.value = false }
        compose.onNodeWithText(context.getString(R.string.minimax_quota_no_profile)).assertExists()
        compose.runOnIdle { assertEquals(0, configured); assertEquals(0, queries.get()) }
        compose.runOnIdle { profile.value = LocalModelProfile("mini", "MiniMax-M3", "https://api.minimaxi.com/v1") }
        val balance = context.getString(R.string.minimax_quota_balance_available, "10")
        compose.waitUntil(5_000) { compose.onAllNodesWithText(balance).fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle { assertEquals(1, queries.get()); profile.value = null }
        compose.onNodeWithText(balance).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.minimax_quota_title)).assertExists()
        compose.runOnIdle { assertEquals(0, configured) }
        compose.onNodeWithText(context.getString(R.string.work_capability_configure_model)).performClick()
        compose.runOnIdle { assertEquals(1, configured) }
    }

    @Test
    fun failedQuotaQueryShowsErrorAndRetriesWithoutOpeningConfiguration() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val queries = AtomicInteger()
        var configured = 0
        compose.setContent {
            DshTheme { Column {
                MiniMaxQuotaPage(
                    profile = LocalModelProfile("mini", "MiniMax-M3", "https://api.minimaxi.com/v1"),
                    loadingProfile = false, onConfigureModel = { configured++ },
                    queryQuota = {
                        if (queries.incrementAndGet() == 1) throw IllegalStateException("offline")
                        MiniMaxProviderUsage.TokenPlan(emptyList())
                    },
                )
            } }
        }
        val failed = context.getString(R.string.minimax_quota_error, "offline")
        compose.waitUntil(5_000) { compose.onAllNodesWithText(failed).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(context.getString(R.string.minimax_quota_refresh)).performClick()
        val empty = context.getString(R.string.minimax_quota_empty)
        compose.waitUntil(5_000) { compose.onAllNodesWithText(empty).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(failed).assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, configured); assertEquals(2, queries.get()) }
    }

    @Test
    fun narrowLargeTextQuotaShowsTwoWindowsAndLocalizedName() {
        compose.setContent {
            DshTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f)) {
                    Box(androidx.compose.ui.Modifier.width(280.dp)) {
                        MiniMaxQuotaCard(MiniMaxProviderUsage.ModelQuota(
                            name = "general",
                            interval = MiniMaxProviderUsage.Window(null, null, 83.0),
                            weekly = MiniMaxProviderUsage.Window(null, null, 94.0),
                            intervalResetMillis = 13_080_000L, weeklyResetMillis = 579_600_000L,
                        ))
                    }
                }
            }
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.onNodeWithText("general").assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.minimax_quota_shared)).assertExists()
        compose.onNodeWithText(context.getString(R.string.minimax_quota_interval_label)).assertExists()
        compose.onNodeWithText(context.getString(R.string.minimax_quota_weekly_label)).assertExists()
        compose.onNodeWithText(context.getString(R.string.minimax_quota_remaining_percent, "83.0")).assertExists()
        compose.onNodeWithText(context.getString(R.string.minimax_quota_remaining_percent, "94.0")).assertExists()
    }
}

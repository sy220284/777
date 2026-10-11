package com.labteto.dshmobile.ui.screens.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.model.MiniMaxProviderUsage
import com.labteto.dshmobile.ui.theme.DshTheme
import org.junit.Rule
import org.junit.Test

class MiniMaxQuotaPageRegressionTest {
    @get:Rule val compose = createComposeRule()

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

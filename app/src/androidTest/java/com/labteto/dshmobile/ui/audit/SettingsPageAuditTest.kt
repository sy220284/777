package com.labteto.dshmobile.ui.audit

import android.Manifest
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.MainActivity
import com.labteto.dshmobile.R
import com.labteto.dshmobile.ui.screens.settings.SettingsDestination
import com.labteto.dshmobile.ui.screens.settings.SettingsScreen
import com.labteto.dshmobile.ui.shenyan.SY_COMPOSER_FIELD_TAG
import com.labteto.dshmobile.ui.theme.DshTheme
import com.labteto.dshmobile.ui.theme.ThemePreference
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Render the real settings pages with real Hilt owners; no fake account or usage data. */
@RunWith(Parameterized::class)
class SettingsPageAuditTest(
    private val destination: SettingsDestination,
    private val theme: ThemePreference,
    private val fontScale: Float,
    private val width: Int,
) {
    private val compose = createAndroidComposeRule<MainActivity>()
    private val permission = object : ExternalResource() {
        override fun before() {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            instrumentation.uiAutomation.grantRuntimePermission(
                instrumentation.targetContext.packageName, Manifest.permission.POST_NOTIFICATIONS,
            )
        }
    }
    @get:Rule val rules: RuleChain = RuleChain.outerRule(permission).around(compose)

    @Test fun pageRendersWithItsBackActionAndKeepsNativeVisualEvidence() {
        val onboardingSkip = compose.activity.getString(R.string.shenyan_onboarding_skip)
        compose.waitUntil(30_000) {
            compose.onAllNodesWithText(onboardingSkip).fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithTag(SY_COMPOSER_FIELD_TAG, useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()
        }
        if (compose.onAllNodesWithText(onboardingSkip).fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithText(onboardingSkip).performClick()
        }
        compose.waitUntil(30_000) {
            compose.onAllNodesWithTag(SY_COMPOSER_FIELD_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.activityRule.scenario.onActivity { activity ->
            activity.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                    DshTheme(preference = theme) {
                        Box(Modifier.width(width.dp).fillMaxHeight().testTag("settings-audit-frame")) {
                            SettingsScreen(onClose = {}, initialDestination = destination)
                        }
                    }
                }
            }
        }
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.common_back))
            .assertExists()
        captureScrollableUiAudit(compose, "settings-${destination.name}-${theme.name}-$fontScale-$width", "settings-audit-frame")
        val modelUsageTitle = compose.activity.getString(R.string.settings_page_model_usage)
        if (destination == SettingsDestination.ROOT) {
            // The entry belongs to settings home and must keep the existing usage navigation intact.
            compose.onNodeWithText(modelUsageTitle).assertExists()
            compose.onNodeWithText(modelUsageTitle).performScrollTo().performClick()
            compose.waitForIdle()
            compose.onNodeWithText(compose.activity.getString(R.string.settings_page_pricing)).assertExists()
            compose.onNodeWithText(compose.activity.getString(R.string.usage_calculation_title)).assertExists()
            compose.onNodeWithContentDescription(compose.activity.getString(R.string.common_back)).performClick()
            compose.waitForIdle()
            compose.onNodeWithText(modelUsageTitle).assertExists()
        }
        if (destination == SettingsDestination.MODELS) {
            // Model configuration is focused on model management and image processing.
            compose.onNodeWithText(modelUsageTitle).assertDoesNotExist()
        }
        if (destination == SettingsDestination.PRICING) {
            val prices = compose.onAllNodes(SemanticsMatcher("complete numeric prices") { node ->
                node.config.getOrNull(SemanticsProperties.Text)?.any {
                    it.text.matches(Regex("[0-9]+(\\.[0-9]+)?"))
                } == true
            }, useUnmergedTree = true)
            assertTrue("The real price table must contain values", prices.fetchSemanticsNodes().isNotEmpty())
            for (index in prices.fetchSemanticsNodes().indices) {
                val layouts = mutableListOf<TextLayoutResult>()
                prices[index].performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                assertTrue(layouts.isNotEmpty())
                assertFalse("Prices must not be clipped", layouts.single().hasVisualOverflow)
                assertEquals("A decimal price must stay on one reading line", 1, layouts.single().lineCount)
            }
        }
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}-{1}-{2}-{3}dp")
        fun cases(): List<Array<Any>> = SettingsDestination.entries.map {
            arrayOf<Any>(it, ThemePreference.LIGHT, 1f, 390)
        } + listOf(SettingsDestination.ROOT, SettingsDestination.APPEARANCE,
            SettingsDestination.PERMISSIONS, SettingsDestination.NOTIFICATIONS).flatMap { page ->
            listOf(ThemePreference.DARK, ThemePreference.MATTE_BLACK).flatMap { theme ->
                listOf(1.3f, 2f).map { scale -> arrayOf<Any>(page, theme, scale, 320) }
            }
        } + SettingsDestination.entries.filterNot { it in setOf(
            SettingsDestination.ROOT, SettingsDestination.APPEARANCE,
            SettingsDestination.PERMISSIONS, SettingsDestination.NOTIFICATIONS,
        ) }.flatMap { page -> listOf(
            arrayOf<Any>(page, ThemePreference.DARK, 2f, 320),
            arrayOf<Any>(page, ThemePreference.MATTE_BLACK, 1.3f, 320),
        ) }
    }
}

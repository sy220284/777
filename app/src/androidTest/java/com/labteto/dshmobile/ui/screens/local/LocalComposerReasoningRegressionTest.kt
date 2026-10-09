package com.labteto.dshmobile.ui.screens.local

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.model.LocalModelProfile
import com.labteto.dshmobile.local.model.LocalReasoningModeStore
import com.labteto.dshmobile.local.presentation.LocalReasoningControls
import com.labteto.dshmobile.local.presentation.LocalReasoningUiMode
import com.labteto.dshmobile.ui.theme.DshTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class LocalComposerReasoningRegressionTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val deepSeek = LocalModelProfile("composer-ds", "deepseek-flash", "https://api.deepseek.com")

    @Test fun webButtonOnlyAppearsInWorkModeAndStalePanelIsHiddenInChat() {
        val mode = mutableStateOf(LocalUsageMode.CHAT)
        val panel = mutableStateOf<String?>(null)
        compose.setContent {
            DshTheme {
                LocalComposerCapabilityActions(
                    profile = deepSeek, usageMode = mode.value,
                    reasoningMode = LocalReasoningUiMode.DEFAULT,
                    networkSearchEnabled = true,
                    running = false, showLabels = true,
                    openPanel = panel.value,
                    onSelectPanel = { panel.value = it },
                    onReasoningModeChange = {},
                    onNetworkSearchChange = {},
                    onWorkSearchRequested = {},
                )
            }
        }
        val onlineAction = context.getString(R.string.local_composer_web_on_action)
        val onlineLabel = context.getString(R.string.local_composer_web_online)
        val webTitle = context.getString(R.string.local_composer_web_title)
        compose.onNodeWithContentDescription(onlineAction).assertDoesNotExist()
        compose.onNodeWithText(onlineLabel).assertDoesNotExist()
        compose.onNodeWithContentDescription(context.getString(
            R.string.local_composer_reasoning_action,
            context.getString(R.string.local_composer_reasoning_default),
        )).assertIsDisplayed()

        compose.runOnIdle { mode.value = LocalUsageMode.WORK }
        compose.onNodeWithContentDescription(onlineAction).assertIsDisplayed()
        compose.onNodeWithText(onlineLabel).assertIsDisplayed()
        compose.runOnIdle { panel.value = "web" }
        compose.onNodeWithText(webTitle).assertIsDisplayed()

        compose.runOnIdle { mode.value = LocalUsageMode.CHAT }
        compose.onNodeWithContentDescription(onlineAction).assertDoesNotExist()
        compose.onNodeWithText(onlineLabel).assertDoesNotExist()
        compose.onNodeWithText(webTitle).assertDoesNotExist()
    }

    @Test fun webSliderAnnouncesSelectionAndCommitsAccessibleAction() {
        val enabled = mutableStateOf(false)
        compose.setContent {
            DshTheme {
                LocalComposerCapabilityPanel(
                    panel = "web", profile = deepSeek, usageMode = LocalUsageMode.WORK,
                    reasoningMode = LocalReasoningUiMode.DEFAULT, onReasoningModeChange = {},
                    networkSearchEnabled = enabled.value,
                    onNetworkSearchChange = { enabled.value = it }, onWorkSearchRequested = {},
                )
            }
        }
        val slider = compose.onNodeWithContentDescription(context.getString(R.string.local_composer_web_title))
        slider.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,
            context.getString(R.string.local_composer_web_off)))
        slider.performSemanticsAction(SemanticsActions.SetProgress) { it(1f) }
        compose.runOnIdle { assertEquals(true, enabled.value) }
        slider.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,
            context.getString(R.string.local_composer_web_on)))
    }

    @Test fun approvalSliderAnnouncesNamedModesAndCommitsAccessibleAction() {
        val selected = mutableStateOf(0)
        val labels = listOf(R.string.local_composer_approval_default,
            R.string.local_composer_approval_manual, R.string.local_composer_approval_auto)
            .map { context.getString(it) }
        val title = context.getString(R.string.local_composer_approval_title)
        compose.setContent {
            DshTheme {
                LocalComposerSheetSlider(title = title, labels = labels,
                    selectedIndex = selected.value, onSelect = { selected.value = it })
            }
        }
        val slider = compose.onNodeWithContentDescription(title)
        for (index in labels.indices) {
            if (index > 0) slider.performSemanticsAction(SemanticsActions.SetProgress) { it(index.toFloat()) }
            compose.runOnIdle { assertEquals(index, selected.value) }
            slider.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, labels[index]))
        }
    }

    @Test fun modelSwitchKeepsGpt6MaxReasoningControlAvailable() {
        val profile = mutableStateOf(deepSeek)
        compose.setContent {
            DshTheme {
                LocalComposerCapabilityActions(
                    profile = profile.value, usageMode = LocalUsageMode.CHAT,
                    reasoningMode = LocalReasoningUiMode.MAX, networkSearchEnabled = true,
                    running = false, showLabels = true, openPanel = "reasoning",
                    onSelectPanel = {}, onReasoningModeChange = {},
                    onNetworkSearchChange = {}, onWorkSearchRequested = {},
                )
            }
        }
        compose.onNodeWithContentDescription(context.getString(R.string.local_composer_reasoning_title))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,
                context.getString(R.string.local_composer_reasoning_max)))
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo,
                ProgressBarRangeInfo(4f, 0f..4f, 3)))
        compose.runOnIdle {
            profile.value = LocalModelProfile("composer-api", "gpt-6-luna", "https://api.openai.com/v1")
        }
        compose.onNodeWithContentDescription(context.getString(R.string.local_composer_reasoning_action,
            context.getString(R.string.local_composer_reasoning_max))).assertIsDisplayed()
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo,
                ProgressBarRangeInfo(4f, 0f..4f, 3)))
        compose.onNodeWithText(context.getString(R.string.local_composer_reasoning_default_tip)).assertDoesNotExist()
        compose.onNodeWithContentDescription(context.getString(R.string.local_composer_reasoning_title))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,
                context.getString(R.string.local_composer_reasoning_max)))
    }

    @Test fun deepSeekSliderCanRestoreProviderDefault() {
        val session = "composer-restore-default-regression"
        LocalReasoningControls.setMode(session, LocalReasoningUiMode.DEEP)
        val mode = mutableStateOf(LocalReasoningControls.mode(session))
        try {
            compose.setContent {
                DshTheme {
                    LocalComposerCapabilityPanel(
                        panel = "reasoning", profile = deepSeek, usageMode = LocalUsageMode.CHAT,
                        reasoningMode = mode.value,
                        onReasoningModeChange = {
                            LocalReasoningControls.setMode(session, it)
                            mode.value = LocalReasoningControls.mode(session)
                        },
                        networkSearchEnabled = true, onNetworkSearchChange = {}, onWorkSearchRequested = {},
                    )
                }
            }
            compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))
                .performSemanticsAction(SemanticsActions.SetProgress) { setProgress -> setProgress(0f) }
            compose.runOnIdle {
                assertEquals(LocalReasoningUiMode.DEFAULT, LocalReasoningControls.mode(session))
                assertNull(LocalReasoningModeStore.effortFor(session, deepSeek))
            }
            compose.onNodeWithText(context.getString(R.string.local_composer_reasoning_default_tip)).assertIsDisplayed()
        } finally {
            LocalReasoningControls.restoreDefault(session)
        }
    }
}

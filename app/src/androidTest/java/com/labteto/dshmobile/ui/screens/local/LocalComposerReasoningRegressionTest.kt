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

    @Test fun modelSwitchUpdatesButtonSliderAndDefaultHintTogether() {
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
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo,
                ProgressBarRangeInfo(4f, 0f..4f, 3)))
        compose.runOnIdle {
            profile.value = LocalModelProfile("composer-api", "gpt-6-luna", "https://api.openai.com/v1")
        }
        compose.onNodeWithContentDescription(context.getString(R.string.local_composer_reasoning_action,
            context.getString(R.string.local_composer_reasoning_default))).assertIsDisplayed()
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo,
                ProgressBarRangeInfo(0f, 0f..3f, 2)))
        compose.onNodeWithText(context.getString(R.string.local_composer_reasoning_default_tip)).assertIsDisplayed()
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

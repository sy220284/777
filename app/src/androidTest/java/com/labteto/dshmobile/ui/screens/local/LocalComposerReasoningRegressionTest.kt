package com.labteto.dshmobile.ui.screens.local

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
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

    @Test fun modelSwitchRefreshesReasoningModesWithoutDroppingSupportedMax() {
        val profile = mutableStateOf(deepSeek)
        compose.setContent {
            DshTheme {
                LocalComposerCapabilityActions(
                    profile = profile.value, usageMode = LocalUsageMode.CHAT,
                    reasoningMode = LocalReasoningUiMode.MAX,
                    running = false, showLabels = true, openPanel = "reasoning",
                    onSelectPanel = {}, onReasoningModeChange = {},
                )
            }
        }
        compose.onNodeWithContentDescription(context.getString(R.string.local_composer_reasoning_title))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,
                context.getString(R.string.local_composer_reasoning_max)))
        compose.onNodeWithContentDescription(context.getString(R.string.local_composer_reasoning_title))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo,
                ProgressBarRangeInfo(4f, 0f..4f, 3)))

        // GPT-6 Luna supports MAX in Chat mode; a profile switch must not silently reset it.
        compose.runOnIdle {
            profile.value = LocalModelProfile("composer-api", "gpt-6-luna", "https://api.openai.com/v1")
        }
        compose.onNodeWithContentDescription(context.getString(R.string.local_composer_reasoning_action,
            context.getString(R.string.local_composer_reasoning_max))).assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.local_composer_reasoning_title))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo,
                ProgressBarRangeInfo(4f, 0f..4f, 3)))
        compose.onNodeWithText(context.getString(R.string.local_composer_reasoning_default_tip))
            .assertDoesNotExist()

        // Mandatory-reasoning models expose LOW through FAST, shortening the slider by one slot.
        compose.runOnIdle {
            profile.value = LocalModelProfile("composer-required", "gpt-6-astra",
                "https://api.openai.com/v1")
        }
        compose.onNodeWithContentDescription(context.getString(R.string.local_composer_reasoning_action,
            context.getString(R.string.local_composer_reasoning_max))).assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.local_composer_reasoning_title))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo,
                ProgressBarRangeInfo(3f, 0f..3f, 2)))

        // Unknown models have no native reasoning override: the action projects DEFAULT
        // and the open panel must not leave behind a stale slider.
        compose.runOnIdle {
            profile.value = LocalModelProfile("composer-unsupported", "unknown-model",
                "https://api.openai.com/v1")
        }
        compose.onNodeWithContentDescription(context.getString(R.string.local_composer_reasoning_action,
            context.getString(R.string.local_composer_reasoning_default))).assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.local_composer_reasoning_title))
            .assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.local_composer_reasoning_unsupported))
            .assertIsDisplayed()
    }


    @Test fun workTemperatureHasFiveStopsWithThreeLabels() {
        val current = mutableStateOf(2)
        val activeProfile = mutableStateOf(deepSeek)
        compose.setContent {
            DshTheme {
                LocalComposerCapabilityPanel(
                    panel = "reasoning", profile = activeProfile.value,
                    usageMode = LocalUsageMode.WORK,
                    reasoningMode = LocalReasoningUiMode.FAST,
                    temperatureLevel = current.value,
                    onReasoningModeChange = {},
                    onTemperatureLevelChange = { current.value = it },
                )
            }
        }
        val slider = compose.onNodeWithContentDescription(
            context.getString(R.string.local_composer_temperature_title),
        )
        slider.assert(SemanticsMatcher.expectValue(
            SemanticsProperties.ProgressBarRangeInfo,
            ProgressBarRangeInfo(2f, 0f..4f, 3),
        ))
        compose.onNodeWithText(context.getString(R.string.local_composer_temperature_work_low))
            .assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.local_composer_temperature_natural))
            .assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.local_composer_temperature_work_high))
            .assertIsDisplayed()
        slider.assert(SemanticsMatcher.expectValue(
            SemanticsProperties.StateDescription,
            context.getString(R.string.local_composer_temperature_value, 0.65),
        ))
        slider.performSemanticsAction(SemanticsActions.SetProgress) { setProgress -> setProgress(4f) }
        compose.runOnIdle { assertEquals(4, current.value) }
        slider.assert(SemanticsMatcher.expectValue(
            SemanticsProperties.StateDescription,
            context.getString(R.string.local_composer_temperature_value, 1.3),
        ))
        // The DeepSeek-specific product cap must not carry over to another provider.
        compose.runOnIdle {
            activeProfile.value = LocalModelProfile(
                "composer-gemini", "gemini-3.8-flash",
                "https://generativelanguage.googleapis.com/v1beta/openai",
            )
        }
        slider.assert(SemanticsMatcher.expectValue(
            SemanticsProperties.StateDescription,
            context.getString(R.string.local_composer_temperature_value, 2.0),
        ))
    }

    @Test fun chatTemperatureReflectsPersonaSliderWhenChangedOutsidePanel() {
        val current = mutableStateOf(2)
        compose.setContent {
            DshTheme {
                LocalComposerCapabilityPanel(
                    panel = "reasoning", profile = deepSeek,
                    usageMode = LocalUsageMode.CHAT,
                    reasoningMode = LocalReasoningUiMode.FAST,
                    temperatureLevel = current.value,
                    onReasoningModeChange = {},
                    onTemperatureLevelChange = { current.value = it },
                )
            }
        }
        compose.onNodeWithText(context.getString(R.string.local_composer_temperature_chat_low))
            .assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.local_composer_temperature_chat_high))
            .assertIsDisplayed()
        compose.runOnIdle { current.value = 4 }
        compose.onNodeWithContentDescription(context.getString(R.string.local_composer_temperature_title))
            .assert(SemanticsMatcher.expectValue(
                SemanticsProperties.ProgressBarRangeInfo,
                ProgressBarRangeInfo(4f, 0f..4f, 3),
            ))
    }

    @Test fun finePersonaValueDisplaysActualTemperatureBetweenFiveDetents() {
        val position = mutableStateOf(60)
        compose.setContent {
            DshTheme {
                LocalComposerCapabilityPanel(
                    panel = "reasoning", profile = deepSeek,
                    usageMode = LocalUsageMode.CHAT,
                    reasoningMode = LocalReasoningUiMode.FAST,
                    temperatureLevel = 2,
                    temperaturePosition = position.value,
                    onReasoningModeChange = {},
                    onTemperatureLevelChange = {},
                )
            }
        }
        // 60 maps directly into the capped 0..1.3 window, giving 0.78.
        val slider = compose.onNodeWithContentDescription(
            context.getString(R.string.local_composer_temperature_title),
        )
        slider.assert(SemanticsMatcher.expectValue(
            SemanticsProperties.StateDescription,
            context.getString(R.string.local_composer_temperature_value, 0.78),
        ))
        // The control has five detents: 60% is displayed at the nearest (50%) stop,
        // while StateDescription above retains the exact persona temperature (0.78).
        slider.assert(SemanticsMatcher.expectValue(
            SemanticsProperties.ProgressBarRangeInfo,
            ProgressBarRangeInfo(2f, 0f..4f, 3),
        ))
        compose.runOnIdle { position.value = 75 }
        slider.assert(SemanticsMatcher.expectValue(
            SemanticsProperties.StateDescription,
            context.getString(R.string.local_composer_temperature_value, 0.975),
        ))
    }

    @Test fun chatTemperatureDoesNotReboundWhileSavingOrAfterReplyRefresh() {
        val level = mutableStateOf(4)
        val position = mutableStateOf(100)
        val saving = mutableStateOf(false)
        val enabled = mutableStateOf(true)
        var proposedLevel: Int? = null
        compose.setContent {
            DshTheme {
                LocalComposerCapabilityPanel(
                    panel = "reasoning", profile = deepSeek,
                    usageMode = LocalUsageMode.CHAT,
                    reasoningMode = LocalReasoningUiMode.FAST,
                    temperatureLevel = level.value,
                    temperaturePosition = position.value,
                    temperatureEnabled = enabled.value,
                    temperatureSaving = saving.value,
                    onReasoningModeChange = {},
                    onTemperatureLevelChange = { chosen ->
                        proposedLevel = chosen
                        saving.value = true
                        enabled.value = false
                    },
                )
            }
        }
        val slider = compose.onNodeWithContentDescription(
            context.getString(R.string.local_composer_temperature_title),
        )
        slider.assert(SemanticsMatcher.expectValue(
            SemanticsProperties.ProgressBarRangeInfo,
            ProgressBarRangeInfo(4f, 0f..4f, 3),
        ))
        slider.performSemanticsAction(SemanticsActions.SetProgress) { setProgress -> setProgress(1f) }
        compose.runOnIdle { assertEquals(1, proposedLevel) }
        slider.assertIsNotEnabled()
        // Backend still reports the old maximum until the asynchronous save commits.
        slider.assert(SemanticsMatcher.expectValue(
            SemanticsProperties.ProgressBarRangeInfo,
            ProgressBarRangeInfo(1f, 0f..4f, 3),
        ))
        compose.runOnIdle {
            position.value = 25
            level.value = 1
            saving.value = false
            enabled.value = true
        }
        slider.assert(SemanticsMatcher.expectValue(
            SemanticsProperties.ProgressBarRangeInfo,
            ProgressBarRangeInfo(1f, 0f..4f, 3),
        ))
        // Sending a message, receiving its reply, and re-enabling the control must not
        // reset the slider to the former maximum.
        compose.runOnIdle { enabled.value = false }
        compose.runOnIdle { enabled.value = true }
        slider.assert(SemanticsMatcher.expectValue(
            SemanticsProperties.ProgressBarRangeInfo,
            ProgressBarRangeInfo(1f, 0f..4f, 3),
        ))
    }

    @Test fun disabledTemperatureShowsCorrectSavingAndGroupHint() {
        val saving = mutableStateOf(true)
        val group = mutableStateOf(false)
        compose.setContent {
            DshTheme {
                LocalComposerCapabilityPanel(
                    panel = "reasoning", profile = deepSeek,
                    usageMode = LocalUsageMode.CHAT,
                    reasoningMode = LocalReasoningUiMode.FAST,
                    onReasoningModeChange = {},
                    temperatureEnabled = false,
                    temperatureSaving = saving.value,
                    temperatureGroupChat = group.value,
                )
            }
        }
        compose.onNodeWithContentDescription(
            context.getString(R.string.local_composer_temperature_title),
        ).assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.local_composer_temperature_saving))
            .assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.local_composer_temperature_group_hint))
            .assertDoesNotExist()
        compose.runOnIdle { saving.value = false; group.value = true }
        compose.onNodeWithText(context.getString(R.string.local_composer_temperature_group_hint))
            .assertIsDisplayed()
    }

    @Test fun deepSeekThinkingDisablesTemperatureUntilFastSelected() {
        val selected = mutableStateOf(LocalReasoningUiMode.DEEP)
        compose.setContent {
            DshTheme {
                LocalComposerCapabilityPanel(
                    panel = "reasoning", profile = deepSeek,
                    usageMode = LocalUsageMode.CHAT,
                    reasoningMode = selected.value,
                    onReasoningModeChange = { selected.value = it },
                )
            }
        }
        val temperature = compose.onNodeWithContentDescription(
            context.getString(R.string.local_composer_temperature_title),
        )
        temperature.assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.local_composer_temperature_thinking))
            .assertIsDisplayed()
        compose.runOnIdle { selected.value = LocalReasoningUiMode.FAST }
        temperature.assertIsEnabled()
    }

    @Test fun planIconTogglesAndAnnouncesStateWithoutSlider() {
        val selected = mutableStateOf(false)
        compose.setContent {
            DshTheme {
                LocalComposerPlanAction(
                    selected = selected.value,
                    enabled = true,
                    waitingForApproval = false,
                    onToggle = { selected.value = !selected.value },
                )
            }
        }
        val control = compose.onNodeWithTag("local-composer-plan-toggle")
        control.assert(SemanticsMatcher.expectValue(
            SemanticsProperties.StateDescription,
            context.getString(R.string.local_composer_plan_off),
        ))
        control.performClick()
        compose.runOnIdle { assertEquals(true, selected.value) }
        control.assert(SemanticsMatcher.expectValue(
            SemanticsProperties.StateDescription,
            context.getString(R.string.local_composer_plan_start),
        ))
    }

    @Test fun planIconDisablesWhileRunningOrAwaitingApproval() {
        val running = mutableStateOf(false)
        val waiting = mutableStateOf(false)
        compose.setContent {
            DshTheme {
                LocalComposerPlanAction(
                    selected = false,
                    enabled = !running.value,
                    waitingForApproval = waiting.value,
                    onToggle = {},
                )
            }
        }
        val control = compose.onNodeWithTag("local-composer-plan-toggle")
        compose.runOnIdle { waiting.value = true }
        control.assertIsNotEnabled()
        compose.runOnIdle { waiting.value = false; running.value = true }
        control.assertIsNotEnabled()
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
                    )
                }
            }
            compose.onNodeWithContentDescription(context.getString(R.string.local_composer_reasoning_title))
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

package com.labteto.dshmobile.ui.components

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import com.labteto.dshmobile.ui.theme.DshTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class DsHapticControlsTest {
    @get:Rule val compose = createComposeRule()

    private class RecordingHaptics : HapticFeedback {
        var pulses = 0
        override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
            pulses++
        }
    }

    @Test fun segmentedOnlyTicksWhenSelectionChanges() {
        val feedback = RecordingHaptics()
        val selected = mutableStateOf("a")
        compose.setContent {
            CompositionLocalProvider(LocalHapticFeedback provides feedback) {
                DshTheme {
                    DsSegmented(
                        segments = listOf(DsSegment("a", "A"), DsSegment("b", "B")),
                        selectedKey = selected.value,
                        onSelect = { selected.value = it },
                        hapticOnChange = true,
                    )
                }
            }
        }
        compose.onNodeWithText("A").performClick()
        compose.runOnIdle { assertEquals(0, feedback.pulses) }
        compose.onNodeWithText("B").performClick()
        compose.runOnIdle { assertEquals(1, feedback.pulses) }
        compose.onNodeWithText("B").performClick()
        compose.runOnIdle { assertEquals(1, feedback.pulses) }
    }

    @Test fun discreteSliderChangesValueAndAvoidsDuplicateDetentPulses() {
        val feedback = RecordingHaptics()
        val value = mutableFloatStateOf(0f)
        compose.setContent {
            CompositionLocalProvider(LocalHapticFeedback provides feedback) {
                DshTheme {
                    DsSlider(
                        value = value.floatValue,
                        onValueChange = { value.floatValue = it },
                        valueRange = 0f..1f,
                        steps = 3,
                        hapticSegments = 4,
                        modifier = Modifier.testTag("detent-slider"),
                    )
                }
            }
        }
        val slider = compose.onNodeWithTag("detent-slider")
        slider.performSemanticsAction(SemanticsActions.SetProgress) { it(0.25f) }
        compose.runOnIdle { assertEquals(1, feedback.pulses) }
        slider.performSemanticsAction(SemanticsActions.SetProgress) { it(0.25f) }
        compose.runOnIdle { assertEquals(1, feedback.pulses) }
        slider.performSemanticsAction(SemanticsActions.SetProgress) { it(0.5f) }
        // Rapid moves may be rate-limited; value changes must still pass through.
        compose.runOnIdle { assertEquals(0.5f, value.floatValue, 0f) }
    }
}

package com.labteto.dshmobile.ui.components

import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.labteto.dshmobile.ui.theme.DsTheme

/**
 * 统一 value control: blue active rail, neutral inactive rail, no colored tick decoration.
 */
@Deprecated("V5: migrate callers to SySliderControl")
@Composable
fun DsSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null,
    hapticSegments: Int = 0,
) {
    val tick = rememberHapticTickFeedback(
        hapticTickIndex(value, valueRange.start, valueRange.endInclusive, hapticSegments),
    )
    com.shenyan.designsystem.components.SySliderControl(
        value = value,
        onValueChange = { next ->
            if (hapticSegments > 0) {
                tick(hapticTickIndex(next, valueRange.start, valueRange.endInclusive, hapticSegments))
            }
            onValueChange(next)
        },
        modifier = modifier, enabled = enabled, valueRange = valueRange,
        steps = steps, onValueChangeFinished = onValueChangeFinished,
    )
}

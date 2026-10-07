package com.labteto.dshmobile.ui.components

import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.labteto.dshmobile.ui.theme.DsTheme

/**
 * Kimi-style value control: blue active rail, neutral inactive rail, no colored tick decoration.
 */
@Composable
fun DsSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    val colors = DsTheme.colors
    Slider(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        valueRange = valueRange,
        steps = steps,
        onValueChangeFinished = onValueChangeFinished,
        colors = SliderDefaults.colors(
            thumbColor = colors.accent,
            activeTrackColor = colors.accent,
            inactiveTrackColor = colors.bgModulePlatform,
            activeTickColor = Color.Transparent,
            inactiveTickColor = Color.Transparent,
            disabledThumbColor = colors.labelCaption,
            disabledActiveTrackColor = colors.accent.copy(alpha = 0.32f),
            disabledInactiveTrackColor = colors.bgModulePlatform.copy(alpha = 0.6f),
            disabledActiveTickColor = Color.Transparent,
            disabledInactiveTickColor = Color.Transparent,
        ),
    )
}

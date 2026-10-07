package com.labteto.dshmobile.ui.components

import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.labteto.dshmobile.ui.theme.DsTheme

/** Kimi-style single-select control with neutral idle state and blue selection. */
@Composable
fun DsRadioButton(
    selected: Boolean,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = DsTheme.colors
    RadioButton(
        selected = selected,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = RadioButtonDefaults.colors(
            selectedColor = colors.accent,
            unselectedColor = colors.labelTertiary,
            disabledSelectedColor = colors.accent.copy(alpha = 0.36f),
            disabledUnselectedColor = colors.labelCaption,
        ),
    )
}

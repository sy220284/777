package com.labteto.dshmobile.ui.components

import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.labteto.dshmobile.ui.theme.DsTheme

/** 统一 multi-select control with one shared selection color. */
@Composable
fun DsCheckbox(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = DsTheme.colors
    Checkbox(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        enabled = enabled,
        colors = CheckboxDefaults.colors(
            checkedColor = colors.accent,
            uncheckedColor = colors.labelTertiary,
            checkmarkColor = colors.onAccent,
            disabledCheckedColor = colors.accent.copy(alpha = 0.36f),
            disabledUncheckedColor = colors.labelCaption,
        ),
    )
}

package com.labteto.dshmobile.ui.components

import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.labteto.dshmobile.ui.theme.DsTheme

/**
 * 统一 binary control.
 *
 * Material owns accessibility, focus and touch behavior; the Design System owns every visible
 * state so feature pages never expose a second switch language.
 */
@Composable
fun DsSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = DsTheme.colors
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedThumbColor = colors.onAccent,
            checkedTrackColor = colors.accent,
            checkedBorderColor = Color.Transparent,
            uncheckedThumbColor = colors.labelTertiary,
            uncheckedTrackColor = colors.bgModulePlatform,
            uncheckedBorderColor = colors.borderL2,
            disabledCheckedThumbColor = colors.onAccent.copy(alpha = 0.58f),
            disabledCheckedTrackColor = colors.accent.copy(alpha = 0.36f),
            disabledCheckedBorderColor = Color.Transparent,
            disabledUncheckedThumbColor = colors.labelCaption,
            disabledUncheckedTrackColor = colors.bgModulePlatform.copy(alpha = 0.6f),
            disabledUncheckedBorderColor = colors.borderL1,
        ),
    )
}

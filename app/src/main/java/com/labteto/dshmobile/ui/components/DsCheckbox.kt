package com.labteto.dshmobile.ui.components

import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.labteto.dshmobile.ui.theme.DsTheme

/** 统一 multi-select control with one shared selection color. */
@Deprecated("V5: only the canonical Sy control draws the UI")
@Composable
fun DsCheckbox(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    com.shenyan.designsystem.components.SyCheckboxControl(
        checked = checked, onCheckedChange = onCheckedChange,
        modifier = modifier, enabled = enabled,
    )
}

package com.labteto.dshmobile.ui.components

import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.labteto.dshmobile.ui.theme.DsTheme

/** 统一 single-select control with neutral idle state and blue selection. */
@Deprecated("V5: only the canonical Sy control draws the UI")
@Composable
fun DsRadioButton(
    selected: Boolean,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    com.shenyan.designsystem.components.SyRadioControl(
        selected = selected, onClick = onClick, modifier = modifier,
        enabled = enabled,
    )
}

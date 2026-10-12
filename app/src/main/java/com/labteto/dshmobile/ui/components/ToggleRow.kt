package com.labteto.dshmobile.ui.components

import androidx.compose.runtime.Composable
import com.shenyan.designsystem.components.SySheetChoiceRow

/** Controlled setting action; the canonical row owns Switch semantics and painting. */
@Composable
fun ToggleRow(label: String, checked: Boolean, hint: String? = null, onChange: () -> Unit) =
    SySheetChoiceRow(title = label, subtitle = hint, switchChecked = checked, onClick = onChange)

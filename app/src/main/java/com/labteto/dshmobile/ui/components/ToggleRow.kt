package com.labteto.dshmobile.ui.components

import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.shenyan.designsystem.components.SySheetChoiceRow

/** A single-line switch entry retains the former two-line touch target. */
@Composable
fun ToggleRow(label: String, checked: Boolean, subtitle: String? = null, onChange: () -> Unit) =
    SySheetChoiceRow(
        title = label,
        subtitle = subtitle,
        modifier = Modifier.heightIn(min = 72.dp),
        switchChecked = checked,
        onClick = onChange,
    )

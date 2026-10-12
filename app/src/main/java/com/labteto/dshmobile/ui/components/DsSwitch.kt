package com.labteto.dshmobile.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.shenyan.designsystem.components.SySwitchControl

/** Temporary business adapter: rendering belongs exclusively to the V5 component. */
@Deprecated("V5: replace call sites with SySwitchControl")
@Composable
fun DsSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) = SySwitchControl(
    checked = checked,
    onCheckedChange = onCheckedChange,
    modifier = modifier,
    enabled = enabled,
)

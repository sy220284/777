package com.labteto.dshmobile.ui.components
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
@Deprecated("V5: use SySheetChoiceRow")
@Composable
fun DsSheetChoiceRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailingText: String? = null,
    icon: ImageVector? = null,
    selected: Boolean = false,
    switchChecked: Boolean? = null,
    danger: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) = com.shenyan.designsystem.components.SySheetChoiceRow(
    title, modifier, subtitle, trailingText, icon, selected,
    switchChecked, danger, enabled, onClick,
)

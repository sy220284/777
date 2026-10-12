package com.labteto.dshmobile.ui.components
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.shenyan.designsystem.foundation.LocalSyColors
@Deprecated("V5: use SyIconButton")
@Composable
fun DsIconButton(
    icon: ImageVector?, contentDescription: String?, onClick: () -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true,
    tint: Color = LocalSyColors.current.textSecondary, iconSize: Dp = 20.dp,
    containerColor: Color = Color.Transparent, shadowElevation: Dp = 0.dp,
    selected: Boolean = false, iconPainter: Painter? = null,
) = com.shenyan.designsystem.components.SyIconButton(
    icon, contentDescription, onClick, modifier, enabled, tint,
    iconSize, containerColor, shadowElevation, selected, iconPainter,
)

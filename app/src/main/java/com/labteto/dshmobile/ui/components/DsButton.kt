package com.labteto.dshmobile.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.ui.shenyan.SyButton
import com.labteto.dshmobile.ui.shenyan.SyButtonStyle

/** Temporary name adapters. The old visual component has been retired. */
enum class DsButtonVariant { Primary, Info, Ghost, Outline, Danger }
enum class DsButtonSize { Normal, Small, Large }

/** Business call sites can migrate incrementally; all pixels come from SyButton. */
@Deprecated("V5: prefer SyButton and SyButtonStyle")
@Composable
fun DsButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    variant: DsButtonVariant = DsButtonVariant.Primary,
    size: DsButtonSize = DsButtonSize.Normal,
    icon: ImageVector? = null,
    loading: Boolean = false,
    onHoldRepeat: (() -> Unit)? = null,
) {
    SyButton(
        text = text,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        loading = loading,
        style = when (variant) {
            DsButtonVariant.Primary -> SyButtonStyle.PRIMARY
            DsButtonVariant.Info, DsButtonVariant.Ghost -> SyButtonStyle.GHOST
            DsButtonVariant.Outline -> SyButtonStyle.OUTLINE
            DsButtonVariant.Danger -> SyButtonStyle.DANGER
        },
        icon = icon,
        height = if (size == DsButtonSize.Large) 52.dp else 48.dp,
        onHoldRepeat = onHoldRepeat,
    )
}

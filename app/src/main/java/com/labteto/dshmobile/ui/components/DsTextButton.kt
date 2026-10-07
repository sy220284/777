package com.labteto.dshmobile.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme

/**
 * Quiet Kimi-style text action.
 *
 * Use for compact toolbar and dialog actions where a filled button would add too much visual
 * weight. Business screens should not depend on Material TextButton styling directly.
 */
@Composable
fun DsTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val colors = DsTheme.colors
    TextButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = DsSpacing.touchTarget),
        enabled = enabled,
        shape = DsShapes.row,
        colors = ButtonDefaults.textButtonColors(
            contentColor = colors.labelPrimary,
            disabledContentColor = colors.labelDimmed,
        ),
        contentPadding = PaddingValues(horizontal = DsSpacing.small),
        content = content,
    )
}

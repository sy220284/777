package com.labteto.dshmobile.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.labteto.dshmobile.ui.shenyan.SyMaterialFrame
import com.labteto.dshmobile.ui.shenyan.SyMaterialTier

/**
 * Transitional API only: visuals are delegated to the new V5 material system.
 * Delete once caller screens have changed from DsCard to SyMaterialFrame.
 * Existing business callbacks are intentionally untouched.
 */
@Deprecated("V5: migrate callers to SyMaterialFrame/SyGlassCard")
@Composable
fun DsCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(
        com.labteto.dshmobile.ui.theme.DsSpacing.tiny
    ),
    elevated: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    SyMaterialFrame(
        modifier = modifier.fillMaxWidth(),
        tier = if (elevated) SyMaterialTier.L4_OVERLAY else SyMaterialTier.L3_CARD,
        onClick = onClick,
        verticalArrangement = verticalArrangement,
        content = content,
    )
}

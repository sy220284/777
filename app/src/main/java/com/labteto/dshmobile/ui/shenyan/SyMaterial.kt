package com.labteto.dshmobile.ui.shenyan

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Compatibility names only. Canonical visuals live under com.shenyan.designsystem. */
enum class SyMaterialTier { L1_SECTION, L2_BAR, L3_CARD, L4_OVERLAY, L5_SOLID }

/**
 * Migration bridge, not a second material renderer.
 * Shared L1–L5 surface semantics, blur rules and accessibility live in the V5 library.
 */
@Composable
fun SyMaterialFrame(
    modifier: Modifier = Modifier,
    tier: SyMaterialTier = SyMaterialTier.L3_CARD,
    onClick: (() -> Unit)? = null,
    backdrop: (@Composable BoxScope.() -> Unit)? = null,
    horizontalPadding: Dp = 16.dp,
    verticalPadding: Dp = 14.dp,
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(10.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val alignedBackdrop: (@Composable () -> Unit)? =
        if (backdrop == null) null else {
            @Composable {
                Box(Modifier.fillMaxSize()) { backdrop() }
            }
        }
    com.shenyan.designsystem.components.SyMaterialFrame(
        modifier = modifier,
        tier = com.shenyan.designsystem.components.SyMaterialTier.valueOf(tier.name),
        onClick = onClick,
        backdrop = alignedBackdrop,
        horizontalPadding = horizontalPadding,
        verticalPadding = verticalPadding,
        verticalArrangement = verticalArrangement,
        content = content,
    )
}

/** Consistent, solid chrome above the canonical V5 glass layer. */
@Composable
fun SyTopChrome(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val p = LocalSyPalette.current
    Surface(
        modifier = modifier,
        color = p.topBar,
        border = BorderStroke(1.dp, p.border.copy(alpha = .5f)),
    ) {
        Box(Modifier.fillMaxWidth().height(SyDimensions.toolbarExpanded), content = content)
    }
}

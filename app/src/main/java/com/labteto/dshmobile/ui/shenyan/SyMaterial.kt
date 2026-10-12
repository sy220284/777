package com.labteto.dshmobile.ui.shenyan

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** V5 material grammar: transparent ancestors limited to three, then solid. */
enum class SyMaterialTier { L1_SECTION, L2_BAR, L3_CARD, L4_OVERLAY, L5_SOLID }

private fun SyPalette.colorFor(tier: SyMaterialTier): Color = when(tier) {
    SyMaterialTier.L1_SECTION -> l1
    SyMaterialTier.L2_BAR -> l2
    SyMaterialTier.L3_CARD -> l3
    SyMaterialTier.L4_OVERLAY -> l4
    SyMaterialTier.L5_SOLID -> solid
}
private fun SyMaterialTier.blurRadius(): Dp = when(this) {
    SyMaterialTier.L1_SECTION -> 18.dp
    SyMaterialTier.L2_BAR -> 28.dp
    SyMaterialTier.L3_CARD -> 20.dp
    SyMaterialTier.L4_OVERLAY -> 32.dp
    SyMaterialTier.L5_SOLID -> 0.dp
}
private fun SyMaterialTier.elevation(): Dp = when(this) {
    SyMaterialTier.L1_SECTION, SyMaterialTier.L5_SOLID -> 0.dp
    SyMaterialTier.L2_BAR -> 3.dp
    SyMaterialTier.L3_CARD -> 7.dp
    SyMaterialTier.L4_OVERLAY -> 16.dp
}

/**
 * Only the optional spatially aligned backdrop is blurred, NEVER the content.
 * When no backdrop is supplied, translucent paint is used with zero fake blur.
 * User-requested reduced transparency and >3 nested glass layers become opaque.
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
    val palette = LocalSyPalette.current
    val depth = LocalSyGlassDepth.current
    val resolved = if ((depth >= 3 || LocalSyReducedTransparency.current) &&
        tier != SyMaterialTier.L5_SOLID) SyMaterialTier.L5_SOLID else tier
    val shape = RoundedCornerShape(if (resolved == SyMaterialTier.L4_OVERLAY)
        SyDimensions.cornerOverlay else SyDimensions.cornerCard)
    val base = modifier.then(
        if (resolved.elevation() > 0.dp) Modifier.shadow(resolved.elevation(), shape)
        else Modifier
    )
    val fill = palette.colorFor(resolved)
    val nextDepth = if (resolved == SyMaterialTier.L5_SOLID) depth else depth + 1
    Surface(
        modifier = base,
        shape = shape,
        color = Color.Transparent,
        border = BorderStroke(1.dp, palette.border),
        onClick = onClick ?: {},
        enabled = onClick != null,
    ) {
        Box(Modifier.clip(shape)) {
            if (backdrop != null && resolved.blurRadius() > 0.dp) {
                Box(Modifier.matchParentSize().blur(
                    resolved.blurRadius(), BlurredEdgeTreatment.Unbounded
                )) { backdrop() }
            }
            Box(Modifier.matchParentSize().background(fill))
            CompositionLocalProvider(LocalSyGlassDepth provides nextDepth) {
                Column(
                    Modifier.padding(horizontal = horizontalPadding, vertical = verticalPadding),
                    verticalArrangement = verticalArrangement,
                    content = content,
                )
            }
            Box(Modifier.align(Alignment.TopCenter).fillMaxWidth(.93f).height(1.dp)
                .background(palette.rim))
        }
    }
}

/** A solid top bar; V5 forbids glass in global top chrome. */
@Composable
fun SyTopChrome(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val p = LocalSyPalette.current
    Surface(modifier = modifier, color = p.topBar,
        border = BorderStroke(1.dp, p.border.copy(alpha = .5f))) {
        Box(Modifier.fillMaxWidth().height(SyDimensions.toolbarExpanded), content = content)
    }
}

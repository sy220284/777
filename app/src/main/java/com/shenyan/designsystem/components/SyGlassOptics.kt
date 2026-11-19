package com.shenyan.designsystem.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.shenyan.designsystem.foundation.SyColors

/**
 * One optical policy shared by all native Shenyan material surfaces.
 * Matte-black and readable solid surfaces never run a glass rendering pass.
 */
internal data class SyGlassOptics(
    val glass: Boolean,
    val blur: Dp,
    val elevation: Dp,
    val reflection: Float,
)

internal fun syGlassOptics(
    colors: SyColors,
    tier: SyMaterialTier,
    fill: Color,
    reduceTransparency: Boolean,
    nestedDepth: Int,
    hasBackdrop: Boolean,
): SyGlassOptics {
    val elevation = when (tier) {
        SyMaterialTier.L1_SECTION, SyMaterialTier.L5_SOLID -> 0.dp
        SyMaterialTier.L2_BAR -> 2.dp
        SyMaterialTier.L3_CARD -> 1.dp
        SyMaterialTier.L4_OVERLAY -> 16.dp
    }
    // Only the white theme's translucent chrome gets optical depth.
    // A fully opaque sampled fill or the system's reduced transparency is a solid.
    val glass = colors.materialL3.alpha < 1f &&
        fill.alpha < 0.999f &&
        !reduceTransparency &&
        nestedDepth < 3 &&
        hasBackdrop &&
        tier in setOf(SyMaterialTier.L2_BAR, SyMaterialTier.L3_CARD, SyMaterialTier.L4_OVERLAY)
    if (!glass) return SyGlassOptics(false, 0.dp, elevation, 0f)

    // Frequent scrolling cards intentionally avoid GPU background blur.
    // Toolbar/sheet blur uses a single spatially aligned scene, not sibling text.
    val blur = if (nestedDepth != 0) 0.dp else when (tier) {
        SyMaterialTier.L2_BAR -> 22.dp
        SyMaterialTier.L4_OVERLAY -> 28.dp
        else -> 0.dp
    }
    val reflection = when (tier) {
        SyMaterialTier.L2_BAR -> .78f
        SyMaterialTier.L3_CARD -> .38f
        SyMaterialTier.L4_OVERLAY -> .90f
        else -> 0f
    }
    return SyGlassOptics(true, blur, elevation, reflection)
}

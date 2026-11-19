package com.shenyan.designsystem.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.lerp
import java.util.ArrayDeque
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.shenyan.designsystem.foundation.SyColors

/**
 * One optical policy shared by all native Shenyan material surfaces.
 * Matte-black and readable solid surfaces never run a glass rendering pass.
 */
internal data class SyGlassOptics(
    val glass: Boolean,
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
    if (!glass) return SyGlassOptics(false, elevation, 0f)

    val reflection = when (tier) {
        SyMaterialTier.L2_BAR -> .55f
        SyMaterialTier.L3_CARD -> .42f
        SyMaterialTier.L4_OVERLAY -> .65f
        else -> 0f
    }
    return SyGlassOptics(true, 0.dp, reflection)
}

/** Optical chrome uses a thinner veil than the corresponding flat reading surface. */
internal fun syLiquidTint(fill: Color, tint: Color): Color {
    val clearFill = fill.copy(alpha = fill.alpha * .36f)
    return if (tint.isSpecified) {
        lerp(clearFill, tint.copy(alpha = clearFill.alpha), tint.alpha.coerceAtMost(.16f))
    } else clearFill
}

/**
 * Forty-eight millisecond pointer history produces comparable bending at
 * 60Hz and 120Hz, without dividing by sub-millisecond single-event intervals.
 * The result is displacement per reference 16ms frame, not a scroll command.
 */
internal fun syWindowedGlassDrag(
    history: ArrayDeque<Pair<Long, Offset>>,
    timeMillis: Long,
    position: Offset,
): Offset {
    history.addLast(timeMillis to position)
    while (history.size > 2 && timeMillis - history.first().first > 48L) {
        history.removeFirst()
    }
    val oldest = history.first()
    val elapsed = (timeMillis - oldest.first).coerceAtLeast(8L).toFloat()
    return (position - oldest.second) * (16f / elapsed)
}

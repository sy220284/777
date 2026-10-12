package com.shenyan.designsystem.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import com.shenyan.designsystem.foundation.*

/**
 * Five-tier material grammar. Alpha, border, rim highlight and elevation are native and real.
 * Compose cannot blur *already-drawn sibling content* with Modifier.blur: it blurs its own layer.
 * For true backdrop blur, caller may supply a spatially aligned `backdrop` composable. It is
 * drawn behind the fill, and only that backdrop is blurred. Never blur foreground text.
 * On old devices or when no backdrop is supplied use a clean semantic surface fallback.
 */
enum class SyMaterialTier { L1_SECTION, L2_BAR, L3_CARD, L4_OVERLAY, L5_SOLID }

@Composable
fun SyMaterialFrame(
    modifier: Modifier = Modifier,
    tier: SyMaterialTier = SyMaterialTier.L3_CARD,
    onClick: (() -> Unit)? = null,
    backdrop: (@Composable () -> Unit)? = null,
    horizontalPadding: Dp = 16.dp,
    verticalPadding: Dp = 14.dp,
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(SyMetric.gapMd),
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = LocalSyColors.current
    val nested = LocalSyGlassDepth.current
    val resolved = if ((nested >= 3 || LocalSyReduceTransparency.current) && tier != SyMaterialTier.L5_SOLID) SyMaterialTier.L5_SOLID else tier
    val fill = when (resolved) {
        SyMaterialTier.L1_SECTION -> c.materialL1
        SyMaterialTier.L2_BAR -> c.materialL2
        SyMaterialTier.L3_CARD -> c.materialL3
        SyMaterialTier.L4_OVERLAY -> c.materialL4
        SyMaterialTier.L5_SOLID -> c.materialL5
    }
    // Opaque Matte surfaces must never sample or blur the supplied backdrop.
    val blurRadius = if (resolved == SyMaterialTier.L5_SOLID || fill.alpha == 1f) 0.dp else when (resolved) {
        SyMaterialTier.L1_SECTION -> 18.dp
        SyMaterialTier.L2_BAR -> 28.dp
        SyMaterialTier.L3_CARD -> 20.dp
        SyMaterialTier.L4_OVERLAY -> 32.dp
        SyMaterialTier.L5_SOLID -> 0.dp
    }
    val shape = RoundedCornerShape(if (resolved == SyMaterialTier.L4_OVERLAY) SyMetric.radiusSheet else SyMetric.radiusCard)
    val elevation = when (resolved) {
        SyMaterialTier.L1_SECTION, SyMaterialTier.L5_SOLID -> 1.dp
        SyMaterialTier.L2_BAR -> 3.dp
        SyMaterialTier.L3_CARD -> 7.dp
        SyMaterialTier.L4_OVERLAY -> 16.dp
    }
    @Composable fun Interior() {
        Box(Modifier.clip(shape)) {
            if (backdrop != null && blurRadius > 0.dp) {
                Box(Modifier.matchParentSize().blur(blurRadius, BlurredEdgeTreatment.Unbounded)) { backdrop() }
            }
            Box(Modifier.matchParentSize().background(fill))
            CompositionLocalProvider(LocalSyGlassDepth provides (if (fill.alpha == 1f) nested else nested + 1)) {
                Column(Modifier.padding(horizontal = horizontalPadding, vertical = verticalPadding),
                    verticalArrangement = verticalArrangement, content = content)
            }
            Box(Modifier.align(Alignment.TopCenter).fillMaxWidth(.93f).height(1.dp).background(c.materialSheen))
        }
    }
    val outer = modifier.shadow(elevation, shape).clip(shape)
    if (onClick != null) {
        Surface(onClick = onClick, modifier = outer, color = Color.Transparent, shape = shape,
            border = BorderStroke(1.dp, c.border)) { Interior() }
    } else {
        Surface(modifier = outer, color = Color.Transparent, shape = shape,
            border = BorderStroke(1.dp, c.border)) { Interior() }
    }
}

/** Machine-generated output/paths/IDs use monospaced Latin glyphs; CJK falls back through OS. */
@Composable
fun SyMachineText(text: String, modifier: Modifier = Modifier) {
    androidx.compose.material3.Text(
        text = text,
        modifier = modifier,
        color = LocalSyColors.current.textSecondary,
        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
        style = androidx.compose.material3.MaterialTheme.typography.bodySmall
    )
}

package com.labteto.dshmobile.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp
import com.shenyan.designsystem.foundation.SyMetric

/**
 * 统一 semantic radius scale:
 * Uploaded components: 9 chip / 13 control / 18 card / 24 floating surface.
 * User bubbles retain a 6dp trailing corner; tags retain the full capsule.
 *
 * Screens consume semantic names so page code does not invent local radii.
 */
/** V6 semantic shape aliases: standard radii delegate to SyMetric; chat tail, sheet and capsule shapes carry distinct screen-specific semantics. Not a second palette or material renderer. */
object DsShapes {
    val buttonCapsule = RoundedCornerShape(SyMetric.radiusControl)
    val buttonSmall = RoundedCornerShape(12.dp)
    val bubble = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 6.dp)
    val characterBubble = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 6.dp, bottomEnd = 18.dp)
    val composer = RoundedCornerShape(20.dp)
    val approvalCard = RoundedCornerShape(SyMetric.radiusCard)
    val dialog = RoundedCornerShape(SyMetric.radiusSheet)
    val floatingSheet = RoundedCornerShape(SyMetric.radiusSheet)
    val sheet = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    val menu = RoundedCornerShape(SyMetric.radiusSheet)
    val toast = RoundedCornerShape(12.dp)
    val block = RoundedCornerShape(SyMetric.radiusCard)
    val settingsGroup = RoundedCornerShape(SyMetric.radiusCard)
    val pill = RoundedCornerShape(12.dp)
    val pillFull = RoundedCornerShape(999.dp)
    val chip = RoundedCornerShape(SyMetric.radiusSm)
    val row = RoundedCornerShape(SyMetric.radiusControl)
    val cube = RoundedCornerShape(SyMetric.radiusSheet)
}

val DsMaterialShapes = Shapes(
    extraSmall = DsShapes.chip,
    small = DsShapes.row,
    medium = DsShapes.block,
    large = DsShapes.dialog,
    extraLarge = DsShapes.composer,
)

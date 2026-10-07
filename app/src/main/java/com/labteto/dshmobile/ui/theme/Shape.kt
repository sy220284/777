package com.labteto.dshmobile.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Radius tokens intentionally collapse to six visual levels: 6 / 8 / 12 / 16 / 20 / full.
 * Semantic names remain so components describe intent without inventing new radii.
 */
object DsShapes {
    val buttonCapsule = RoundedCornerShape(14.dp)
    val buttonSmall = RoundedCornerShape(10.dp)
    val bubble = RoundedCornerShape(16.dp)
    val composer = RoundedCornerShape(20.dp)
    val approvalCard = RoundedCornerShape(16.dp)
    val dialog = RoundedCornerShape(16.dp)
    val sheet = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    val menu = RoundedCornerShape(12.dp)
    val toast = RoundedCornerShape(12.dp)
    val tooltip = RoundedCornerShape(8.dp)
    val block = RoundedCornerShape(12.dp)
    val pill = RoundedCornerShape(10.dp)
    val pillFull = RoundedCornerShape(999.dp)
    val chip = RoundedCornerShape(6.dp)
    val row = RoundedCornerShape(10.dp)
    val cube = RoundedCornerShape(12.dp)
}

val DsMaterialShapes = Shapes(
    extraSmall = DsShapes.chip,
    small = DsShapes.menu,
    medium = DsShapes.block,
    large = DsShapes.composer,
    extraLarge = DsShapes.dialog,
)

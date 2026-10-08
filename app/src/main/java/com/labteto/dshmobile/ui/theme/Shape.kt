package com.labteto.dshmobile.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * 统一 semantic radius scale:
 * 6 chip / 8 tooltip / 10 row / 24 anchored menu and block /
 * 16 approval / 18 bubble / 28 dialog and composer / 32 sheet / full capsule.
 *
 * Screens consume semantic names so page code does not invent local radii.
 */
object DsShapes {
    val buttonCapsule = RoundedCornerShape(999.dp)
    val buttonSmall = RoundedCornerShape(12.dp)
    val bubble = RoundedCornerShape(18.dp)
    val composer = RoundedCornerShape(28.dp)
    val approvalCard = RoundedCornerShape(16.dp)
    val dialog = RoundedCornerShape(28.dp)
    val floatingSheet = RoundedCornerShape(32.dp)
    val sheet = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)
    val menu = RoundedCornerShape(24.dp)
    val toast = RoundedCornerShape(12.dp)
    val tooltip = RoundedCornerShape(8.dp)
    val block = RoundedCornerShape(24.dp)
    val pill = RoundedCornerShape(12.dp)
    val pillFull = RoundedCornerShape(999.dp)
    val chip = RoundedCornerShape(6.dp)
    val row = RoundedCornerShape(10.dp)
    val cube = RoundedCornerShape(24.dp)
}

val DsMaterialShapes = Shapes(
    extraSmall = DsShapes.chip,
    small = DsShapes.row,
    medium = DsShapes.block,
    large = DsShapes.dialog,
    extraLarge = DsShapes.composer,
)

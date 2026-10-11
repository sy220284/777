package com.labteto.dshmobile.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * 统一 semantic radius scale:
 * Fluid glass: 8 chip / 13 control / 20 card / 24 floating surface.
 * User bubbles retain a 7dp trailing corner; tags retain the full capsule.
 *
 * Screens consume semantic names so page code does not invent local radii.
 */
object DsShapes {
    val buttonCapsule = RoundedCornerShape(13.dp)
    val buttonSmall = RoundedCornerShape(12.dp)
    val bubble = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 7.dp)
    val characterBubble = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 7.dp, bottomEnd = 20.dp)
    val composer = RoundedCornerShape(24.dp)
    val approvalCard = RoundedCornerShape(20.dp)
    val dialog = RoundedCornerShape(24.dp)
    val floatingSheet = RoundedCornerShape(24.dp)
    val sheet = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    val menu = RoundedCornerShape(24.dp)
    val toast = RoundedCornerShape(12.dp)
    val block = RoundedCornerShape(20.dp)
    val settingsGroup = RoundedCornerShape(18.dp)
    val pill = RoundedCornerShape(12.dp)
    val pillFull = RoundedCornerShape(999.dp)
    val chip = RoundedCornerShape(8.dp)
    val row = RoundedCornerShape(13.dp)
    val cube = RoundedCornerShape(24.dp)
}

val DsMaterialShapes = Shapes(
    extraSmall = DsShapes.chip,
    small = DsShapes.row,
    medium = DsShapes.block,
    large = DsShapes.dialog,
    extraLarge = DsShapes.composer,
)

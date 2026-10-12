package com.labteto.dshmobile.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * 统一 semantic radius scale:
 * Uploaded components: 9 chip / 13 control / 18 card / 24 floating surface.
 * User bubbles retain a 6dp trailing corner; tags retain the full capsule.
 *
 * Screens consume semantic names so page code does not invent local radii.
 */
object DsShapes {
    val buttonCapsule = RoundedCornerShape(13.dp)
    val buttonSmall = RoundedCornerShape(12.dp)
    val bubble = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 6.dp)
    val characterBubble = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 6.dp, bottomEnd = 18.dp)
    val composer = RoundedCornerShape(20.dp)
    val approvalCard = RoundedCornerShape(18.dp)
    val dialog = RoundedCornerShape(24.dp)
    val floatingSheet = RoundedCornerShape(24.dp)
    val sheet = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    val menu = RoundedCornerShape(24.dp)
    val toast = RoundedCornerShape(12.dp)
    val block = RoundedCornerShape(18.dp)
    val settingsGroup = RoundedCornerShape(18.dp)
    val pill = RoundedCornerShape(12.dp)
    val pillFull = RoundedCornerShape(999.dp)
    val chip = RoundedCornerShape(9.dp)
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

package com.labteto.dshmobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.ui.theme.Ds
import com.labteto.dshmobile.ui.theme.DsTheme

/**
 * Function families for tinted icon containers. Each family maps one navigation concept to one
 * hue so a screen can be scanned by color before it is read: 聊天/人设 purple, 工作 cyan,
 * 通知/任务 amber, 权限 green, 模型 celadon accent, everything else neutral ink.
 */
enum class DsIconFamily {
    Accent,
    Purple,
    Cyan,
    Amber,
    Green,
    Neutral,
}

/** Container + content colors for a family, resolved against the active theme. */
private data class DsIconFamilyColors(val container: Color, val content: Color)

@Composable
private fun colorsFor(family: DsIconFamily): DsIconFamilyColors {
    val c = DsTheme.colors
    return when (family) {
        DsIconFamily.Accent -> DsIconFamilyColors(c.accentTertiary, c.accent)
        DsIconFamily.Purple -> DsIconFamilyColors(Ds.FamilyPurple.copy(alpha = 0.12f), Ds.FamilyPurple)
        DsIconFamily.Cyan -> DsIconFamilyColors(Ds.FamilyCyan.copy(alpha = 0.12f), Ds.FamilyCyan)
        DsIconFamily.Amber -> DsIconFamilyColors(c.warnTertiary, c.warn)
        DsIconFamily.Green -> DsIconFamilyColors(c.successTertiary, c.success)
        DsIconFamily.Neutral -> DsIconFamilyColors(c.hover, c.labelSecondary)
    }
}

/**
 * 30dp rounded tint container behind a 15dp outlined icon. The container — not the glyph — carries
 * the family hue, which keeps the icon itself quiet and the page rhythm even.
 */
@Composable
fun DsIconBox(
    icon: ImageVector,
    family: DsIconFamily = DsIconFamily.Neutral,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    val familyColors = colorsFor(family)
    Box(
        modifier = modifier
            .size(30.dp)
            .background(familyColors.container, RoundedCornerShape(9.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = familyColors.content,
            modifier = Modifier.size(15.dp),
        )
    }
}

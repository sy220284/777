package com.labteto.dshmobile.ui.theme

import androidx.compose.ui.graphics.compositeOver
import com.shenyan.designsystem.foundation.SyColors

/**
 * V6 page color projection. Pages not yet refactored to Sy* still read DsColors;
 * their surfaces must come from the SAME light/dark/matte palette as new controls.
 * This is a visual adapter only: explicit user accent selection remains untouched.
 * The backgrounds are composed over the canonical canvas so a nested page cannot
 * accidentally become a bright white plate or an unbacked transparent drawer.
 */
internal fun DsColors.harmonizeV6(p: SyColors, preserveAccent: Boolean = false): DsColors {
    val bar = p.materialL2.compositeOver(p.canvas)
    val card = p.materialL3.compositeOver(p.canvas)
    val overlay = p.materialL4.compositeOver(p.canvas)
    return copy(
        bgBase = p.canvas,
        brandPrimary = if (preserveAccent) brandPrimary else p.primary,
        onBrandPrimary = if (preserveAccent) onBrandPrimary else p.onPrimary,
        accent = if (preserveAccent) accent else p.primary,
        onAccent = if (preserveAccent) onAccent else p.onPrimary,
        accentHover = if (preserveAccent) accentHover else p.primaryPressed,
        accentTertiary = if (preserveAccent) accentTertiary else p.selected,
        buttonPrimaryHover = if (preserveAccent) buttonPrimaryHover else p.primaryPressed,
        bgLayer1 = card,
        bgLayer2 = bar,
        bgLayer3 = overlay,
        bgModulePlatform = bar,
        borderL1 = p.border.copy(alpha = p.border.alpha * 0.55f),
        borderL2 = p.border.copy(alpha = p.border.alpha * 0.75f),
        borderL3 = p.border,
        labelPrimary = p.text,
        labelSecondary = p.textSecondary,
        labelTertiary = p.textTertiary,
        sidebar = bar,
        sidebarNavActive = card,
        sidebarNavAccent = p.selected,
        sidebarNavHover = card,
        composerCard = bar,
        userBubble = card,
        userBubbleHighlight = overlay,
        tipSurface = card,
    )
}

/** Project the saved accent into the canonical controls as well as legacy page consumers.
 * Material tiers and semantic status colours still belong to the V6 palette.
 */
internal fun SyColors.withV6Accent(source: DsColors): SyColors = copy(
    primary = source.accent,
    primaryPressed = source.accentHover.compositeOver(source.bgBase),
    onPrimary = source.onAccent,
    selected = source.accentTertiary.compositeOver(source.bgBase),
)

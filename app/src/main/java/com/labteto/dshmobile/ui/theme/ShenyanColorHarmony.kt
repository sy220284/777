package com.labteto.dshmobile.ui.theme

import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.lerp
import com.shenyan.designsystem.foundation.SyColors

/**
 * Screen-level projection from one canonical Shenyan colour contract. Production UI colors are served by LocalSyColors;
 * keep historical preference values in sync with the canonical V6 palette.
 * This is a visual adapter only: explicit user accent selection remains untouched.
 * The backgrounds are composed over the canonical canvas so a nested page cannot
 * accidentally become a bright white plate or an unbacked transparent drawer.
 */
internal fun SyHostColors.harmonizeV6(p: SyColors, preserveAccent: Boolean = false): SyHostColors {
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
        sidebarNavActive = if (p.text.luminance() < .5f)
            if (preserveAccent) accentTertiary.compositeOver(p.canvas) else p.selected
            else card,
        sidebarNavAccent = if (preserveAccent) accentTertiary.compositeOver(p.canvas) else p.selected,
        sidebarNavHover = if (p.text.luminance() < .5f) p.text.copy(alpha = .04f).compositeOver(bar) else card,
        composerCard = bar,
        userBubble = if (p.text.luminance() < .5f)
            if (preserveAccent) accentTertiary.compositeOver(p.canvas) else p.selected
            else card,
        userBubbleHighlight = overlay,
        tipSurface = card,
    )
}

/** Project the saved accent into the canonical controls as well as legacy page consumers.
 * Material tiers and semantic status colours still belong to the V6 palette.
 */
internal fun SyColors.withV6Accent(source: SyHostColors): SyColors {
    val accent = source.accent.copy(alpha = 1f)
    // Neutral reading canvases remain classic white/matte. Selection, subtle optical edge
    // and the sheen move together so accent families read as one coordinated system.
    val edge = lerp(border.copy(alpha = 1f), accent,
        if (text.luminance() < .5f) .12f else .20f).copy(alpha = border.alpha)
    val sheen = lerp(materialSheen.copy(alpha = 1f), accent, .06f)
        .copy(alpha = materialSheen.alpha)
    return copy(
        primary = accent,
        primaryPressed = source.accentHover.compositeOver(source.bgBase),
        onPrimary = source.onAccent,
        selected = source.accentTertiary.compositeOver(source.bgBase),
        border = edge,
        materialSheen = sheen,
    )
}

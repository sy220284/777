package com.shenyan.designsystem.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import com.shenyan.designsystem.foundation.LocalSyColors
import com.shenyan.designsystem.foundation.LocalSySceneBackdrop
import com.shenyan.designsystem.foundation.SyColors
import com.shenyan.designsystem.foundation.SySceneBackdrop

/** The same fill policy is used by drawn glass and platform controls' container colours. */
@Composable
fun syMaterialColor(tier: SyMaterialTier): Color =
    syResolveMaterialFill(LocalSyColors.current, tier, LocalSySceneBackdrop.current)

internal fun syContrastRatio(ink: Color, background: Color): Float {
    val a = ink.compositeOver(background).luminance()
    val b = background.luminance()
    return (maxOf(a, b) + .05f) / (minOf(a, b) + .05f)
}

/**
 * All five tiers draw from one root scene: the theme canvas, its static glow or the actual
 * wallpaper's sampled region. Tiers change opacity/contrast, never switch to a white palette.
 * Colour sampling is cached by AppBackgroundHost; this resolver never reads bitmap pixels.
 */
internal fun syResolveMaterialFill(
    colors: SyColors, tier: SyMaterialTier, scene: SySceneBackdrop?, regionY: Float = .5f,
): Color {
    val token = when (tier) {
        SyMaterialTier.L1_SECTION -> colors.materialL1
        SyMaterialTier.L2_BAR -> colors.materialL2
        SyMaterialTier.L3_CARD -> colors.materialL3
        SyMaterialTier.L4_OVERLAY -> colors.materialL4
        SyMaterialTier.L5_SOLID -> colors.materialL5
    }
    // Matte is intentionally solid; detached previews fall back to the same canvas-based tokens.
    if (colors.materialL3.alpha == 1f || scene == null) return token

    val index = when {
        regionY < .30f -> 0
        regionY < .74f -> 1
        else -> 2
    }
    val sample = when {
        scene.regionColors.isNotEmpty() -> {
            val colour = scene.regionColors.getOrElse(index) { scene.regionColors.last() }
            scene.veil?.compositeOver(colour) ?: colour
        }
        scene.glowColors.size >= 3 -> {
            // FluidGlassBackdrop's matching top/middle/bottom light fields.
            lerp(scene.canvas, scene.glowColors[index].copy(alpha = 1f),
                if (scene.dark) .055f else if (index == 2) .045f else .14f)
        }
        else -> scene.canvas
    }
    val backgrounds = if (scene.regionColors.isNotEmpty()) {
        listOf(sample) + scene.regionExtremes.getOrNull(index).orEmpty()
            .map { scene.veil?.compositeOver(it) ?: it }
    } else listOf(sample)
    fun contrast(background: Color): Float = listOf(
        colors.text, colors.textSecondary, colors.textTertiary,
        colors.successInk, colors.warningInk, colors.dangerInk,
    ).minOf { syContrastRatio(it, background) }

    // Keep the sampled hue. Simply reducing saturation toward a pale neutral makes every
    // colourful wallpaper look white and can erase the difference between screen regions.
    val anchor = colors.materialL5.copy(alpha = 1f)
    var tinted = lerp(anchor, sample.copy(alpha = 1f),
        if (scene.regionColors.isNotEmpty()) .30f else .18f)
    val luminanceStep = if (colors.text.luminance() < .5f) .01f else -.01f
    repeat(100) {
        if (contrast(tinted) >= 4.5f) return@repeat
        // Equal-channel correction protects contrast without discarding the wallpaper hue.
        tinted = Color(
            red = (tinted.red + luminanceStep).coerceIn(0f, 1f),
            green = (tinted.green + luminanceStep).coerceIn(0f, 1f),
            blue = (tinted.blue + luminanceStep).coerceIn(0f, 1f),
        )
    }
    if (tier == SyMaterialTier.L5_SOLID) return tinted

    var alpha = (token.alpha +
        (.5f - scene.surfaceTransparency.coerceIn(0f, 1f)) * .46f).coerceIn(.08f, 1f)
    fun minimumContrast(opacity: Float) =
        backgrounds.minOf { contrast(tinted.copy(alpha = opacity).compositeOver(it)) }
    if (scene.adaptiveContrast && minimumContrast(alpha) < 4.5f) {
        var low = alpha
        var high = 1f
        repeat(10) {
            val mid = (low + high) / 2f
            if (minimumContrast(mid) < 4.5f) low = mid else high = mid
        }
        alpha = high
    }
    return tinted.copy(alpha = alpha)
}

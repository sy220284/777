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

/** Constant-time cached region sampling; no bitmap decode/read occurs in composition or drawing.
 * Tint prevents a neutral white slab over coloured wallpaper. WCAG 4.5:1 secondary-text contrast
 * bounds its strength and, when adaptive contrast is enabled, the required backing opacity.
 */
internal fun syResolveMaterialFill(
    colors: SyColors, tier: SyMaterialTier, scene: SySceneBackdrop?, regionY: Float = .5f,
): Color {
    fun contrast(background: Color): Float = listOf(colors.text, colors.textSecondary,
        colors.textTertiary, colors.successInk, colors.warningInk, colors.dangerInk)
        .minOf { syContrastRatio(it, background) }
    val token = when (tier) {
        SyMaterialTier.L1_SECTION -> colors.materialL1
        SyMaterialTier.L2_BAR -> colors.materialL2
        SyMaterialTier.L3_CARD -> colors.materialL3
        SyMaterialTier.L4_OVERLAY -> colors.materialL4
        SyMaterialTier.L5_SOLID -> colors.materialL5
    }
    // Opaque accessibility/Matte palettes keep their exact semantic tiers and never sample.
    if (colors.materialL3.alpha == 1f || scene == null || scene.regionColors.isEmpty()) return token
    val index = when {
        scene.regionColors.size < 3 -> 0
        regionY < .30f -> 0
        regionY < .74f -> 1
        else -> 2
    }
    val sample = scene.regionColors[index].let { scene.veil?.compositeOver(it) ?: it }
    val backgrounds = listOf(sample) + scene.regionExtremes.getOrNull(index).orEmpty()
        .map { scene.veil?.compositeOver(it) ?: it }
    val neutral = colors.materialL5.copy(alpha = 1f)
    var strength = .30f
    var tinted = lerp(neutral, sample, strength)
    // Keep tint only while the resulting solid can still protect the active theme's ink.
    while (contrast(tinted) < 4.5f && strength > 0f) {
        strength = (strength - .03f).coerceAtLeast(0f)
        tinted = lerp(neutral, sample, strength)
    }
    if (tier == SyMaterialTier.L5_SOLID) return tinted
    var alpha = (token.alpha + (.5f - scene.surfaceTransparency.coerceIn(0f, 1f)) * .46f).coerceIn(.08f, 1f)
    fun minimumContrast(opacity: Float) = backgrounds.minOf { contrast(tinted.copy(alpha = opacity).compositeOver(it)) }
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

package com.labteto.dshmobile.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

class FluidGlassReadabilityTest {
    @Test fun essentialLabelsAndPrimaryActionsMeetNormalTextContrastInBothSchemes() {
        for (colors in listOf(DsThemeTokens.light, DsThemeTokens.dark)) {
            val dark = colors.bgBase.luminance() < 0.5f
            val card = fluidSurfaceColor(colors.bgLayer1, WallpaperSurfaceLevel.CARD, dark)
                .compositeOver(colors.bgBase)
            assertContrast(colors.labelPrimary, card)
            assertContrast(colors.labelSecondary, card)
            assertContrast(colors.labelCaption, card)
            assertContrast(colors.onBrandPrimary, colors.brandPrimary)
            assertContrast(colors.onError, colors.error)
        }
    }

    @Test fun MobileOverlayLabelsRemainReadableOverTheDarkestAndLightestUnderlyingContent() {
        for (colors in listOf(DsThemeTokens.light, DsThemeTokens.dark)) {
            val dark = colors.bgBase.luminance() < 0.5f
            for (level in listOf(WallpaperSurfaceLevel.SHEET, WallpaperSurfaceLevel.DRAWER,
                WallpaperSurfaceLevel.DIALOG, WallpaperSurfaceLevel.MENU)) {
                for (underlay in listOf(Color.Black, Color.White)) {
                    val base = if (level == WallpaperSurfaceLevel.DRAWER) colors.sidebar else colors.bgLayer2
                    val plate = fluidSurfaceColor(base, level, dark).compositeOver(underlay)
                    assertContrast(colors.labelPrimary, plate)
                    assertContrast(colors.labelSecondary, plate)
                }
            }
        }
    }

    private fun assertContrast(foreground: Color, background: Color) {
        val a = foreground.compositeOver(background).luminance()
        val b = background.luminance()
        val contrast = (maxOf(a, b) + 0.05f) / (minOf(a, b) + 0.05f)
        assertTrue("Text contrast $contrast is below 4.5:1", contrast >= 4.5f)
    }
}

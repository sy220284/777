package com.labteto.dshmobile.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import com.shenyan.designsystem.foundation.ShenyanPalettes
import com.shenyan.designsystem.foundation.syInkContrastRatio
import com.shenyan.designsystem.foundation.syReadableInk
import org.junit.Assert.assertTrue
import org.junit.Test

class ShenyanReadableInkTest {
    @Test fun allThemeReadingInksMeetNormalTextContrast() {
        listOf(ShenyanPalettes.light, ShenyanPalettes.dark, ShenyanPalettes.matte).forEach { palette ->
            val surfaces = listOf(palette.canvas, palette.materialL2, palette.materialL3, palette.selected)
            val inks = listOf(palette.text, palette.textSecondary, palette.textTertiary,
                palette.linkInk, palette.personaInk, palette.successInk, palette.warningInk, palette.dangerInk)
            surfaces.forEachIndexed { surfaceIndex, surface ->
                inks.forEachIndexed { inkIndex, ink ->
                    assertTrue("reading ink $inkIndex on surface $surfaceIndex",
                        syInkContrastRatio(ink, surface) >= 4.5f)
                }
            }
            assertTrue("primary action", syInkContrastRatio(palette.onPrimary, palette.primary) >= 4.5f)
            assertTrue("pressed primary action", syInkContrastRatio(palette.onPrimary, palette.primaryPressed) >= 4.5f)
            assertTrue("danger action", syInkContrastRatio(syReadableInk(Color.White, palette.danger), palette.danger) >= 4.5f)
        }
    }

    @Test fun everySelectedAccentKeepsButtonTextReadableAcrossThemesAndStates() {
        val themes = listOf(
            DsThemeTokens.light to false,
            DsThemeTokens.dark to true,
            DsThemeTokens.matteBlack to true,
        )
        for (palette in AccentPalettes.ALL) {
            for ((base, dark) in themes) {
                val colors = base.withAccent(palette, dark)
                val fills = listOf(
                    colors.accent,
                    colors.brandPrimary,
                    colors.accentHover.compositeOver(colors.bgLayer2),
                    colors.accentHover.compositeOver(colors.bgLayer3),
                )
                fills.forEachIndexed { index, fill ->
                    assertTrue("${palette.key}/${if (dark) "dark" else "light"}/$index",
                        syInkContrastRatio(colors.onAccent, fill) >= 4.5f)
                    assertTrue("legacy/canonical foreground must match",
                        com.shenyan.designsystem.foundation.ShenyanPalettes.light
                            .withV6Accent(colors).onPrimary == colors.onAccent)
                }
            }
        }
    }

    @Test fun selectedDecorativeAccentsAreReadableWhenUsedAsText() {
        val surfaces = listOf(
            Color(0xFFF1E8DC), Color(0xFF35312D), Color(0xFF29292B),
            Color(0xFF777777), Color(0xFF999999), Color(0xFFB0B0B0),
        )
        AccentPalettes.ALL.forEach { accent ->
            surfaces.forEach { surface ->
                val text = syReadableInk(accent.lightAccent, surface)
                assertTrue(accent.cnName, syInkContrastRatio(text, surface) >= 4.5f)
            }
        }
    }
}

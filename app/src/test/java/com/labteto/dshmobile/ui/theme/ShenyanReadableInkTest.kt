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
        listOf(ShenyanPalettes.light, ShenyanPalettes.matte).forEach { palette ->
            assertTrue("link ink", syInkContrastRatio(palette.linkInk, palette.materialL5) >= 4.5f)
            assertTrue("persona ink", syInkContrastRatio(palette.personaInk, palette.materialL5) >= 4.5f)
        }
    }

    @Test fun everySelectedAccentKeepsButtonTextReadableAcrossThemesAndStates() {
        val themes = listOf(
            SyHostThemeTokens.light to false,
            SyHostThemeTokens.matteBlack to true,
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

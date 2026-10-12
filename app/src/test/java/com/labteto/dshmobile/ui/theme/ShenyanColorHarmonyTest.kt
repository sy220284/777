package com.labteto.dshmobile.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import com.shenyan.designsystem.foundation.ShenyanPalettes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShenyanColorHarmonyTest {
    @Test
    fun pageChromeSidebarComposerShareOneV6PaletteAcrossThemes() {
        listOf(
            DsThemeTokens.light to ShenyanPalettes.light,
            DsThemeTokens.dark to ShenyanPalettes.dark,
            DsThemeTokens.matteBlack to ShenyanPalettes.matte,
        ).forEach { (old, v6) ->
            val result = old.harmonizeV6(v6)
            val bar = v6.materialL2.compositeOver(v6.canvas)
            assertEquals(v6.canvas, result.bgBase)
            assertEquals(bar, result.sidebar)
            assertEquals(bar, result.composerCard)
            assertEquals(bar, result.bgLayer2)
            assertEquals(v6.materialL3.compositeOver(v6.canvas), result.bgLayer1)
            assertEquals(v6.text, result.labelPrimary)
            assertEquals(v6.textSecondary, result.labelSecondary)
            // Both the rail and input remain readable over either scene, not bare alpha.
            assertEquals(1f, result.sidebar.alpha, 0f)
            assertEquals(1f, result.composerCard.alpha, 0f)
            assertEquals(v6.primary, result.accent)
            assertEquals(v6.primary, result.brandPrimary)
            assertEquals(v6.onPrimary, result.onAccent)
            // An explicitly selected accent remains independent of the V6 default.
            assertEquals(old.accent, old.harmonizeV6(v6, preserveAccent = true).accent)
        }
    }

    @Test
    fun wallpaperSafeDrawerBackingMeetsReadableContrastOverImages() {
        for (v6 in listOf(ShenyanPalettes.light, ShenyanPalettes.dark, ShenyanPalettes.matte)) {
            for (underlay in listOf(Color.Black, Color.White)) {
                val drawer = v6.topBar.copy(alpha = .92f).compositeOver(underlay)
                val a = v6.textSecondary.luminance()
                val b = drawer.luminance()
                val contrast = (maxOf(a, b) + .05f) / (minOf(a, b) + .05f)
                assertTrue("V6 navigation readability: $contrast", contrast >= 4.5f)
            }
        }
    }
}

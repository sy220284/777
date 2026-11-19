package com.labteto.dshmobile.ui.theme

import androidx.compose.ui.graphics.Color
import com.shenyan.designsystem.foundation.ShenyanPalettes
import com.shenyan.designsystem.foundation.syInkContrastRatio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShenyanElegantLightThemeTest {
    @Test fun whiteCanvasBlackTypographyAndLayeredSurfacesStayCanonical() {
        val light = ShenyanPalettes.light
        assertEquals(Color(0xFFFCFCFB), light.canvas)
        assertEquals(Color.White, light.topBar)
        assertEquals(Color.White, light.materialL5)
        assertEquals(Color.White, light.surfaceSolid)
        assertEquals(Color.Black, light.text)
        assertEquals(Color(0xFF353535), light.textSecondary)
        for (background in listOf(light.canvas, light.materialL5)) {
            for (ink in listOf(light.text, light.textSecondary, light.textTertiary)) {
                assertTrue("浅色阅读文字应满足普通正文对比度",
                    syInkContrastRatio(ink, background) >= 4.5f)
            }
        }
    }

    @Test fun changingAccentDoesNotTintWhiteReadingCanvasOrBlackInk() {
        for (accent in AccentPalettes.ALL) {
            for (preference in listOf(ThemePreference.LIGHT, ThemePreference.SYSTEM)) {
                val projected = resolveV6ThemePalette(preference, accent.key, systemDark = false)
                assertEquals(false, projected.dark)
                assertEquals(Color(0xFFFCFCFB), projected.page.bgBase)
                assertEquals(Color(0xFFFCFCFB), projected.controls.canvas)
                assertEquals(Color.White, projected.controls.materialL5)
                assertEquals(Color.Black, projected.page.labelPrimary)
                assertEquals(Color.Black, projected.controls.text)
                val expectedActionColor = if (accent.key == AccentPalettes.DEFAULT.key)
                    ShenyanPalettes.light.primary else accent.lightAccent
                assertEquals(expectedActionColor, projected.controls.primary)
                assertTrue(syInkContrastRatio(projected.controls.onPrimary, projected.controls.primary) >= 4.5f)
            }
        }
    }

    @Test fun darkAndMatteKeepTheirOriginalCanvases() {
        assertEquals(Color(0xFF24211E), resolveV6ThemePalette(ThemePreference.DARK, null, false).controls.canvas)
        assertEquals(Color(0xFF0F0F10), resolveV6ThemePalette(ThemePreference.MATTE_BLACK, null, false).controls.canvas)
    }
}

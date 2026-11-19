package com.labteto.dshmobile.ui.theme

import com.shenyan.designsystem.foundation.ShenyanPalettes
import com.shenyan.designsystem.foundation.syInkContrastRatio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShenyanPaletteFamiliesTest {
    @Test fun allPublishedFamiliesRetainSemanticContrastOnEveryMode() {
        val accents = AccentPalettes.ALL
        assertEquals(9, accents.size)
        assertEquals(accents.size, accents.map { it.key }.toSet().size)
        for (accent in accents) {
            for (mode in listOf(
                ThemePreference.LIGHT, ThemePreference.MATTE_BLACK
            )) {
                val theme = resolveV6ThemePalette(mode, accent.key, systemDark = false)
                val colors = theme.controls
                assertTrue("${accent.key}/${mode}/button",
                    syInkContrastRatio(colors.onPrimary, colors.primary) >= 4.5f)
                assertTrue("${accent.key}/${mode}/body",
                    syInkContrastRatio(colors.text, colors.materialL5) >= 4.5f)
                assertTrue("${accent.key}/${mode}/support",
                    syInkContrastRatio(colors.textSecondary, colors.materialL5) >= 4.5f)
                assertEquals("Page and components must use the same canvas",
                    colors.canvas, theme.page.bgBase)
                assertEquals("主题画布由当前明暗模式统一管理",
                    if (mode == ThemePreference.LIGHT) ShenyanPalettes.light.canvas
                    else ShenyanPalettes.matte.canvas, colors.canvas)
            }
        }
    }

    @Test fun formerBlueAndPurpleOptionsResolveToCoherentSupportedFamilies() {
        assertEquals("forest", AccentPalettes.of("default_blue").key)
        assertEquals("graphite", AccentPalettes.of("dailan").key)
        assertEquals("rose_copper", AccentPalettes.of("zitang").key)
        assertEquals("fluid_apricot", AccentPalettes.of("celadon").key)
        assertEquals(ShenyanPalettes.light.canvas,
            resolveV6ThemePalette(ThemePreference.LIGHT, "forest", false).controls.canvas)
    }
}

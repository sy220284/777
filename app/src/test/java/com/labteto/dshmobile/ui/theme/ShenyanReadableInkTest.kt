package com.labteto.dshmobile.ui.theme

import androidx.compose.ui.graphics.Color
import com.shenyan.designsystem.foundation.ShenyanPalettes
import com.shenyan.designsystem.foundation.syInkContrastRatio
import com.shenyan.designsystem.foundation.syReadableInk
import org.junit.Assert.assertTrue
import org.junit.Test

class ShenyanReadableInkTest {
    @Test fun allThemeReadingInksMeetNormalTextContrast() {
        listOf(ShenyanPalettes.light, ShenyanPalettes.dark, ShenyanPalettes.matte).forEach { palette ->
            assertTrue("link ink", syInkContrastRatio(palette.linkInk, palette.materialL5) >= 4.5f)
            assertTrue("persona ink", syInkContrastRatio(palette.personaInk, palette.materialL5) >= 4.5f)
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

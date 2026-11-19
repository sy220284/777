package com.labteto.dshmobile.ui.theme

import androidx.compose.ui.graphics.Color
import com.shenyan.designsystem.foundation.ShenyanPalettes
import com.shenyan.designsystem.foundation.SyMotion
import com.shenyan.designsystem.foundation.syInkContrastRatio
import com.shenyan.designsystem.foundation.withAccent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShenyanAtlasTokensTest {
    @Test fun allThemesHonorArbitraryAccentsAndKeepActionTextReadable() {
        val accents = listOf(
            Color(0xFFBB3B29),
            Color(0xFF216F51),
            Color(0xFFCF9032),
            Color(0xFF696057),
            Color(0xFFEAD5C5),
        )
        val themes = listOf(ShenyanPalettes.light, ShenyanPalettes.dark, ShenyanPalettes.matte)
        for (base in themes) {
            for (accent in accents) {
                val current = base.withAccent(accent)
                assertEquals(accent, current.primary)
                assertTrue(
                    "Chosen accent must keep action labels readable",
                    syInkContrastRatio(current.onPrimary, current.primary) >= 4.5f,
                )
                assertTrue(
                    "Link ink must remain readable over the local reading material",
                    syInkContrastRatio(current.linkInk, current.materialL5) >= 4.5f,
                )
            }
        }
    }

    @Test fun motionRespectsReduceMotionAndTempo() {
        assertEquals(0, SyMotion.duration(SyMotion.sheetMs, reducedMotion = true))
        assertEquals(SyMotion.sheetMs, SyMotion.duration(SyMotion.sheetMs, reducedMotion = false))
        assertEquals((SyMotion.sheetMs * .7f).toInt(),
            SyMotion.duration(SyMotion.sheetMs, reducedMotion = false, tempo = .7f))
    }
}
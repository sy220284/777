package com.labteto.dshmobile.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

/** Uploaded fluid-glass spec: dark toast plates need a readable light foreground. */
class ShenyanToastContrastTest {
    private fun ratio(a: Color, b: Color): Float {
        val l1 = a.luminance()
        val l2 = b.luminance()
        return (maxOf(l1, l2) + .05f) / (minOf(l1, l2) + .05f)
    }

    @Test fun toastLabelPassesWcagAcrossExistingThemes() {
        listOf(DsThemeTokens.light, DsThemeTokens.dark, DsThemeTokens.matteBlack).forEach {
            assertTrue("Toast labels must keep >= 4.5:1 contrast", ratio(it.toastBg, it.onToast) >= 4.5f)
        }
    }
}

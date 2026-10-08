package com.labteto.dshmobile.ui.screens.settings

import androidx.compose.ui.graphics.Color
import com.labteto.dshmobile.ui.theme.DsThemeTokens
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsInkColorsTest {
    @Test
    fun lightSettingsUsePureBlackForLabelsAndIcons() {
        val original = DsThemeTokens.light
        val actual = settingsInkColors(original)

        assertEquals(Color.Black, actual.labelPrimary)
        assertEquals(Color.Black, actual.labelSecondary)
        assertEquals(Color.Black, actual.labelCaption)
        assertEquals(original.labelTertiary, actual.labelTertiary)
        assertEquals(original.labelDimmed, actual.labelDimmed)
        assertEquals(original.bgBase, actual.bgBase)
        assertEquals(original.accent, actual.accent)
    }

    @Test
    fun darkSettingsKeepTheirReadableForegroundPalette() {
        val original = DsThemeTokens.dark
        assertEquals(original, settingsInkColors(original))
    }

    @Test
    fun matteBlackSettingsKeepTheirReadableForegroundPalette() {
        val original = DsThemeTokens.matteBlack
        assertEquals(original, settingsInkColors(original))
    }
}

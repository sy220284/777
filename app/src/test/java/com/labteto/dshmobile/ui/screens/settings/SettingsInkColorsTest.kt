package com.labteto.dshmobile.ui.screens.settings

import com.labteto.dshmobile.ui.theme.DsThemeTokens
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsInkColorsTest {
    @Test
    fun lightSettingsPreserveTheSharedReadableInkHierarchy() {
        val original = DsThemeTokens.light
        val actual = settingsInkColors(original)

        assertEquals(original.labelPrimary, actual.labelPrimary)
        assertEquals(original.labelSecondary, actual.labelSecondary)
        assertEquals(original.labelCaption, actual.labelCaption)
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

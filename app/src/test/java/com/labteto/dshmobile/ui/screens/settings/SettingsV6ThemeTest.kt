package com.labteto.dshmobile.ui.screens.settings

import com.shenyan.designsystem.foundation.ShenyanPalettes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** Regression for the canonical settings ink palette after removing the DsColors bridge. */
class SettingsV6ThemeTest {
    @Test fun lightSettingsUseCanonicalV6Colors() {
        val colors = ShenyanPalettes.light
        assertNotEquals(colors.canvas, colors.text)
        assertEquals(colors.primary, ShenyanPalettes.light.primary)
    }
    @Test fun darkSettingsKeepReadableInk() {
        val colors = ShenyanPalettes.dark
        assertNotEquals(colors.canvas, colors.text)
        assertNotEquals(colors.materialL3, colors.text)
    }
    @Test fun matteSettingsRetainTierHierarchy() {
        val colors = ShenyanPalettes.matte
        assertNotEquals(colors.materialL1, colors.materialL3)
        assertNotEquals(colors.canvas, colors.text)
    }
}

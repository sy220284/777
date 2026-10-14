package com.labteto.dshmobile.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvancedAppearanceDefaultsTest {
    @Test fun advancedAppearanceDefaultsDoNotShiftTheDesign() {
        val defaults = com.labteto.dshmobile.connection.AppSettings()
        assertEquals(.5f, defaults.ambientGlow, .0001f)
        assertEquals(.5f, defaults.componentEmphasis, .0001f)
        assertEquals(.5f, defaults.wallpaperSurfaceTransparency, .0001f)
        assertEquals(1f, defaults.motionDurationScale, .0001f)
        assertTrue(defaults.backgroundAdaptiveContrast)
    }

}

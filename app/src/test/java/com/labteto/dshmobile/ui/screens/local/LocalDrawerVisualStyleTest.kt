package com.labteto.dshmobile.ui.screens.local

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalDrawerVisualStyleTest {
    @Test fun modeTrackFollowsWallpaperWithoutLosingBlackLabelContrast() {
        val blue = drawerModeSurfaceColors(Color(0xFF4450B9))
        val coral = drawerModeSurfaceColors(Color(0xFFBB6755))
        val dark = drawerModeSurfaceColors(Color(0xFF171428))
        assertNotEquals(blue.first, coral.first)
        (listOf(blue, coral, dark, drawerModeSurfaceColors(null))).forEach { (track, thumb) ->
            assertTrue((track.luminance() + .05f) / .05f >= 4.5f)
            assertTrue((thumb.luminance() + .05f) / .05f >= 4.5f)
        }
    }
}

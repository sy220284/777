package com.labteto.dshmobile.ui.screens.local

import androidx.compose.ui.graphics.Color
import com.shenyan.designsystem.foundation.ShenyanPalettes
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalDrawerVisualStyleTest {
    @Test fun modeTrackFollowsWallpaperWithoutLosingBlackLabelContrast() {
        val blue = drawerModeSurfaceColors(ShenyanPalettes.light, Color(0xFF4450B9))
        val coral = drawerModeSurfaceColors(ShenyanPalettes.light, Color(0xFFBB6755))
        val dark = drawerModeSurfaceColors(ShenyanPalettes.light, Color(0xFF171428))
        assertNotEquals(blue.first, coral.first)
        (listOf(blue, coral, dark, drawerModeSurfaceColors(ShenyanPalettes.light, null))).forEach { (track, thumb) ->
            assertTrue((track.luminance() + .05f) / .05f >= 4.5f)
            assertTrue((thumb.luminance() + .05f) / .05f >= 4.5f)
        }
    }
    @Test fun matteDrawerUsesOpaqueThemeSurfaces() {
        val colors = ShenyanPalettes.matte
        val (track, thumb) = drawerModeSurfaceColors(colors, Color(0xFFE7BA8F))
        org.junit.Assert.assertEquals(colors.materialL1, track)
        org.junit.Assert.assertEquals(colors.materialL4, thumb)
    }
}

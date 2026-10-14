package com.shenyan.designsystem.components

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShenyanSceneBackdropTest {
    @Test fun sameWallpaperSamplesCorrespondingScreenRegions() {
        val image = IntSize(400, 800)
        val viewport = IntSize(200, 400)
        val top = sySceneImagePlacement(image, viewport, IntOffset.Zero)!!
        val next = sySceneImagePlacement(image, viewport, IntOffset(0, 100))!!
        assertEquals(IntSize(200, 400), top.size)
        assertEquals(top.size, next.size)
        assertEquals(IntOffset(0, -100), next.origin - top.origin)
    }

    @Test fun coverCropMaintainsGlobalImageCentering() {
        val placement = sySceneImagePlacement(IntSize(1000, 500), IntSize(250, 500), IntOffset.Zero)!!
        assertEquals(IntOffset(-375, 0), placement.origin)
        assertEquals(IntSize(1000, 500), placement.size)
    }

    @Test fun partiallyOffscreenSurfacesKeepRootScaleWithoutStretching() {
        val image = IntSize(800, 1600)
        val viewport = IntSize(400, 800)
        val above = sySceneImagePlacement(image, viewport, IntOffset(-20, -60))!!
        val below = sySceneImagePlacement(image, viewport, IntOffset(350, 760))!!
        assertEquals(IntOffset(20, 60), above.origin)
        assertEquals(IntOffset(-350, -760), below.origin)
        assertEquals(viewport, above.size)
        assertEquals(above.size, below.size)
    }

    @Test fun invalidOrMissingViewportNeverSamples() {
        assertNull(sySceneImagePlacement(IntSize(0, 300), IntSize(200, 400), IntOffset.Zero))
        assertNull(sySceneImagePlacement(IntSize(200, 400), IntSize.Zero, IntOffset.Zero))
    }
}

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
        val top = sySceneCrop(image, viewport, IntOffset(0, 0), IntSize(80, 100))!!
        val next = sySceneCrop(image, viewport, IntOffset(0, 100), IntSize(80, 100))!!
        assertEquals(IntOffset(0, 0), top.origin)
        assertEquals(IntSize(160, 200), top.size)
        assertEquals(IntOffset(0, 200), next.origin)
    }

    @Test fun coverCropMaintainsGlobalImageCentering() {
        val image = IntSize(1000, 500)
        val viewport = IntSize(250, 500)
        val crop = sySceneCrop(image, viewport, IntOffset(0, 0), IntSize(250, 500))!!
        assertEquals(IntOffset(375, 0), crop.origin)
        assertEquals(IntSize(250, 500), crop.size)
    }

    @Test fun invalidOrMissingViewportNeverSamples() {
        assertNull(sySceneCrop(IntSize(0, 300), IntSize(200, 400), IntOffset.Zero, IntSize(40, 40)))
        assertNull(sySceneCrop(IntSize(200, 400), IntSize.Zero, IntOffset.Zero, IntSize(40, 40)))
    }
}

package com.labteto.dshmobile.ui.sidebar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SidebarAvatarCropTest {

    @Test
    fun portraitFocusMovesCropTowardFaceWithoutLeavingBounds() {
        val focused = requireNotNull(
            calculateSidebarAvatarCrop(
                sourceWidth = 1024,
                sourceHeight = 1536,
                targetWidth = 52,
                targetHeight = 52,
                focusY = 0.38f,
            ),
        )
        val centered = requireNotNull(
            calculateSidebarAvatarCrop(
                sourceWidth = 1024,
                sourceHeight = 1536,
                targetWidth = 52,
                targetHeight = 52,
                focusY = 0.5f,
            ),
        )

        assertEquals(0f, focused.translateX, 0.0001f)
        assertTrue(focused.translateY > centered.translateY)
        assertTrue(focused.translateY <= 0f)
        assertTrue(focused.translateY >= 52f - 1536f * focused.scale)
    }

    @Test
    fun centeredFocusMatchesCenterCropForPortrait() {
        val crop = requireNotNull(
            calculateSidebarAvatarCrop(
                sourceWidth = 1024,
                sourceHeight = 1536,
                targetWidth = 52,
                targetHeight = 52,
                focusY = 0.5f,
            ),
        )

        assertEquals(52f / 1024f, crop.scale, 0.0001f)
        assertEquals(0f, crop.translateX, 0.0001f)
        assertEquals(-13f, crop.translateY, 0.0001f)
    }

    @Test
    fun verticalFocusIsClampedAndLandscapeDoesNotCreateBlankSpace() {
        val top = requireNotNull(
            calculateSidebarAvatarCrop(
                sourceWidth = 1024,
                sourceHeight = 1536,
                targetWidth = 52,
                targetHeight = 52,
                focusY = -1f,
            ),
        )
        val bottom = requireNotNull(
            calculateSidebarAvatarCrop(
                sourceWidth = 1024,
                sourceHeight = 1536,
                targetWidth = 52,
                targetHeight = 52,
                focusY = 2f,
            ),
        )
        val landscape = requireNotNull(
            calculateSidebarAvatarCrop(
                sourceWidth = 1536,
                sourceHeight = 1024,
                targetWidth = 52,
                targetHeight = 52,
                focusY = 0.38f,
            ),
        )

        assertEquals(0f, top.translateY, 0.0001f)
        assertEquals(-26f, bottom.translateY, 0.0001f)
        assertEquals(0f, landscape.translateY, 0.0001f)
        assertTrue(landscape.translateX < 0f)
    }
}

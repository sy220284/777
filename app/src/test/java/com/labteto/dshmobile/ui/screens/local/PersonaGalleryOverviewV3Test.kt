package com.labteto.dshmobile.ui.screens.local

import com.labteto.dshmobile.local.LocalGoal
import com.labteto.dshmobile.local.presentation.LocalWorkUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonaGalleryOverviewV3Test {
    @Test
    fun reconcileOrderKeepsManualOrderAndAppendsNewEntries() {
        val order = reconcilePersonaGalleryOrder(
            currentIds = listOf("new", "a", "b"),
            savedIds = listOf("b", "gone", "a"),
        )

        assertEquals(listOf("b", "a", "new"), order)
    }

    @Test
    fun moveEntryOnlySwapsInsideRequestedGroup() {
        val order = listOf("pin-a", "regular-a", "pin-b", "regular-b")

        val moved = movePersonaGalleryEntry(
            order = order,
            id = "pin-b",
            direction = -1,
            movableIds = setOf("pin-a", "pin-b"),
        )

        assertEquals(listOf("pin-b", "regular-a", "pin-a", "regular-b"), moved)
    }

    @Test
    fun emptyRunCenterStateIsRecognized() {
        assertFalse(LocalWorkUiState().hasRunCenterContent())
        assertTrue(LocalWorkUiState(running = true).hasRunCenterContent())
        assertTrue(
            LocalWorkUiState(
                goal = LocalGoal(
                    description = "test",
                    status = "active",
                ),
            ).hasRunCenterContent(),
        )
    }

    @Test
    fun portraitPhotoFramePreservesTallArtworkWithoutExhibitionPerspective() {
        val layout = personaPhotoFrameLayout(imageWidth = 1024, imageHeight = 1536)

        assertEquals(2f / 3f, layout.imageAspectRatio, 0.01f)
        assertEquals(0.64f, layout.widthFraction, 0.001f)
    }

    @Test
    fun landscapePhotoFrameUsesWiderAlbumLayout() {
        val layout = personaPhotoFrameLayout(imageWidth = 1536, imageHeight = 1024)

        assertEquals(1.5f, layout.imageAspectRatio, 0.01f)
        assertEquals(0.90f, layout.widthFraction, 0.001f)
    }

    @Test
    fun photoFrameFallsBackToStableSquareBeforeArtworkLoads() {
        val layout = personaPhotoFrameLayout(imageWidth = 0, imageHeight = 0)

        assertEquals(1f, layout.imageAspectRatio, 0.001f)
        assertEquals(0.78f, layout.widthFraction, 0.001f)
    }

}

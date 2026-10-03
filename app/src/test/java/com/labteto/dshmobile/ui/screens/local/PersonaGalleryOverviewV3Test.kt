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
}

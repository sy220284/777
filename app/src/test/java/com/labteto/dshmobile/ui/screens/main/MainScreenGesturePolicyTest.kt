package com.labteto.dshmobile.ui.screens.main

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainScreenGesturePolicyTest {
    @Test
    fun detailsSwipeOnlyStartsInsideVisibleRightPanel() {
        val width = 1080f
        val panelWidth = 300f

        assertFalse(detailsPanelOwnsRightSwipe(779f, width, panelWidth))
        assertTrue(detailsPanelOwnsRightSwipe(780f, width, panelWidth))
        assertTrue(detailsPanelOwnsRightSwipe(1000f, width, panelWidth))
        assertTrue(detailsPanelOwnsRightSwipe(1080f, width, panelWidth))
        assertFalse(detailsPanelOwnsRightSwipe(1081f, width, panelWidth))
    }

    @Test
    fun oversizedPanelTreatsWholeContainerAsVisiblePanel() {
        assertTrue(detailsPanelOwnsRightSwipe(0f, 320f, 480f))
        assertTrue(detailsPanelOwnsRightSwipe(320f, 320f, 480f))
    }

    @Test
    fun invalidGeometryNeverClaimsGesture() {
        assertFalse(detailsPanelOwnsRightSwipe(Float.NaN, 1080f, 300f))
        assertFalse(detailsPanelOwnsRightSwipe(100f, 0f, 300f))
        assertFalse(detailsPanelOwnsRightSwipe(100f, 1080f, 0f))
    }
}

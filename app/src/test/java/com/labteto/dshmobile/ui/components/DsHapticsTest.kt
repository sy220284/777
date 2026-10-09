package com.labteto.dshmobile.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class DsHapticsTest {
    @Test fun sliderDetentsAreStableWithinEachStep() {
        assertEquals(0, hapticTickIndex(0.90f, 0.90f, 1.30f, 8))
        assertEquals(1, hapticTickIndex(0.95f, 0.90f, 1.30f, 8))
        assertEquals(1, hapticTickIndex(0.951f, 0.90f, 1.30f, 8))
        assertEquals(8, hapticTickIndex(1.30f, 0.90f, 1.30f, 8))
    }
    @Test fun characterScaleHasTenDetents() {
        assertEquals(0, hapticTickIndex(-5f, 0f, 100f, 10))
        assertEquals(0, hapticTickIndex(4f, 0f, 100f, 10))
        assertEquals(1, hapticTickIndex(10f, 0f, 100f, 10))
        assertEquals(5, hapticTickIndex(50f, 0f, 100f, 10))
        assertEquals(10, hapticTickIndex(105f, 0f, 100f, 10))
    }
    @Test fun invalidInputAndDisabledSegmentsRemainSilent() {
        assertEquals(0, hapticTickIndex(Float.NaN, 0f, 1f, 8))
        assertEquals(0, hapticTickIndex(0.6f, 1f, 1f, 8))
        assertEquals(0, hapticTickIndex(0.6f, 0f, 1f, 0))
        assertEquals(1, hapticTickIndex(0.51f, 0f, 1f, 1))
        assertEquals(0, hapticTickIndex(0.49f, 0f, 1f, 1))
    }
}

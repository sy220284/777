package com.labteto.dshmobile.ui.shenyan

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Test

class ContextActionMenuPositionTest {
    private val bubble = IntRect(24, 100, 290, 320)
    private val menu = IntSize(120, 80)
    private val viewport = IntRect(12, 30, 306, 560)

    @Test
    fun twoPressesOnOneBubbleProduceDifferentMenuPositions() {
        assertEquals(IntOffset(40, 128), place(IntOffset(100, 120)))
        assertEquals(IntOffset(180, 298), place(IntOffset(240, 290)))
    }

    @Test
    fun nearBottomTheMenuFlipsAboveAndRespectsTheSafeHorizontalEdge() {
        assertEquals(IntOffset(186, 442), place(IntOffset(302, 530)))
    }

    @Test
    fun buttonAndAccessibilityActionsUseTheLayoutAnchor() {
        assertEquals(IntOffset(170, 328), place(null))
        assertEquals(IntOffset(24, 328), place(null, LayoutDirection.Rtl))
    }

    private fun place(point: IntOffset?, direction: LayoutDirection = LayoutDirection.Ltr) =
        contextActionMenuPosition(bubble, menu, viewport, direction, point, gap = 8)
}

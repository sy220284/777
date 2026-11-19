package com.labteto.dshmobile.ui.shenyan

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Window-coordinate regression tests for finger-anchored conversation actions. */
class ConversationMenuPositionTest {
    private val window = IntSize(400, 900)
    private val popup = IntSize(220, 256)
    private val row = IntRect(0, 230, 384, 282)

    private fun position(
        anchor: IntRect = row,
        size: IntSize = popup,
        finger: Offset? = Offset(120f, 26f),
        direction: LayoutDirection = LayoutDirection.Ltr,
        viewport: IntSize = window,
    ) = contextActionMenuPosition(
        anchor, size, IntRect(12, 12, viewport.width - 12, viewport.height - 12),
        direction, finger?.let { IntOffset(anchor.left + it.x.toInt(), anchor.top + it.y.toInt()) }, 12,
    ).offset

    @Test fun menuFollowsLongPressRatherThanTheRowOrigin() {
        val left = position(finger = Offset(90f, 26f))
        val right = position(finger = Offset(260f, 26f))
        assertEquals(268, left.y)
        assertEquals(left.y, right.y)
        assertTrue("A different finger X must move the menu", right.x > left.x)
        assertTrue(left.y > row.top + 26)
    }

    @Test fun menuFlipsAboveFingerAtBottomOfScreen() {
        val anchor = IntRect(0, 770, 384, 824)
        val result = position(anchor = anchor, finger = Offset(150f, 28f))
        assertEquals(770 + 28 - 256 - 12, result.y)
        assertTrue(result.y + popup.height <= 900)
    }

    @Test fun rightEdgeAndTopBarAnchorsStayInsideTheViewport() {
        val result = position(
            anchor = IntRect(360, 34, 392, 84),
            finger = null,
        )
        assertEquals(168, result.x)
        assertEquals(96, result.y)
    }

    @Test fun rtlHeaderMenuStartsAtTheAnchorLeftEdge() {
        val result = position(
            anchor = IntRect(140, 30, 188, 82),
            finger = null,
            direction = LayoutDirection.Rtl,
        )
        assertEquals(140, result.x)
        assertEquals(94, result.y)
    }

    @Test fun drawerRemainsInsideItsRowWhenItFits() {
        val drawer = IntRect(10, 150, 310, 200)
        val result = contextActionMenuPosition(
            drawer, popup, IntRect(8, 8, 392, 892), LayoutDirection.Ltr,
            IntOffset(304, 178), 8, stayWithinAnchor = true,
        )
        assertEquals(82, result.offset.x)
        assertEquals(186, result.offset.y)
    }

    @Test fun oversizedMenuAndNarrowViewportNeverProduceNegativeCoordinates() {
        val result = position(
            anchor = IntRect(0, 40, 180, 100),
            size = IntSize(340, 780),
            finger = Offset(8f, 35f),
            viewport = IntSize(320, 640),
        )
        assertTrue(result.x >= 0)
        assertTrue(result.y >= 0)
    }
}

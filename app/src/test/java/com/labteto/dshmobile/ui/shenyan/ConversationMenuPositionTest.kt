package com.labteto.dshmobile.ui.shenyan

import androidx.compose.ui.geometry.Offset
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
    ) = conversationMenuPosition(anchor, viewport, size, finger, direction, 12, 12)

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

    @Test fun longPressMenuIsContainedByTheDrawerInsteadOfCoveringTheChat() {
        val drawerRow = IntRect(18, 280, 340, 332)
        val menu = IntSize(206, 218)
        val result = conversationMenuPosition(
            anchorBounds = drawerRow,
            windowSize = IntSize(400, 900),
            menuSize = menu,
            touchOffset = Offset(300f, 20f),
            layoutDirection = LayoutDirection.Ltr,
            marginPx = 10,
            fingerGapPx = 8,
        )
        assertTrue(result.x >= drawerRow.left + 10)
        assertTrue(result.x + menu.width <= drawerRow.right - 10)
        assertEquals(340 - 206 - 10, result.x)
    }

    @Test fun narrowDrawerStillKeepsTheMenuOnScreen() {
        val result = conversationMenuPosition(
            anchorBounds = IntRect(10, 280, 178, 332),
            windowSize = IntSize(240, 470),
            menuSize = IntSize(206, 218),
            touchOffset = Offset(160f, 20f),
            layoutDirection = LayoutDirection.Ltr,
            marginPx = 10,
            fingerGapPx = 8,
        )
        assertTrue(result.x >= 0)
        assertTrue(result.x + 206 <= 240)
        assertTrue(result.y >= 0)
        assertTrue(result.y + 218 <= 470)
    }
}

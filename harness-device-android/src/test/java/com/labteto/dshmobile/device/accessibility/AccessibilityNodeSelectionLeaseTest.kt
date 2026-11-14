package com.labteto.dshmobile.device.accessibility

import org.junit.Assert.assertThrows
import org.junit.Test

class AccessibilityNodeSelectionLeaseTest {
    private fun node(index: Int, text: String = "保存", bounds: String = "[0,0][100,48]") =
        AccessibilityNodeSnapshot(index, text, null, "id/save", "Button", true, false, bounds, 2)

    @Test fun indexIsValidOnlyAfterARealScreenSnapshot() {
        val selection = AccessibilityNodeSelectionLease()
        assertThrows(IllegalStateException::class.java) {
            selection.requireCurrent(0, 1, node(0))
        }
        selection.replace(1, listOf(node(0)))
        selection.requireCurrent(0, 1, node(0))
    }

    @Test fun staleIndexCannotTargetAnotherWindowOrControl() {
        val selection = AccessibilityNodeSelectionLease()
        selection.replace(1, listOf(node(0), node(1, "删除")))
        assertThrows(IllegalStateException::class.java) {
            selection.requireCurrent(1, 2, node(1, "删除"))
        }
        assertThrows(IllegalStateException::class.java) {
            selection.requireCurrent(1, 1, node(1, "确认删除"))
        }
        assertThrows(IllegalStateException::class.java) {
            selection.requireCurrent(0, 1, node(0, bounds = "[100,0][200,48]"))
        }
    }

    @Test fun contentChangesInvalidateTheEntireSelection() {
        val selection = AccessibilityNodeSelectionLease()
        selection.replace(1, listOf(node(0)))
        selection.invalidate()
        assertThrows(IllegalStateException::class.java) {
            selection.requireCurrent(0, 1, node(0))
        }
        selection.replace(1, listOf(node(0, "取消")))
        assertThrows(IllegalStateException::class.java) {
            selection.requireCurrent(0, 1, node(0))
        }
        selection.requireCurrent(0, 1, node(0, "取消"))
    }
}

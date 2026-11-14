package com.labteto.dshmobile.device.accessibility

import org.junit.Assert.assertEquals
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
    @Test fun unchangedNodeRemainsUsableAcrossUnrelatedScreenEvents() {
        val selection = AccessibilityNodeSelectionLease()
        val input = AccessibilityNodeSnapshot(2, "", null, null, "EditText", true, true,
            "[10,100][500,150]", 3)
        selection.replace(4, listOf(node(0), node(1), input))
        // A content event alone is not evidence that this control has changed.
        selection.requireCurrent(2, 4, input)
        org.junit.Assert.assertEquals(2, selection.resolveEditableIndex(2, 4,
            listOf(node(0), node(1), input)))
    }

    @Test fun composeInputRebindsAfterSemanticsTreeReindexes() {
        val selection = AccessibilityNodeSelectionLease()
        val input = AccessibilityNodeSnapshot(1, "", null, null, "EditText", true, true,
            "[10,100][500,150]", 3)
        selection.replace(4, listOf(node(0), input))
        val reindexed = input.copy(index = 2)
        org.junit.Assert.assertEquals(2, selection.resolveEditableIndex(1, 4,
            listOf(node(0), node(1, "新增"), reindexed)))
    }

    @Test fun composeFieldCanMoveAfterImeLayoutWithoutLosingItsIdentity() {
        val selection = AccessibilityNodeSelectionLease()
        val input = AccessibilityNodeSnapshot(1, "", "搜索", null, "EditText",
            true, true, "[10,100][500,150]", 3)
        selection.replace(4, listOf(node(0), input))
        val moved = input.copy(index = 2, bounds = "[10,60][500,110]")
        assertEquals(2, selection.resolveEditableIndex(1, 4,
            listOf(node(0), node(1, "其他"), moved)))
    }

    @Test fun composeRebindingNeverPicksAnotherSingleEditableFieldWithChangedSemantics() {
        val selection = AccessibilityNodeSelectionLease()
        val input = AccessibilityNodeSnapshot(1, "", "手机号", null, "EditText",
            true, true, "[10,100][500,150]", 3)
        selection.replace(4, listOf(node(0), input))
        val changedScreen = input.copy(index = 2, bounds = "[10,80][500,130]",
            contentDescription = "验证码")
        assertThrows(IllegalStateException::class.java) {
            selection.resolveEditableIndex(1, 4,
                listOf(node(0), node(1, "其他"), changedScreen))
        }
    }

    @Test fun composeFieldMoveRemainsAmbiguousWhenTwoFieldsHaveSameSemantics() {
        val selection = AccessibilityNodeSelectionLease()
        val input = AccessibilityNodeSnapshot(1, "", "搜索", null, "EditText",
            true, true, "[10,100][500,150]", 3)
        selection.replace(4, listOf(node(0), input))
        assertThrows(IllegalStateException::class.java) {
            selection.resolveEditableIndex(1, 4, listOf(
                node(0),
                input.copy(index = 1, bounds = "[10,60][500,110]"),
                input.copy(index = 2, bounds = "[10,170][500,220]"),
            ))
        }
    }

    @Test fun composeInputRebindRejectsWindowChangeAndAmbiguousFields() {
        val selection = AccessibilityNodeSelectionLease()
        val input = AccessibilityNodeSnapshot(1, "", null, null, "EditText", false, true,
            "[10,100][500,150]", 3)
        selection.replace(4, listOf(node(0), input))
        assertThrows(IllegalStateException::class.java) {
            selection.resolveEditableIndex(1, 8, listOf(input))
        }
        val live = listOf(node(0), node(1), input.copy(index = 2), input.copy(index = 3))
        assertThrows(IllegalStateException::class.java) {
            selection.resolveEditableIndex(1, 4, live)
        }
    }

    @Test fun textActionsNeverGuessAmongDuplicateControls() {
        org.junit.Assert.assertNull(requireUniqueAccessibilityTarget(emptyList<String>(), "点击"))
        org.junit.Assert.assertEquals(
            "唯一按钮", requireUniqueAccessibilityTarget(listOf("唯一按钮"), "点击"),
        )
        val error = assertThrows(IllegalStateException::class.java) {
            requireUniqueAccessibilityTarget(listOf("删除", "删除"), "点击")
        }
        org.junit.Assert.assertTrue(error.message.orEmpty().contains("android_find"))
        assertThrows(IllegalStateException::class.java) {
            requireUniqueAccessibilityTarget(listOf("账号", "账号"), "输入文字")
        }
    }
}

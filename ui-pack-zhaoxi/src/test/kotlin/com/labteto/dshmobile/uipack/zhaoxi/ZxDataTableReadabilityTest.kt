package com.labteto.dshmobile.uipack.zhaoxi

import com.labteto.dshmobile.uipack.zhaoxi.components.zxTableColumnWidthDp
import com.labteto.dshmobile.uipack.zhaoxi.components.zxTableCellReadingLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 长回复表格在高字体倍率下不能沿用未缩放的固定列宽。 */
class ZxDataTableReadabilityTest {
    @Test
    fun columnWidthIncreasesWithFontScale() {
        val normal = zxTableColumnWidthDp("执行状态", listOf("执行成功等待验证完成"), 1f)
        val zoomed = zxTableColumnWidthDp("执行状态", listOf("执行成功等待验证完成"), 2f)
        assertEquals(140f, normal, 0.01f)
        assertEquals(normal * 2f, zoomed, 0.01f)
    }

    @Test
    fun latinAndCjkCellsReceiveDifferentSpace() {
        val latin = zxTableColumnWidthDp("State", listOf("Completed"), 1f)
        val han = zxTableColumnWidthDp("状态", listOf("已完成正在验证"), 1f)
        assertTrue(han > latin)
    }

    @Test
    fun screenReaderReadsColumnNameAndEmptyCells() {
        assertEquals("运行状态：已完成", zxTableCellReadingLabel("运行状态", "已完成"))
        assertEquals("进度：空白", zxTableCellReadingLabel("进度", ""))
    }

    @Test
    fun columnWidthRemainsBoundedForVeryLongData() {
        assertEquals(252f, zxTableColumnWidthDp("超长字段".repeat(80), emptyList(), 1f), 0.01f)
        assertEquals(84f, zxTableColumnWidthDp("a", emptyList(), 0.85f), 0.01f)
    }
}

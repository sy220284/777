package com.labteto.dshmobile.uipack.zhaoxi

import com.labteto.dshmobile.uicontract.*
import com.labteto.dshmobile.uipack.zhaoxi.scenes.zxDocumentDisplayValues
import com.labteto.dshmobile.uipack.zhaoxi.scenes.zxMcpStdioCommand
import org.junit.Assert.*
import org.junit.Test

class ZhaoxiInterfaceActionsTest {
    @Test fun portraitCropUsesOriginalResolutionAndKeepsSmallValidSelections() {
        assertEquals(listOf(0, 0, 800, 600), UiPortraitCrop(0f, 0f, 1f, 1f).pixels(800, 600))
        assertEquals(listOf(100, 150, 700, 450), UiPortraitCrop(.125f, .25f, .875f, .75f).pixels(800, 600))
        assertEquals(listOf(1, 1, 2, 2), UiPortraitCrop(.9f, .9f, .91f, .91f).pixels(2, 2))
        assertThrows(IllegalArgumentException::class.java) { UiPortraitCrop(.5f, 0f, .5f, 1f) }
        assertThrows(IllegalArgumentException::class.java) { UiPortraitCrop(Float.NaN, 0f, 1f, 1f) }
    }

    @Test fun stdioPreservesArgumentsWithoutShellSplittingOrExpansion() {
        val source = """["/local/my program", "argument with spaces", "$(echo keep-literal)", ""]"""
        assertEquals(listOf("/local/my program", "argument with spaces", "$(echo keep-literal)", ""),
            zxMcpStdioCommand(source))
        listOf("node server.js", "[]", "[1]", "[\"\"]", "[\"node\", {}]").forEach {
            assertNull(zxMcpStdioCommand(it))
        }
    }

    @Test fun displayEditorRetainsExactTableAndChartAddressesAndExcludesAuthority() {
        val values = zxDocumentDisplayValues(listOf(
            UiDocumentNode.Column("layout", listOf(
                UiDocumentNode.Text("body", "正文"),
                UiDocumentNode.Table("table", listOf("姓名", "数量"),
                    listOf(listOf("甲", "1"), listOf("乙", "2"))),
                UiDocumentNode.Disclosure("fold", "图表", listOf(
                    UiDocumentNode.BarChart("bar", "数量", "项", listOf(UiChartPoint("甲", 2.0))),
                    UiDocumentNode.LineChart("line", "变化", "项", listOf(UiChartPoint("乙", 3.0))),
                )),
                UiDocumentNode.BoundText("phase", "执行状态", UiDocumentBinding.EXECUTION_PHASE),
                UiDocumentNode.Field("private", "输入", "原始草稿", false, true),
                UiDocumentNode.Button("approve", "批准", "approval-action"),
            )),
        ))
        assertEquals(listOf("body", "table", "table", "table", "table", "bar", "line"), values.map { it.nodeId })
        assertEquals("2", values[4].value)
        assertEquals(1, values[4].row)
        assertEquals(1, values[4].column)
        assertEquals(0, values[5].point)
        assertEquals("3.0", values[6].value)
        assertTrue(values.none { it.nodeId in setOf("phase", "private", "approve") })
    }
}

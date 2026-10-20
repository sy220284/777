package com.labteto.dshmobile.ui.screens.local

import com.labteto.dshmobile.uicontract.UiDocument
import com.labteto.dshmobile.uicontract.UiDocumentNode
import com.labteto.dshmobile.uicontract.UiDocumentSource
import com.labteto.dshmobile.uicontract.UiChartPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LocalReplyDocumentPreviewTest {
    private fun document(vararg nodes: UiDocumentNode) = UiDocument(
        schemaVersion = 1, documentId = "doc", revision = 1,
        source = UiDocumentSource("session", "reply", 0L, null, null),
        title = "分析报告", nodes = nodes.toList(), actions = emptyList(),
        fallbackText = "历史摘要"
    )

    @Test fun visibleTextIsPreviewedWithoutDuplicatingFullDocument() {
        val preview = localReplyDocumentPreview(document(
            UiDocumentNode.Text("summary", "已完成代码与界面核查。"),
            UiDocumentNode.Text("details", "后续还有很多详细数据。"),
        ))
        assertEquals("已完成代码与界面核查。", preview)
    }

    @Test fun chartAndTableShowCompactMetadataInsteadOfDroppingTheirContent() {
        val table = localReplyDocumentPreview(document(
            UiDocumentNode.Table("table", listOf("模块", "结果"), listOf(listOf("消息", "通过")))
        ))
        assertEquals("表格 · 1 行 · 2 列", table)
        val chart = localReplyDocumentPreview(document(
            UiDocumentNode.BarChart("chart", "通过率", "%", listOf(UiChartPoint("本周", 82.0)))
        ))
        assertEquals("通过率 · 1 项", chart)
    }

    @Test fun privateFormInputsNeverAppearAsPlaintextPreview() {
        val sensitive = "TEST-PRIVATE-VALUE"
        val preview = localReplyDocumentPreview(document(
            UiDocumentNode.Column("group", listOf(
                UiDocumentNode.Field("secret", "密钥", sensitive, required = false, multiline = false),
                UiDocumentNode.Select("pick", "选择账号", listOf(sensitive), sensitive)
            ))
        ))
        assertEquals("交互表单 · 请展开填写", preview)
        assertFalse(preview.orEmpty().contains(sensitive))
    }

    @Test fun emptyDocumentUsesNeutralTitleOnly() {
        assertEquals(null, localReplyDocumentPreview(document()))
    }
}

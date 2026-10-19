package com.labteto.dshmobile.uipack.zhaoxi.scenes

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.uicontract.*
import com.labteto.dshmobile.uipack.zhaoxi.components.*
import com.labteto.dshmobile.uihost.rememberUiPrivateText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal fun UiDocumentReceipt.zxDocumentFeedback(): String = when (this) {
    UiDocumentReceipt.AdditionalRequestConsentRequired -> "需要先确认额外模型请求"
    is UiDocumentReceipt.Published -> "文档已保存"
    UiDocumentReceipt.Deleted -> "文档已删除"
    UiDocumentReceipt.DraftPrepared -> "已填入输入框，请核对后发送"
    UiDocumentReceipt.NavigationRequested -> "已提交页面打开请求"
    is UiDocumentReceipt.OwnerReceipt -> when (val response = receipt) {
        UiCommandReceipt.Accepted -> "操作已受理"
        UiCommandReceipt.Queued -> "操作已排队"
        is UiCommandReceipt.Failed -> response.message
        else -> "操作未被接受，请核对当前会话"
    }
    is UiDocumentReceipt.Rejected -> "当前内容已变化或操作不可用，请重新打开文档后重试"
    is UiDocumentReceipt.InvalidFields -> "请检查必填项：${fieldIds.joinToString("、")}"
    is UiDocumentReceipt.Fallback -> text
    is UiDocumentReceipt.Failed -> message ?: "操作失败，编辑内容仍保留"
}

/** Closed display edits only. Bound status, forms and actions cannot be edited into authority. */
internal data class ZxDocumentValue(
    val nodeId: String, val label: String, val value: String,
    val row: Int? = null, val column: Int? = null, val point: Int? = null,
)

internal fun zxDocumentDisplayValues(nodes: List<UiDocumentNode>): List<ZxDocumentValue> = buildList {
    fun visit(node: UiDocumentNode) {
        when (node) {
            is UiDocumentNode.Text -> add(ZxDocumentValue(node.id, "正文 · ${node.id}", node.text))
            is UiDocumentNode.Table -> node.rows.forEachIndexed { r, row ->
                row.forEachIndexed { c, cell -> add(ZxDocumentValue(node.id,
                    "${node.id} · 第 ${r + 1} 行 · ${node.headers.getOrNull(c).orEmpty()}", cell, r, c)) }
            }
            is UiDocumentNode.BarChart -> node.points.forEachIndexed { i, point ->
                add(ZxDocumentValue(node.id, "${node.title} · ${point.label}", point.value.toString(), point = i))
            }
            is UiDocumentNode.LineChart -> node.points.forEachIndexed { i, point ->
                add(ZxDocumentValue(node.id, "${node.title} · ${point.label}", point.value.toString(), point = i))
            }
            is UiDocumentNode.Column -> node.children.forEach(::visit)
            is UiDocumentNode.Row -> node.children.forEach(::visit)
            is UiDocumentNode.Disclosure -> node.children.forEach(::visit)
            else -> Unit
        }
    }
    nodes.forEach(::visit)
}

@Composable
internal fun ZhaoxiDocumentDisplayEditor(
    port: UiDocumentUiPort, document: UiDocument, context: () -> ZhaoxiDocumentContext?,
) {
    var editing by remember(document.documentId, document.source) { mutableStateOf<UiDocument?>(null) }
    var identity by remember(document.documentId, document.source) { mutableStateOf<UiSessionIdentity?>(null) }
    var selected by remember(editing) { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<DeleteUiDocumentCommand?>(null) }
    var feedback by remember(document.documentId) { mutableStateOf<String?>(null) }
    val jobs = rememberCoroutineScope()
    fun execute(action: suspend () -> UiDocumentReceipt) {
        if (busy) return
        busy = true
        jobs.launch {
            try {
                val result = action()
                feedback = result.zxDocumentFeedback()
                if (result is UiDocumentReceipt.Published || result == UiDocumentReceipt.Deleted) editing = null
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { feedback = failure.message ?: "保存失败，编辑内容仍保留" }
            finally { busy = false }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (document.actions.none { it is UiDocumentAction.AnswerApproval })
        ZxButton("编辑显示内容", {
            identity = context()?.identity
            editing = document
        }, variant = ZxButtonVariant.Outline, enabled = !busy)
        ZxButton("删除文档", {
            val source = context()?.identity
            if (source == null) feedback = "会话已失效，请返回会话后重试"
            else pendingDelete = DeleteUiDocumentCommand(source, document.documentId, document.revision, document.source)
        }, variant = ZxButtonVariant.Danger, enabled = !busy)
    }
    feedback?.let { ZxFooterNote(it) }
    editing?.let { original ->
        val values = remember(original) { zxDocumentDisplayValues(original.nodes) }
        ZxFooterNote("只修改文档显示内容，不改变工具执行或审批状态。其他地方更新文档后，旧版本编辑会被拒绝。")
        if (values.isEmpty()) ZxEmpty("没有可编辑的显示内容", "可编辑正文、表格单元格与图表数值")
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            values.forEachIndexed { index, value -> ZxChip(value.label, selected == index) { selected = index } }
        }
        values.getOrNull(selected)?.let { target ->
            var text by rememberUiPrivateText("zhaoxi.document.${original.documentId}.${original.revision}.$selected",
                original.source, target) { target.value }
            ZxField(text, { text = it }, Modifier.fillMaxWidth().heightIn(min = 80.dp), fieldLabel = target.label)
            ZxButton("保存当前修改", {
                val expected = identity
                val number = if (target.point != null) text.toDoubleOrNull() else null
                if (expected == null) feedback = "会话已失效，编辑内容仍保留"
                else if (target.point != null && (number == null || !number.isFinite())) feedback = "请输入有效数值"
                else execute {
                    when {
                        target.point != null -> port.replaceChartPoint(EditUiDocumentChartPointCommand(expected,
                            original.documentId, original.revision, original.source, target.nodeId, target.point, requireNotNull(number)))
                        target.row != null && target.column != null -> port.replaceTableCell(EditUiDocumentTableCellCommand(expected,
                            original.documentId, original.revision, original.source, target.nodeId, target.row, target.column, text))
                        else -> port.replaceText(EditUiDocumentTextCommand(expected,
                            original.documentId, original.revision, original.source, target.nodeId, text))
                    }
                }
            }, enabled = !busy && text != target.value, loading = busy)
        }
        ZxButton("关闭编辑", { editing = null }, variant = ZxButtonVariant.Outline, enabled = !busy)
    }
    pendingDelete?.let { command -> ZxDialog("删除文档？", "删除此文档的本地显示记录，原会话消息保留。", "删除文档", {
        pendingDelete = null
        execute { port.delete(command) }
    }, { pendingDelete = null }) }
}

package com.labteto.dshmobile.ui.screens.local

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labteto.dshmobile.uicontract.DeleteUiDocumentCommand
import com.labteto.dshmobile.uicontract.EditUiDocumentChartPointCommand
import com.labteto.dshmobile.uicontract.EditUiDocumentTableCellCommand
import com.labteto.dshmobile.uicontract.EditUiDocumentTextCommand
import com.labteto.dshmobile.uicontract.GenerateLocalUiDocumentCommand
import com.labteto.dshmobile.uicontract.GenerateUiDocumentCommand
import com.labteto.dshmobile.uicontract.UiDocument
import com.labteto.dshmobile.uicontract.UiDocumentAction
import com.labteto.dshmobile.uicontract.UiDocumentActionRequest
import com.labteto.dshmobile.uicontract.UiDocumentBinding
import com.labteto.dshmobile.uicontract.UiDocumentBoundValue
import com.labteto.dshmobile.uicontract.UiDocumentNode
import com.labteto.dshmobile.uicontract.UiDocumentReceipt
import com.labteto.dshmobile.uicontract.UiDocumentSnapshot
import com.labteto.dshmobile.uicontract.UiDocumentUiPort
import com.labteto.dshmobile.uicontract.UiLocalDocumentTemplate
import com.labteto.dshmobile.uicontract.UiSessionIdentity
import com.labteto.dshmobile.uicontract.initialFields
import com.labteto.dshmobile.ui.shenyan.SyButton
import com.labteto.dshmobile.ui.shenyan.SyButtonSize
import com.labteto.dshmobile.ui.shenyan.SyButtonVariant
import com.labteto.dshmobile.ui.shenyan.SyResponsiveSheet
import com.shenyan.designsystem.foundation.LocalSyColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * V6-native rendering of durable interactive documents. The Session/Work Owner performs
 * every revision, identity, approval and submission check; document nodes are display data.
 */
@Composable
internal fun NativeV6DocumentsSheet(
    port: UiDocumentUiPort,
    identity: () -> UiSessionIdentity?,
    currentMessageId: () -> String?,
    workMode: Boolean,
    onPreparedDraft: () -> Unit,
    onDismiss: () -> Unit,
) {
    val snapshot by port.snapshots.collectAsStateWithLifecycle(initialValue = port.snapshot())
    val scope = rememberCoroutineScope()
    val colors = LocalSyColors.current
    val current = identity()
    val items = snapshot.documents.filter { it.document.source.sessionId == current?.sessionId }
    var prompt by remember(current?.sessionId) { mutableStateOf("") }
    var modelConsent by remember(current?.sessionId) { mutableStateOf(false) }
    var notice by remember(current?.sessionId) { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    fun doCommand(block: suspend () -> UiDocumentReceipt) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                val receipt = block()
                notice = when (receipt) {
                    UiDocumentReceipt.DraftPrepared -> {
                        onPreparedDraft()
                        "已放入 V6 输入框，等待确认发送"
                    }
                    is UiDocumentReceipt.Published -> "文档已保存"
                    UiDocumentReceipt.Deleted -> "文档已删除，原会话消息仍保留"
                    is UiDocumentReceipt.Failed -> receipt.message ?: "操作失败"
                    is UiDocumentReceipt.Rejected -> "操作被拒绝：${receipt.reason}"
                    is UiDocumentReceipt.InvalidFields -> "请检查输入：${receipt.fieldIds.joinToString()}"
                    is UiDocumentReceipt.Fallback -> receipt.text
                    is UiDocumentReceipt.OwnerReceipt -> "已收到业务执行回执：${receipt.receipt}"
                    UiDocumentReceipt.NavigationRequested -> "已请求打开原生页面"
                    UiDocumentReceipt.AdditionalRequestConsentRequired -> "需要确认额外模型请求"
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                notice = failure.message ?: "文档操作失败"
            } finally {
                busy = false
            }
        }
    }

    SyResponsiveSheet("交互文档", onDismiss = onDismiss,
        subtitle = "当前会话的持久文档与可编辑表单") {
        Column(Modifier.fillMaxWidth().heightIn(max = 580.dp)
            .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (snapshot.generating || busy) Text("正在处理…", color = colors.textSecondary)
            snapshot.error?.let { Text("文档读取失败：$it", color = colors.dangerInk) }
            notice?.let { Text(it, color = colors.textSecondary) }

            if (current != null && currentMessageId() != null) {
                val msg = requireNotNull(currentMessageId())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SyButton("本地状态卡", onClick = {
                        doCommand { port.generateLocal(GenerateLocalUiDocumentCommand(
                            current, workMode, msg, UiLocalDocumentTemplate.STATUS)) }
                    }, enabled = !busy, size = SyButtonSize.Small, variant = SyButtonVariant.Outline)
                    SyButton("回复表单", onClick = {
                        doCommand { port.generateLocal(GenerateLocalUiDocumentCommand(
                            current, workMode, msg, UiLocalDocumentTemplate.REPLY_FORM)) }
                    }, enabled = !busy, size = SyButtonSize.Small, variant = SyButtonVariant.Outline)
                }
                OutlinedTextField(
                    value = prompt, onValueChange = { prompt = it },
                    label = { Text("需要生成的交互文档") },
                    modifier = Modifier.fillMaxWidth(), maxLines = 3,
                )
                SyButton("请求模型生成文档", onClick = { modelConsent = true },
                    enabled = !busy && prompt.isNotBlank(), modifier = Modifier.fillMaxWidth(),
                    variant = SyButtonVariant.Ghost)
            }
            if (items.isEmpty() && !snapshot.generating) {
                Text("当前会话还没有交互文档，可使用本地模板创建。", color = colors.textSecondary)
            }
            items.forEach { item ->
                NativeV6DocumentCard(
                    item = item,
                    enabled = !busy && current != null,
                    onAction = { actionId, fields, confirmed ->
                        val id = identity()
                        if (id != null) {
                            doCommand { port.submit(id, UiDocumentActionRequest(
                                item.document.documentId, item.document.revision,
                                item.document.source, actionId, fields, confirmed,
                            )) }
                        } else notice = "会话已变化，请重新打开文档"
                    },
                    onDelete = {
                        val id = identity()
                        if (id != null) doCommand { port.delete(DeleteUiDocumentCommand(
                            id, item.document.documentId, item.document.revision,
                            item.document.source,
                        )) }
                    },
                    onEditText = { nodeId, value ->
                        val id = identity()
                        if (id != null) doCommand { port.replaceText(EditUiDocumentTextCommand(
                            id, item.document.documentId, item.document.revision,
                            item.document.source, nodeId, value,
                        )) }
                    },
                    onEditTableCell = { nodeId, row, col, value ->
                        val id = identity()
                        if (id != null) doCommand { port.replaceTableCell(EditUiDocumentTableCellCommand(
                            id, item.document.documentId, item.document.revision,
                            item.document.source, nodeId, row, col, value,
                        )) }
                    },
                    onEditChart = { nodeId, point, value ->
                        val id = identity()
                        if (id != null) doCommand { port.replaceChartPoint(EditUiDocumentChartPointCommand(
                            id, item.document.documentId, item.document.revision,
                            item.document.source, nodeId, point, value,
                        )) }
                    },
                )
            }
        }
    }
    if (modelConsent && current != null) {
        AlertDialog(
            onDismissRequest = { modelConsent = false },
            title = { Text("确认额外模型调用") },
            text = { Text("生成交互文档会产生一次新的模型请求和相应消耗，确定继续吗？") },
            confirmButton = {
                SyButton("确认生成", onClick = {
                    modelConsent = false
                    val msg = currentMessageId() ?: return@SyButton
                    doCommand { port.generate(GenerateUiDocumentCommand(current, workMode, msg,
                        prompt.trim(), acknowledgedAdditionalModelRequest = true)) }
                })
            },
            dismissButton = { SyButton("取消", onClick = { modelConsent = false },
                variant = SyButtonVariant.Ghost) },
        )
    }
}

@Composable
private fun NativeV6DocumentCard(
    item: UiDocumentSnapshot,
    enabled: Boolean,
    onAction: (String, Map<String, String>, Boolean) -> Unit,
    onDelete: () -> Unit,
    onEditText: (String, String) -> Unit,
    onEditTableCell: (String, Int, Int, String) -> Unit,
    onEditChart: (String, Int, Double) -> Unit,
) {
    val doc = item.document
    val colors = LocalSyColors.current
    val fields = remember(doc.documentId, doc.revision, doc.source.sessionId) {
        mutableStateMapOf<String, String>().apply { putAll(doc.initialFields()) }
    }
    var confirmAction by remember(doc.documentId, doc.revision) { mutableStateOf<String?>(null) }
    var confirmDelete by remember(doc.documentId, doc.revision) { mutableStateOf(false) }
    var edit by remember(doc.documentId, doc.revision) { mutableStateOf<NativeDocumentEdit?>(null) }
    var editValue by remember(doc.documentId, doc.revision) { mutableStateOf("") }
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(doc.title, color = colors.text)
        item.recoveryNotice?.let { Text(it, color = colors.warningInk) }
        doc.nodes.forEach { node ->
            NativeV6DocumentNode(
                node = node,
                item = item,
                fields = fields,
                enabled = enabled,
                onEdit = { target, value -> edit = target; editValue = value },
                onAction = { actionId ->
                    val action = doc.actions.firstOrNull { it.id == actionId }
                    if (action is UiDocumentAction.SendUserInput && action.submit ||
                        action is UiDocumentAction.AnswerApproval) confirmAction = actionId
                    else onAction(actionId, fields.toMap(), false)
                },
            )
        }
        if (!doc.documentId.startsWith("work-live:") && item.recoveryNotice == null) {
            SyButton("删除文档", onClick = { confirmDelete = true },
                enabled = enabled, size = SyButtonSize.Small, variant = SyButtonVariant.Ghost)
        }
    }
    if (confirmAction != null) {
        val id = requireNotNull(confirmAction)
        AlertDialog(onDismissRequest = { confirmAction = null },
            title = { Text("确认文档操作") },
            text = { Text("该操作会向当前会话提交输入或处理审批，请确认。") },
            confirmButton = { SyButton("确认执行", onClick = {
                confirmAction = null
                onAction(id, fields.toMap(), true)
            }) },
            dismissButton = { SyButton("取消", onClick = { confirmAction = null },
                variant = SyButtonVariant.Ghost) })
    }
    if (confirmDelete) {
        AlertDialog(onDismissRequest = { confirmDelete = false },
            title = { Text("删除交互文档？") },
            text = { Text("仅删除这份文档，不会删除原会话消息。") },
            confirmButton = { SyButton("确认删除", onClick = {
                confirmDelete = false
                onDelete()
            }) },
            dismissButton = { SyButton("取消", onClick = { confirmDelete = false },
                variant = SyButtonVariant.Ghost) })
    }
    edit?.let { target ->
        AlertDialog(onDismissRequest = { edit = null },
            title = { Text("修改文档显示内容") },
            text = { OutlinedTextField(value = editValue, onValueChange = { editValue = it },
                modifier = Modifier.fillMaxWidth(), maxLines = 5) },
            confirmButton = { SyButton("保存修改", onClick = {
                when (target) {
                    is NativeDocumentEdit.TextNode -> onEditText(target.id, editValue)
                    is NativeDocumentEdit.TableCell -> onEditTableCell(target.id, target.row, target.column, editValue)
                    is NativeDocumentEdit.ChartPoint -> editValue.toDoubleOrNull()
                        ?.let { onEditChart(target.id, target.index, it) }
                }
                edit = null
            }) },
            dismissButton = { SyButton("取消", onClick = { edit = null },
                variant = SyButtonVariant.Ghost) })
    }
}

private sealed interface NativeDocumentEdit {
    data class TextNode(val id: String) : NativeDocumentEdit
    data class TableCell(val id: String, val row: Int, val column: Int) : NativeDocumentEdit
    data class ChartPoint(val id: String, val index: Int) : NativeDocumentEdit
}

@Composable
private fun NativeV6DocumentNode(
    node: UiDocumentNode,
    item: UiDocumentSnapshot,
    fields: MutableMap<String, String>,
    enabled: Boolean,
    onEdit: (NativeDocumentEdit, String) -> Unit,
    onAction: (String) -> Unit,
) {
    val colors = LocalSyColors.current
    when (node) {
        is UiDocumentNode.Text -> {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(node.text, modifier = Modifier.weight(1f), color = colors.textSecondary)
                if (enabled) SyButton("修改", onClick = {
                    onEdit(NativeDocumentEdit.TextNode(node.id), node.text)
                }, size = SyButtonSize.Small, variant = SyButtonVariant.Ghost)
            }
        }
        is UiDocumentNode.BoundText -> {
            val value = when (val state = item.bindings[node.binding]) {
                is UiDocumentBoundValue.Text -> state.value
                is UiDocumentBoundValue.Count -> state.value.toString()
                is UiDocumentBoundValue.Phase -> state.value.name
                else -> "暂不可用"
            }
            Text("${node.label}：$value", color = colors.textSecondary)
        }
        is UiDocumentNode.Field -> OutlinedTextField(
            value = fields[node.id] ?: node.initial,
            onValueChange = { fields[node.id] = it },
            label = { Text(node.label) }, modifier = Modifier.fillMaxWidth(),
            maxLines = if (node.multiline) 5 else 1, enabled = enabled,
        )
        is UiDocumentNode.Select -> {
            Text(node.label, color = colors.textSecondary)
            node.options.forEach { option ->
                SyButton((if (fields[node.id] == option) "✓ " else "") + option,
                    onClick = { fields[node.id] = option },
                    size = SyButtonSize.Small, enabled = enabled,
                    variant = SyButtonVariant.Ghost)
            }
        }
        is UiDocumentNode.Switch -> Row {
            Text(node.label, modifier = Modifier.weight(1f), color = colors.textSecondary)
            Switch(checked = (fields[node.id] ?: node.initial.toString()) == "true",
                onCheckedChange = { fields[node.id] = it.toString() }, enabled = enabled)
        }
        is UiDocumentNode.Button -> SyButton(node.label, onClick = { onAction(node.actionId) },
            enabled = enabled && item.actions[node.actionId]?.enabled == true,
            variant = SyButtonVariant.Outline, modifier = Modifier.fillMaxWidth())
        is UiDocumentNode.Column -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            node.children.forEach { child -> NativeV6DocumentNode(child, item, fields, enabled, onEdit, onAction) }
        }
        is UiDocumentNode.Row -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            node.children.forEach { child -> NativeV6DocumentNode(child, item, fields, enabled, onEdit, onAction) }
        }
        is UiDocumentNode.Disclosure -> {
            var expanded by remember(node.id) { mutableStateOf(false) }
            SyButton(node.title + if (expanded) " ▲" else " ▼",
                onClick = { expanded = !expanded }, variant = SyButtonVariant.Ghost)
            if (expanded) node.children.forEach {
                NativeV6DocumentNode(it, item, fields, enabled, onEdit, onAction)
            }
        }
        is UiDocumentNode.Table -> {
            Text(node.headers.joinToString("  |  "), color = colors.textSecondary)
            node.rows.forEachIndexed { row, cells ->
                cells.forEachIndexed { column, cell ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("${node.headers[column]}：$cell",
                            modifier = Modifier.weight(1f), color = colors.textSecondary)
                        if (enabled) SyButton("改", onClick = {
                            onEdit(NativeDocumentEdit.TableCell(node.id, row, column), cell)
                        }, size = SyButtonSize.Small, variant = SyButtonVariant.Ghost)
                    }
                }
            }
        }
        is UiDocumentNode.BarChart -> {
            Text("${node.title} (${node.unit})", color = colors.textSecondary)
            node.points.forEachIndexed { index, point ->
                Row {
                    Text("${point.label}：${point.value}", modifier = Modifier.weight(1f),
                        color = colors.textSecondary)
                    if (enabled) SyButton("改", onClick = {
                        onEdit(NativeDocumentEdit.ChartPoint(node.id, index), point.value.toString())
                    }, size = SyButtonSize.Small, variant = SyButtonVariant.Ghost)
                }
            }
        }
        is UiDocumentNode.LineChart -> {
            Text("${node.title} (${node.unit})", color = colors.textSecondary)
            node.points.forEachIndexed { index, point ->
                Row {
                    Text("${point.label}：${point.value}", modifier = Modifier.weight(1f),
                        color = colors.textSecondary)
                    if (enabled) SyButton("改", onClick = {
                        onEdit(NativeDocumentEdit.ChartPoint(node.id, index), point.value.toString())
                    }, size = SyButtonSize.Small, variant = SyButtonVariant.Ghost)
                }
            }
        }
        is UiDocumentNode.Media -> Text("${node.label} · ${node.kind}（会话媒体）",
            color = colors.textSecondary)
    }
}

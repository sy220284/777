package com.labteto.dshmobile.ui.screens.local

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import com.labteto.dshmobile.ui.shenyan.SyDialog
import com.labteto.dshmobile.ui.shenyan.SyButton
import com.labteto.dshmobile.ui.shenyan.SyButtonVariant
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.uicontract.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Real V6 conversation cards. Rendered nodes are presentation only; every command passes
 * through the existing document Owner for revision, session, approval and input validation.
 */
@Composable
internal fun NativeV6DocumentMessage(
    messageId: String,
    documents: List<UiDocumentSnapshot>,
    controller: NativeV6Documents,
) {
    val scope = rememberCoroutineScope()
    var creating by remember(messageId) { mutableStateOf(false) }
    var generating by remember(messageId) { mutableStateOf(false) }
    var prompt by remember(messageId) { mutableStateOf("把这条回复整理成可交互的文档") }
    var feedback by remember(messageId) { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        documents.forEach { snapshot ->
            NativeV6DocumentCard(snapshot, controller, onFeedback = { feedback = it })
        }
        if (documents.isEmpty()) {
            TextButton(onClick = { creating = true }, enabled = !generating) {
                Text(if (generating) "正在生成交互文档…" else "生成交互文档")
            }
        }
        feedback?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
    if (creating) {
        SyDialog(title = "生成交互文档", onDismiss = { creating = false }) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("将额外请求一次模型；确认后才会执行。")
                    OutlinedTextField(value = prompt, onValueChange = { prompt = it },
                        label = { Text("文档要求") }, modifier = Modifier.fillMaxWidth())
                }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = { creating = false }) { Text("取消") }
        TextButton(enabled = prompt.isNotBlank() && !generating, onClick = {
                    creating = false
                    generating = true
                    scope.launch {
                        try {
                            feedback = nativeV6DocumentReceiptMessage(controller.generate(messageId, prompt))
                        } finally { generating = false }
                    }
                }) { Text("确认生成") }
    }
}
    }
}

@Composable
private fun NativeV6DocumentCard(
    snapshot: UiDocumentSnapshot,
    controller: NativeV6Documents,
    onFeedback: (String) -> Unit,
) {
    val doc = snapshot.document
    val scope = rememberCoroutineScope()
    val fields = remember(doc.documentId, doc.revision) {
        mutableStateMapOf<String, String>().apply { putAll(doc.initialFields()) }
    }
    var confirmation by remember(doc.documentId, doc.revision) { mutableStateOf<String?>(null) }
    var deleting by remember(doc.documentId, doc.revision) { mutableStateOf(false) }
    var confirmDelete by remember(doc.documentId, doc.revision) { mutableStateOf(false) }
    fun perform(actionId: String, confirmed: Boolean) {
        val values = fields.toMap()
        scope.launch {
            val receipt = withContext(Dispatchers.IO) { controller.submit(snapshot, actionId, values, confirmed) }
            onFeedback(nativeV6DocumentReceiptMessage(receipt))
        }
    }
    Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(doc.title, style = MaterialTheme.typography.titleMedium)
            if (snapshot.recoveryNotice != null) {
                Text(doc.fallbackText, style = MaterialTheme.typography.bodyMedium)
                Text("该文档仅提供历史内容，不能执行操作。")
            } else {
                doc.nodes.forEach { node ->
                    NativeV6DocumentNode(node, snapshot.bindings, fields) { actionId ->
                        val action = doc.actions.firstOrNull { it.id == actionId }
                        if (snapshot.actions[actionId]?.available != true) {
                            onFeedback("当前会话中的操作不可用")
                        } else if (action is UiDocumentAction.AnswerApproval ||
                            action is UiDocumentAction.SendUserInput && action.submit) {
                            confirmation = actionId
                        } else {
                            perform(actionId, false)
                        }
                    }
                }
            }
            if (snapshot.recoveryNotice == null && !doc.documentId.startsWith("work-live:")) {
                SyButton(
                    "删除文档", onClick = { confirmDelete = true },
                    enabled = !deleting, variant = SyButtonVariant.Ghost,
                )
            }
        }
    }
    confirmation?.let { actionId ->
        SyDialog(title = "确认文档操作", onDismiss = { confirmation = null }) {
    Text("该操作将提交当前填写内容，或回答待处理的审批。是否继续？")
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = { confirmation = null }) { Text("取消") }
        TextButton(onClick = {
                confirmation = null
                perform(actionId, true)
            }) { Text("确认") }
    }
}
    }
    if (confirmDelete) {
        SyDialog(title = "删除交互文档？", onDismiss = { confirmDelete = false }) {
            Text("仅删除这份文档，不会删除来源会话消息。")
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                SyButton("取消", onClick = { confirmDelete = false },
                    variant = SyButtonVariant.Ghost)
                SyButton("确认删除", onClick = {
                    confirmDelete = false
                    deleting = true
                    scope.launch {
                        try {
                            val receipt = withContext(Dispatchers.IO) { controller.delete(snapshot) }
                            onFeedback(nativeV6DocumentReceiptMessage(receipt))
                        } finally { deleting = false }
                    }
                }, variant = SyButtonVariant.Danger)
            }
        }
    }

}

@Composable
private fun NativeV6DocumentNode(
    node: UiDocumentNode,
    bindings: Map<UiDocumentBinding, UiDocumentBoundValue>,
    fields: MutableMap<String, String>,
    onAction: (String) -> Unit,
) {
    when (node) {
        is UiDocumentNode.Text -> Text(node.text, style = MaterialTheme.typography.bodyMedium)
        is UiDocumentNode.Media -> Text("${node.label}（${node.kind.name.lowercase()} 素材）")
        is UiDocumentNode.Table -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                node.headers.forEach { Text(it, modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium) }
            }
            node.rows.forEach { cells ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    node.headers.indices.forEach { i ->
                        Text(cells.getOrElse(i) { "" }, modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        is UiDocumentNode.BarChart -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(node.title, style = MaterialTheme.typography.titleSmall)
            val peak = node.points.maxOfOrNull { kotlin.math.abs(it.value) }?.coerceAtLeast(1.0) ?: 1.0
            node.points.forEach { point ->
                Text("${point.label}：${point.value} ${node.unit}", style = MaterialTheme.typography.bodySmall)
                androidx.compose.material3.LinearProgressIndicator(
                    progress = { (kotlin.math.abs(point.value) / peak).toFloat() },
                    modifier = Modifier.fillMaxWidth())
            }
        }
        is UiDocumentNode.LineChart -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(node.title, style = MaterialTheme.typography.titleSmall)
            val values = node.points.map { it.value }
            if (values.size > 1) {
                val min = values.minOrNull() ?: 0.0
                val max = values.maxOrNull() ?: 1.0
                val range = (max - min).coerceAtLeast(1.0)
                val color = MaterialTheme.colorScheme.primary
                Canvas(Modifier.fillMaxWidth().height(90.dp)) {
                    for (i in 0 until values.lastIndex) {
                        val a = Offset(size.width * i / values.lastIndex,
                            size.height * (1 - ((values[i] - min) / range).toFloat()))
                        val b = Offset(size.width * (i + 1) / values.lastIndex,
                            size.height * (1 - ((values[i + 1] - min) / range).toFloat()))
                        drawLine(color, a, b, strokeWidth = 3.dp.toPx())
                    }
                }
            }
            Text(node.points.joinToString("  ·  ") { "${it.label}: ${it.value} ${node.unit}" },
                style = MaterialTheme.typography.bodySmall)
        }
        is UiDocumentNode.BoundText -> {
            val label = when (val value = bindings[node.binding]) {
                is UiDocumentBoundValue.Text -> value.value
                is UiDocumentBoundValue.Count -> value.value.toString()
                is UiDocumentBoundValue.Phase -> value.value.name
                else -> "暂无实时数据"
            }
            Text("${node.label}：$label")
        }
        is UiDocumentNode.Column -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            node.children.forEach { NativeV6DocumentNode(it, bindings, fields, onAction) }
        }
        is UiDocumentNode.Row -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            node.children.forEach { child ->
                Column(Modifier.weight(1f)) { NativeV6DocumentNode(child, bindings, fields, onAction) }
            }
        }
        is UiDocumentNode.Field -> OutlinedTextField(
            value = fields[node.id].orEmpty(), onValueChange = { fields[node.id] = it },
            label = { Text(node.label) }, singleLine = !node.multiline,
            modifier = Modifier.fillMaxWidth())
        is UiDocumentNode.Select -> Column {
            Text(node.label, style = MaterialTheme.typography.labelMedium)
            node.options.forEach { option ->
                Row { Checkbox(checked = fields[node.id] == option,
                    onCheckedChange = { if (it) fields[node.id] = option })
                    Text(option, modifier = Modifier.padding(top = 12.dp)) }
            }
        }
        is UiDocumentNode.Switch -> Row {
            Checkbox(checked = fields[node.id].toBoolean(), onCheckedChange = { fields[node.id] = it.toString() })
            Text(node.label, modifier = Modifier.padding(top = 12.dp))
        }
        is UiDocumentNode.Button -> TextButton(onClick = { onAction(node.actionId) }) { Text(node.label) }
        is UiDocumentNode.Disclosure -> {
            var expanded by remember(node.id) { mutableStateOf(false) }
            Column {
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "收起 · ${node.title}" else "展开 · ${node.title}")
                }
                if (expanded) node.children.forEach { NativeV6DocumentNode(it, bindings, fields, onAction) }
            }
        }
    }
}

internal fun nativeV6DocumentReceiptMessage(receipt: UiDocumentReceipt): String = when (receipt) {
    is UiDocumentReceipt.Published -> "文档已生成"
    UiDocumentReceipt.Deleted -> "文档已删除"
    UiDocumentReceipt.DraftPrepared -> "已写入 V6 输入框，请检查后发送"
    UiDocumentReceipt.NavigationRequested -> "已切换页面"
    UiDocumentReceipt.AdditionalRequestConsentRequired -> "需要确认额外的模型请求"
    is UiDocumentReceipt.InvalidFields -> "请检查输入：${receipt.fieldIds.joinToString()}"
    is UiDocumentReceipt.Fallback -> "无法显示文档：${receipt.reason}"
    is UiDocumentReceipt.Rejected -> "操作未执行：${receipt.reason}"
    is UiDocumentReceipt.OwnerReceipt -> when (receipt.receipt) {
        UiCommandReceipt.Accepted, UiCommandReceipt.Queued -> "操作已受理"
        else -> "操作结果：${receipt.receipt}"
    }
    is UiDocumentReceipt.Failed -> "操作失败：${receipt.message.orEmpty()}"
}

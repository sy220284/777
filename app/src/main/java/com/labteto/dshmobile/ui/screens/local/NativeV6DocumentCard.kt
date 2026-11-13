package com.labteto.dshmobile.ui.screens.local

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import com.labteto.dshmobile.ui.shenyan.SyDialog
import com.labteto.dshmobile.ui.shenyan.SyResponsiveSheet
import com.labteto.dshmobile.ui.shenyan.SyButtonSize
import androidx.compose.ui.platform.testTag
import com.labteto.dshmobile.ui.shenyan.SyButton
import com.labteto.dshmobile.ui.shenyan.SyButtonVariant
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The reply shows a bounded, read-only preview. Inputs, selections and authoritative
 * bindings are intentionally excluded; real interactions remain in the guarded sheet.
 */
internal fun localReplyDocumentPreview(doc: UiDocument): String? {
    val pending = ArrayDeque<UiDocumentNode>()
    doc.nodes.forEach(pending::addLast)
    var inspected = 0
    var containsInputs = false
    while (pending.isNotEmpty() && inspected++ < 128) {
        when (val node = pending.removeFirst()) {
            is UiDocumentNode.Text -> node.text.trim().takeIf(String::isNotBlank)
                ?.let { return it.take(220) }
            is UiDocumentNode.Table ->
                return "表格 · ${node.rows.size} 行 · ${node.headers.size} 列"
            is UiDocumentNode.BarChart ->
                return "${node.title.take(90)} · ${node.points.size} 项"
            is UiDocumentNode.LineChart ->
                return "${node.title.take(90)} · ${node.points.size} 项"
            is UiDocumentNode.Media -> return node.label.take(160)
            is UiDocumentNode.Column -> node.children.asReversed().forEach(pending::addFirst)
            is UiDocumentNode.Row -> node.children.asReversed().forEach(pending::addFirst)
            is UiDocumentNode.Disclosure -> node.children.asReversed().forEach(pending::addFirst)
            is UiDocumentNode.Field, is UiDocumentNode.Select, is UiDocumentNode.Switch,
            is UiDocumentNode.Slider, is UiDocumentNode.Calculated, is UiDocumentNode.Button ->
                containsInputs = true
            is UiDocumentNode.BoundText -> Unit
        }
    }
    return "交互表单 · 请展开填写".takeIf { containsInputs }
}

/** The message timeline shows only a small entry; documents open on demand. */
@Composable
internal fun NativeV6DocumentModalHost(
    messageId: String,
    documents: List<UiDocumentSnapshot>,
    dismissEnabled: Boolean = true,
    content: @Composable (UiDocumentSnapshot, () -> Unit) -> Unit,
) {
    var open by remember(messageId) { mutableStateOf(false) }
    var selectedId by remember(messageId) { mutableStateOf<String?>(null) }
    if (documents.isEmpty()) return

    val selected = documents.firstOrNull { it.document.documentId == selectedId }
    val latest = documents.last()
    val colors = com.shenyan.designsystem.foundation.LocalSyColors.current
    val summary = if (latest.recoveryNotice != null)
        "历史内容，仅支持查看"
    else localReplyDocumentPreview(latest.document)
    // A reply-owned document reads as content, not an unrelated modal-launch button.
    Column(
        modifier = Modifier.fillMaxWidth()
            .heightIn(min = 48.dp)
            .testTag("reply_document_open:${messageId}")
            .clickable(enabled = dismissEnabled) {
                selectedId = latest.document.documentId
                open = true
            }
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            androidx.compose.material3.Icon(
                com.labteto.dshmobile.ui.components.FeatherIcons.FileText,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = colors.textSecondary,
            )
            Text(latest.document.title,
                style = com.labteto.dshmobile.ui.theme.DsType.small13Strong,
                color = colors.text,
                modifier = Modifier.weight(1f),
                maxLines = 2,
            )
            androidx.compose.material3.Icon(
                com.labteto.dshmobile.ui.components.FeatherIcons.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = colors.textSecondary,
            )
        }
        summary?.let { text ->
            Text(text, style = com.labteto.dshmobile.ui.theme.DsType.small13,
                color = colors.textSecondary,
                modifier = Modifier.padding(start = 26.dp),
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
        if (documents.size > 1) {
            Text("关联文档 ${documents.size} 份",
                style = com.labteto.dshmobile.ui.theme.DsType.caption11,
                color = colors.textTertiary, modifier = Modifier.padding(start = 26.dp))
        }
    }
    if (open && selected != null) {
        val close = { open = false }
        SyResponsiveSheet(
            title = selected.document.title,
            onDismiss = close,
            scrollable = true,
            seamlessBackground = false,
            dismissEnabled = dismissEnabled,
            trailing = {
                SyButton("关闭", onClick = close, enabled = dismissEnabled,
                    size = SyButtonSize.Small, variant = SyButtonVariant.Ghost)
            },
        ) {
            if (documents.size > 1) {
                Text("选择要查看的文档", style = MaterialTheme.typography.labelMedium)
                documents.forEach { snapshot ->
                    SyButton(
                        text = snapshot.document.title,
                        onClick = { selectedId = snapshot.document.documentId },
                        enabled = dismissEnabled,
                        size = SyButtonSize.Small,
                        variant = if (snapshot.document.documentId == selected.document.documentId)
                            SyButtonVariant.Primary else SyButtonVariant.Ghost,
                    )
                }
            }
            content(selected, close)
        }
    }
}

@Composable
internal fun NativeV6DocumentMessage(
    messageId: String,
    documents: List<UiDocumentSnapshot>,
    controller: NativeV6Documents,
    readOnly: Boolean = false,
    onPreparedDraft: () -> Unit,
) {
    var feedback by remember(messageId) { mutableStateOf<String?>(null) }
    var busy by remember(messageId) { mutableStateOf(false) }
    NativeV6DocumentModalHost(messageId, documents, dismissEnabled = !busy) { snapshot, close ->
        feedback?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        NativeV6DocumentCard(snapshot, controller, readOnly,
            onFeedback = { feedback = it },
            onPreparedDraft = { close(); onPreparedDraft() },
            onDeleted = close,
            onBusyChange = { busy = it },
        )
    }
}

@Composable
private fun NativeV6DocumentCard(
    snapshot: UiDocumentSnapshot,
    controller: NativeV6Documents,
    readOnly: Boolean,
    onFeedback: (String) -> Unit,
    onPreparedDraft: () -> Unit,
    onDeleted: () -> Unit,
    onBusyChange: (Boolean) -> Unit,
) {
    val doc = snapshot.document
    val scope = rememberCoroutineScope()
    val formKey = remember(doc.source.sessionId, doc.documentId, doc.revision) {
        UiDocumentFormKey(doc.source.sessionId, doc.documentId, doc.revision)
    }
    val savedFields by controller.forms.values.collectAsStateWithLifecycle()
    val fields = doc.initialFields() + savedFields[formKey].orEmpty()
    DisposableEffect(formKey, readOnly) {
        if (!readOnly) controller.forms.bindRetention(formKey, doc.fieldRetention())
        onDispose { if (!readOnly) controller.forms.releaseRetention(formKey) }
    }
    var confirmation by remember(doc.documentId, doc.revision) { mutableStateOf<String?>(null) }
    var deleting by remember(doc.documentId, doc.revision) { mutableStateOf(false) }
    var submitting by remember(doc.documentId, doc.revision) { mutableStateOf(false) }
    var submitted by remember(doc.documentId, doc.revision) { mutableStateOf(false) }
    var confirmDelete by remember(doc.documentId, doc.revision) { mutableStateOf(false) }
    fun perform(actionId: String, confirmed: Boolean) {
        if (submitting || submitted) return
        submitting = true
        onBusyChange(true)
        val values = fields
        scope.launch {
            try {
                val receipt = withContext(Dispatchers.IO) {
                    controller.submit(snapshot, actionId, values, confirmed)
                }
                if (nativeV6SubmittedToOwner(receipt) &&
                    doc.actions.any { it.id == actionId && it is UiDocumentAction.SendUserInput && it.submit }) {
                    submitted = true
                }
                onFeedback(nativeV6DocumentReceiptMessage(receipt))
                if (receipt == UiDocumentReceipt.DraftPrepared) onPreparedDraft()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                onFeedback("表单提交失败：${failure.message.orEmpty().ifBlank { failure.javaClass.simpleName }}")
            } finally {
                submitting = false
                onBusyChange(false)
            }
        }
    }
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (submitting || deleting) Text("正在处理，请稍候…", style = MaterialTheme.typography.bodySmall)
            if (snapshot.recoveryNotice != null) {
                Text(doc.fallbackText, style = MaterialTheme.typography.bodyMedium)
                Text("该文档仅提供历史内容，不能执行操作。")
            } else {
                if (readOnly) Text("历史文档只读，操作已禁用。")
                doc.nodes.forEach { node ->
                    NativeV6DocumentNode(
                        node, snapshot.bindings, fields,
                        onFieldChange = { id, value -> if (!readOnly && !submitting && !submitted) controller.forms.update(formKey, id, value) },
                        readOnly = readOnly || submitting || submitted, media = controller.media, sessionId = doc.source.sessionId,
                        actions = doc.actions,
                    ) { actionId ->
                        val action = doc.actions.firstOrNull { it.id == actionId }
                        if (submitted) {
                            onFeedback("该表单已提交，等待会话处理")
                        } else if (snapshot.actions[actionId]?.available != true) {
                            onFeedback("当前会话中的操作不可用：${snapshot.actions[actionId]?.reason ?: UiCommandRejection.STALE_IDENTITY}")
                        } else if (action is UiDocumentAction.AnswerApproval ||
                            action is UiDocumentAction.SendUserInput && action.submit) {
                            confirmation = actionId
                        } else {
                            perform(actionId, false)
                        }
                    }
                }
            }
            if (!readOnly && snapshot.recoveryNotice == null && !doc.documentId.startsWith("work-live:")) {
                SyButton(
                    "删除文档", onClick = { confirmDelete = true },
                    enabled = !deleting, variant = SyButtonVariant.Ghost,
                )
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
            }, enabled = !submitting && !submitted) { Text(if (submitting) "正在提交…" else "确认") }
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
                    onBusyChange(true)
                    scope.launch {
                        try {
                            val receipt = withContext(Dispatchers.IO) { controller.delete(snapshot) }
                            if (receipt == UiDocumentReceipt.Deleted) {
                                controller.forms.forgetDocument(formKey)
                                onDeleted()
                            } else onFeedback(nativeV6DocumentReceiptMessage(receipt))
                        } finally { deleting = false; onBusyChange(false) }
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
    fields: Map<String, String>,
    onFieldChange: (String, String) -> Unit,
    readOnly: Boolean,
    media: MediaUiPort,
    sessionId: String,
    actions: List<UiDocumentAction>,
    onAction: (String) -> Unit,
) {
    when (node) {
        is UiDocumentNode.Text -> Text(node.text, style = MaterialTheme.typography.bodyMedium)
        is UiDocumentNode.Media -> NativeV6DocumentMedia(node, sessionId, media)
        is UiDocumentNode.Table -> Column(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // Each column needs a real minimum width inside horizontal scrolling. Weighted
            // children otherwise squeeze technical receipt tables into illegible mobile cells.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                node.headers.forEach { header ->
                    Text(header, modifier = Modifier.width(144.dp),
                        style = MaterialTheme.typography.labelMedium)
                }
            }
            node.rows.forEach { cells ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    node.headers.indices.forEach { i ->
                        Text(cells.getOrElse(i) { "" }, modifier = Modifier.width(144.dp),
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
            node.children.forEach { NativeV6DocumentNode(it, bindings, fields, onFieldChange, readOnly, media, sessionId, actions, onAction) }
        }
        is UiDocumentNode.Row -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            node.children.forEach { child ->
                Column(Modifier.weight(1f)) { NativeV6DocumentNode(child, bindings, fields, onFieldChange, readOnly, media, sessionId, actions, onAction) }
            }
        }
        is UiDocumentNode.Field -> OutlinedTextField(
            value = fields[node.id].orEmpty(), onValueChange = { onFieldChange(node.id, it) },
            label = { Text(node.label) }, singleLine = !node.multiline, enabled = !readOnly,
            modifier = Modifier.fillMaxWidth())
        is UiDocumentNode.Select -> NativeV6CompactSelect(
            node = node, fields = fields, enabled = !readOnly, onFieldChange = onFieldChange,
        )
        is UiDocumentNode.Switch -> Row {
            Checkbox(checked = fields[node.id].toBoolean(), onCheckedChange = { onFieldChange(node.id, it.toString()) }, enabled = !readOnly)
            Text(node.label, modifier = Modifier.padding(top = 12.dp))
        }
        is UiDocumentNode.Slider -> Column {
            val value = fields[node.id]?.toDoubleOrNull()?.coerceIn(node.minimum, node.maximum) ?: node.initial
            Text("${node.label}：${value.toBigDecimal().stripTrailingZeros().toPlainString()}")
            Slider(value = value.toFloat(),
                onValueChange = { update ->
                    val steps = kotlin.math.round((update.toDouble() - node.minimum) / node.step)
                    val snapped = (node.minimum + steps * node.step).coerceIn(node.minimum, node.maximum)
                    onFieldChange(node.id, snapped.toString())
                },
                valueRange = node.minimum.toFloat()..node.maximum.toFloat(),
                steps = kotlin.math.round((node.maximum - node.minimum) / node.step).toInt() - 1,
                enabled = !readOnly)
        }
        is UiDocumentNode.Calculated -> Column {
            Text(node.label, style = MaterialTheme.typography.labelMedium)
            Text(calculateUiDocumentValue(node, fields) ?: "请输入有效数字",
                style = MaterialTheme.typography.titleMedium)
        }
        is UiDocumentNode.Button -> TextButton(onClick = { onAction(node.actionId) }, enabled = !readOnly) {
            Text(nativeV6ActionLabel(node.label, actions.firstOrNull { it.id == node.actionId }))
        }
        is UiDocumentNode.Disclosure -> {
            var expanded by remember(node.id) { mutableStateOf(false) }
            Column {
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "收起 · ${node.title}" else "展开 · ${node.title}")
                }
                if (expanded) node.children.forEach { NativeV6DocumentNode(it, bindings, fields, onFieldChange, readOnly, media, sessionId, actions, onAction) }
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
    is UiDocumentReceipt.OwnerReceipt -> when (val outcome = receipt.receipt) {
        UiCommandReceipt.Accepted -> "表单已提交，等待会话处理"
        UiCommandReceipt.Queued -> "表单已加入工作队列，稍后处理"
        is UiCommandReceipt.Rejected -> when (outcome.reason) {
            UiCommandRejection.STALE_IDENTITY, UiCommandRejection.SESSION_TRANSITION ->
                "表单所属会话已失效或正在切换，请重新打开当前会话"
            UiCommandRejection.QUEUE_FULL -> "当前工作队列已满，提交未成功，请稍后重试"
            UiCommandRejection.UNCONFIGURED -> "模型尚未配置，提交未成功"
            else -> "表单提交未成功：${outcome.reason}"
        }
        is UiCommandReceipt.Failed -> "表单提交失败：${outcome.message}"
        UiCommandReceipt.NotAdmitted -> "当前会话拒绝了这次表单提交"
        else -> "操作未确认：$outcome"
    }
    is UiDocumentReceipt.Failed -> "操作失败：${receipt.message.orEmpty()}"
}

package com.labteto.dshmobile.uipack.zhaoxi.scenes

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.labteto.dshmobile.uicontract.*
import com.labteto.dshmobile.uipack.zhaoxi.components.*
import com.labteto.dshmobile.uipack.zhaoxi.theme.ZhaoxiTheme
import com.labteto.dshmobile.uipack.zhaoxi.theme.ZhaoxiMotion
import com.labteto.dshmobile.uipack.zhaoxi.theme.ZhaoxiDuration
import com.labteto.dshmobile.uihost.UiOwnedMediaPlayback
import com.labteto.dshmobile.uihost.UiSceneRenderScope
import kotlinx.coroutines.launch

/** UI-only context captured from the CURRENT Chat/Work owner for identity-safe commands. */
data class ZhaoxiDocumentContext(
    val identity: UiSessionIdentity,
    val workMode: Boolean,
    val messageId: String,
)

/** Interactive document rendering retains only ephemeral form values. Owner remains authoritative. */
@Composable
fun ZhaoxiUiDocumentScene(
    port: UiDocumentUiPort,
    scope: UiSceneRenderScope,
    context: () -> ZhaoxiDocumentContext?,
    knownScenes: Set<UiSceneId>,
    pendingApprovalId: () -> String?,
    media: MediaUiPort? = null,
    forms: UiDocumentFormUiPort? = null,
) {
    val snap by port.snapshots.collectAsState(initial = port.snapshot())
    var selecting by remember(snap.sessionId) { mutableStateOf(false) }
    var selectedDocuments by remember(snap.sessionId) { mutableStateOf(emptyMap<String, UiDocument>()) }
    var pendingBatch by remember(snap.sessionId) { mutableStateOf<List<DeleteUiDocumentCommand>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var batchFeedback by remember(snap.sessionId) { mutableStateOf<String?>(null) }
    val jobs = rememberCoroutineScope()
    ZxPage("文档", onBack = { scope.onBack() }) {
        Spacer(Modifier.height(8.dp))
        snap.error?.let { ZxBanner("生成失败 · $it", ZxTone.Err) }
        if (snap.generating) {
            ZxProgressBar(progress = null)
            Spacer(Modifier.height(8.dp))
        }
        if (snap.documents.isEmpty() && !snap.generating) ZxEmpty("还没有文档", "文档由模型或工具生成")
        batchFeedback?.let { ZxFooterNote(it) }
        ZxChip(if (selecting) "退出多选" else "多选管理文档", selecting) {
            selecting = !selecting
            selectedDocuments = emptyMap()
        }
        if (selecting && selectedDocuments.isNotEmpty()) {
            ZxFooterNote("已选 ${selectedDocuments.size} 项；单次批量删除支持 2–32 项。")
            ZxButton("删除所选文档", {
                val id = context()?.identity
                if (id == null) batchFeedback = "会话已失效，请返回原会话"
                else pendingBatch = selectedDocuments.values.map { doc ->
                    DeleteUiDocumentCommand(id, doc.documentId, doc.revision, doc.source)
                }
            }, enabled = !busy && selectedDocuments.size <= 32, variant = ZxButtonVariant.Danger)
        }
        pendingBatch?.let { commands ->
            ZxDialog("删除 ${commands.size} 个文档？", "仅删除明确选中的文档显示记录，原会话消息保留。", "确认删除", {
                pendingBatch = null
                busy = true
                jobs.launch {
                    try {
                        val result = if (commands.size == 1) port.delete(commands.single()) else port.deleteBatch(commands)
                        batchFeedback = result.zxDocumentFeedback()
                        if (result == UiDocumentReceipt.Deleted) {
                            commands.forEach { forms?.forgetDocument(UiDocumentFormKey(it.source.sessionId, it.documentId, it.revision)) }
                            selectedDocuments = emptyMap()
                            selecting = false
                        }
                    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (failure: Exception) { batchFeedback = failure.message ?: "删除失败，已选文档仍保留" }
                    finally { busy = false }
                }
            }, { pendingBatch = null })
        }
        snap.documents.forEach { item ->
            val document = item.document
            val readOnlyProgress = document.source.workMode && document.documentId == "work-live:${document.source.messageId}"
            if (selecting && !readOnlyProgress) ZxChip(
                if (document.documentId in selectedDocuments) "已选文档" else "选择此文档",
                document.documentId in selectedDocuments,
            ) {
                selectedDocuments = if (document.documentId in selectedDocuments) selectedDocuments - document.documentId
                    else selectedDocuments + (document.documentId to document)
            }
            ZxDocumentCard(
                document = document,
                bindings = item.bindings,
                knownScenes = knownScenes,
                pendingApprovalId = pendingApprovalId(),
                media = media,
                forms = forms,
                onAction = { actionId, fields, confirmed ->
                    // Commands are admitted again by the original Feature Owner.
                    val live = context()
                    if (live == null || live.identity.sessionId != document.source.sessionId)
                        UiDocumentReceipt.Rejected(UiCommandRejection.STALE_IDENTITY)
                    else port.submit(live.identity, UiDocumentActionRequest(
                        documentId = document.documentId,
                        revision = document.revision,
                        source = document.source,
                        actionId = actionId,
                        fields = fields,
                        confirmed = confirmed,
                    ))
                },
            )
            if (!readOnlyProgress && !selecting)
                ZhaoxiDocumentDisplayEditor(port, document, context, forms)
            Spacer(Modifier.height(10.dp))
        }
    }
}

/** Explicit model request and strictly local generation are separate actions. */
@Composable
fun ZhaoxiUiDocumentGenerateScene(
    port: UiDocumentUiPort,
    scope: UiSceneRenderScope,
    context: () -> ZhaoxiDocumentContext?,
) {
    val co = rememberCoroutineScope()
    val snapshot by port.snapshots.collectAsState(initial = port.snapshot())
    var busy by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }
    fun generate(local: Boolean) {
        val live = context()
        if (live == null) { feedback = "请先进入包含消息的会话"; return }
        if (busy) return
        busy = true
        co.launch {
            try {
                val result = if (local) port.generateLocal(GenerateLocalUiDocumentCommand(
                    live.identity, live.workMode, live.messageId, UiLocalDocumentTemplate.STATUS))
                else port.generate(GenerateUiDocumentCommand(live.identity, live.workMode,
                    live.messageId, "请根据当前会话生成结构化交互文档", true))
                feedback = result.zxDocumentFeedback()
                if (result is UiDocumentReceipt.Published) scope.onNavigate(UiSceneId("conversation.ui_document"))
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (failure: Exception) { feedback = failure.message ?: "生成失败，请检查会话与模型状态" }
            finally { busy = false }
        }
    }
    ZxPage("生成文档", onBack = { scope.onBack() }) {
        Spacer(Modifier.height(8.dp))
        Text("从当前对话生成交互文档", fontSize = 14.sp, color = ZhaoxiTheme.colors.ink)
        Spacer(Modifier.height(10.dp))
        feedback?.let { ZxFooterNote(it) }
        snapshot.error?.let { ZxBanner(it, ZxTone.Err) }
        ZxButton("确认生成（调用模型）", { generate(false) }, Modifier.fillMaxWidth(),
            enabled = !busy && !snapshot.generating, loading = busy || snapshot.generating)
        ZxButton("本地生成（离线模板）", { generate(true) }, Modifier.fillMaxWidth(),
            enabled = !busy && !snapshot.generating, variant = ZxButtonVariant.Outline)

    }
}

@Composable
fun ZxDocumentCard(
    document: UiDocument,
    bindings: Map<UiDocumentBinding, UiDocumentBoundValue> = emptyMap(),
    knownScenes: Set<UiSceneId>,
    pendingApprovalId: String?,
    onAction: (String, Map<String, String>, Boolean) -> UiDocumentReceipt,
    media: MediaUiPort? = null,
    forms: UiDocumentFormUiPort? = null,
) {
    var actionFeedback by remember(document.documentId, document.source) { mutableStateOf<String?>(null) }
    var pendingSend by remember(document.documentId, document.revision, document.source) {
        mutableStateOf<Pair<String, Map<String, String>>?>(null)
    }
    fun dispatch(actionId: String, fields: Map<String, String>, confirmed: Boolean = false) {
        val action = document.actions.firstOrNull { it.id == actionId }
        if (!confirmed && action is UiDocumentAction.SendUserInput && action.submit) pendingSend = actionId to fields
        else actionFeedback = onAction(actionId, fields, confirmed).zxDocumentFeedback()
    }
    val validation = remember(document, knownScenes, pendingApprovalId) {
        document.validate(UiDocumentBudget(512, 16, 262_144), knownScenes, pendingApprovalId)
    }
    if (!validation.valid) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(document.title.ifBlank { "文档" }, color = ZhaoxiTheme.colors.ink)
            ZxBanner("文档无法完整显示，以下为原始文本", ZxTone.Warn)
            Text(document.fallbackText.take(400), color = ZhaoxiTheme.colors.ink2)
        }
        return
    }
    val defaults = remember(document) { document.initialFields() }
    val formKey = remember(document.source, document.documentId, document.revision) {
        UiDocumentFormKey(document.source.sessionId, document.documentId, document.revision)
    }
    val retention = remember(document) { document.fieldRetention() }
    var bindingReady by remember(forms, formKey, retention) { mutableStateOf(false) }
    androidx.compose.runtime.DisposableEffect(forms, formKey, retention) {
        forms?.bindRetention(formKey, retention)
        bindingReady = true
        onDispose { forms?.releaseRetention(formKey) }
    }
    val stored by (forms?.values ?: remember { kotlinx.coroutines.flow.MutableStateFlow(emptyMap<UiDocumentFormKey, Map<String, String>>()) }).collectAsState()
    val local = remember(formKey) { mutableStateMapOf<String, String>() }
    val fields = defaults + if (forms == null) local else if (bindingReady) stored[formKey].orEmpty() else emptyMap()
    fun changeField(id: String, value: String) {
        if (forms == null) local[id] = value
        else {
            forms.update(formKey, id, value)
            if (forms.values.value[formKey]?.get(id) != value)
                actionFeedback = "本次输入未保存，内容超过表单支持范围；请缩短后重试"
        }
    }
    val isWorkProgress = document.documentId == "work-live:${document.source.messageId}" &&
        document.source.workMode
    Column(Modifier.fillMaxWidth().padding(12.dp)) {
        Text(if (isWorkProgress) "执行状态" else document.title.ifBlank { "文档" },
            fontSize = 14.sp, color = ZhaoxiTheme.colors.ink)
        pendingSend?.let { (actionId, values) ->
            ZxDialog("发送文档内容？", "将按当前表单提交到会话，可能启动模型或工作执行。", "确认发送", {
                pendingSend = null
                dispatch(actionId, values, confirmed = true)
            }, { pendingSend = null })
        }
        actionFeedback?.let { ZxFooterNote(it) }
            if (isWorkProgress) {
                val status = document.nodes.filterIsInstance<UiDocumentNode.Text>()
                    .firstOrNull { it.id == "work_turn_status" }?.text
                if (!status.isNullOrBlank()) Text(status, fontSize = 13.sp, color = ZhaoxiTheme.colors.ink2)
                val rows = document.nodes.filterIsInstance<UiDocumentNode.Table>()
                    .firstOrNull { it.id == "work_events" }?.rows.orEmpty()
                Text("工具记录 ${rows.size} 项 · 已保存", fontSize = 11.sp, color = ZhaoxiTheme.colors.ink2)
                var detailsExpanded by remember(document.source.sessionId, document.documentId) {
                    mutableStateOf(false)
                }
                ZxRow("执行详情", if (detailsExpanded) "收起" else "展开",
                    onClick = { detailsExpanded = !detailsExpanded })
                AnimatedVisibility(
                    visible = detailsExpanded,
                    enter = fadeIn(animationSpec = ZhaoxiMotion.spec<Float>(ZhaoxiDuration.FAST)),
                    exit = fadeOut(animationSpec = ZhaoxiMotion.spec<Float>(ZhaoxiDuration.FAST)),
                ) {
                    Column {
                        document.nodes.filterNot {
                            it.id == "work_turn_status" || it.id == "work_scope"
                        }.forEach { node ->
                            ZxDocumentNodeView(node, bindings, fields, ::changeField,
                                { actionId -> dispatch(actionId, fields.toMap()) },
                                media, document.source.sessionId)
                        }
                    }
                }
            } else document.nodes.forEach { node ->
                ZxDocumentNodeView(node, bindings, fields, ::changeField, { actionId -> dispatch(actionId, fields.toMap()) }, media, document.source.sessionId)
            }
    }
}

@Composable
private fun ZxDocumentNodeView(
    node: UiDocumentNode,
    bindings: Map<UiDocumentBinding, UiDocumentBoundValue>,
    fields: Map<String, String>,
    onFieldChange: (String, String) -> Unit,
    onAction: (String) -> Unit,
    media: MediaUiPort?,
    sessionId: String,
) {
    when (node) {
        is UiDocumentNode.Text -> Text(node.text, fontSize = 13.sp, color = ZhaoxiTheme.colors.ink)
        is UiDocumentNode.BoundText -> {
            val value = when (val b = bindings[node.binding]) {
                is UiDocumentBoundValue.Text -> b.value
                is UiDocumentBoundValue.Count -> b.value.toString()
                is UiDocumentBoundValue.Phase -> b.value.name
                else -> "—"
            }
            ZxRow(node.label, value)
        }
        is UiDocumentNode.Field -> ZxField(fields[node.id].orEmpty(), { onFieldChange(node.id, it) },
            Modifier.fillMaxWidth(), placeholder = node.label)
        is UiDocumentNode.Switch -> ZxRow(node.label, "") {
            ZxSwitch(fields[node.id].toBoolean(), { onFieldChange(node.id, it.toString()) })
        }
        is UiDocumentNode.Select -> Column {
            ZxRow(node.label, fields[node.id].orEmpty())
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                node.options.forEach { option ->
                    ZxChip(option, fields[node.id] == option, onClick = { onFieldChange(node.id, option) })
                }
            }
        }
        is UiDocumentNode.Button -> ZxChip(node.label, false, onClick = { onAction(node.actionId) })
        is UiDocumentNode.Disclosure -> {
            var expanded by remember(node.id) { mutableStateOf(false) }
            Column {
                ZxRow(node.title, if (expanded) "收起" else "展开",
                    onClick = { expanded = !expanded })
                if (expanded) node.children.forEach {
                    ZxDocumentNodeView(it, bindings, fields, onFieldChange, onAction, media, sessionId)
                }
            }
        }
        is UiDocumentNode.Table -> ZxDataTable(node.headers, node.rows)
        is UiDocumentNode.BarChart -> {
            val maximum = node.points.maxOfOrNull { it.value }?.takeIf { it > 0.0 } ?: 1.0
            Column {
                Text(node.title, color = ZhaoxiTheme.colors.ink)
                node.points.forEach { point ->
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(point.label, fontSize = 11.sp, color = ZhaoxiTheme.colors.ink2,
                            modifier = Modifier.width(86.dp))
                        Box(Modifier.weight(1f).height(10.dp).background(ZhaoxiTheme.colors.bg2)) {
                            Box(Modifier.fillMaxWidth((point.value / maximum).toFloat().coerceIn(0f, 1f))
                                .height(10.dp).background(ZhaoxiTheme.colors.accent))
                        }
                        Text(point.value.toString() + " " + node.unit, fontSize = 11.sp,
                            color = ZhaoxiTheme.colors.ink2)
                    }
                }
            }
        }
        is UiDocumentNode.LineChart -> {
            val points = node.points
            val maxValue = points.maxOfOrNull { it.value }?.takeIf { it > 0.0 } ?: 1.0
            val accent = ZhaoxiTheme.colors.accent
            Column {
                Text(node.title, color = ZhaoxiTheme.colors.ink)
                Canvas(Modifier.fillMaxWidth().height(120.dp)) {
                    val last = (points.size - 1).coerceAtLeast(1)
                    points.zipWithNext().forEachIndexed { i, (a, b) ->
                        drawLine(accent,
                            Offset(size.width * i / last, size.height * (1 - a.value / maxValue).toFloat()),
                            Offset(size.width * (i + 1) / last, size.height * (1 - b.value / maxValue).toFloat()),
                            strokeWidth = 3.dp.toPx())
                    }
                }
                Text(points.joinToString(" · ") { "${it.label}: ${it.value}" }, fontSize = 11.sp,
                    color = ZhaoxiTheme.colors.ink2)
            }
        }
        is UiDocumentNode.Media -> {
            val owned = media?.assetForSession(node.assetId, sessionId)
            Column {
                Text(node.label, color = ZhaoxiTheme.colors.ink)
                if (owned == null) {
                    Text("媒体结果暂不可用", fontSize = 12.sp, color = ZhaoxiTheme.colors.ink2)
                } else {
                    owned.textOutput?.let { Text(it, color = ZhaoxiTheme.colors.ink) }
                    owned.localPath?.let { path ->
                        val kind = when (node.kind) {
                            UiDocumentMediaKind.IMAGE -> UiAttachmentMediaKind.IMAGE
                            UiDocumentMediaKind.AUDIO -> UiAttachmentMediaKind.AUDIO
                            UiDocumentMediaKind.VIDEO -> UiAttachmentMediaKind.VIDEO
                        }
                        UiOwnedMediaPlayback(path, kind, Modifier.fillMaxWidth().heightIn(max = 360.dp))
                    }
                }
            }
        }
        is UiDocumentNode.Column -> Column {
            node.children.forEach { ZxDocumentNodeView(it, bindings, fields, onFieldChange, onAction, media, sessionId) }
        }
        is UiDocumentNode.Row -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            node.children.forEach { ZxDocumentNodeView(it, bindings, fields, onFieldChange, onAction, media, sessionId) }
        }
    }
}

package com.labteto.dshmobile.uipack.zhaoxi.scenes

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
) {
    val snap by port.snapshots.collectAsState(initial = port.snapshot())
    ZxPage("文档", onBack = { scope.onBack() }) {
        Spacer(Modifier.height(8.dp))
        snap.error?.let { ZxBanner("生成失败 · $it", ZxTone.Err) }
        if (snap.generating) {
            ZxProgressBar(progress = null)
            Spacer(Modifier.height(8.dp))
        }
        if (snap.documents.isEmpty() && !snap.generating) ZxEmpty("还没有文档", "文档由模型或工具生成")
        snap.documents.forEach { item ->
            val document = item.document
            ZxDocumentCard(
                document = document,
                bindings = item.bindings,
                knownScenes = knownScenes,
                pendingApprovalId = pendingApprovalId(),
                onAction = { actionId, fields ->
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
                    ))
                },
            )
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
    ZxPage("生成文档", onBack = { scope.onBack() }) {
        Spacer(Modifier.height(8.dp))
        Text("从当前对话生成交互文档", fontSize = 14.sp, color = ZhaoxiTheme.colors.ink)
        Spacer(Modifier.height(10.dp))
        ZxButton("确认生成（调用模型）", {
            val live = context()
            if (live != null) co.launch {
                port.generate(GenerateUiDocumentCommand(
                    identity = live.identity,
                    workMode = live.workMode,
                    messageId = live.messageId,
                    prompt = "请根据当前会话生成结构化交互文档",
                    acknowledgedAdditionalModelRequest = true,
                ))
            }
        }, Modifier.fillMaxWidth())
        ZxButton("本地生成（离线模板）", {
            val live = context()
            if (live != null) co.launch {
                port.generateLocal(GenerateLocalUiDocumentCommand(
                    identity = live.identity,
                    workMode = live.workMode,
                    messageId = live.messageId,
                    template = UiLocalDocumentTemplate.STATUS,
                ))
            }
        }, Modifier.fillMaxWidth(), variant = ZxButtonVariant.Outline)
    }
}

/** Form text lives in transient Compose presentation state, not a parallel business state store. */
private fun documentDefaults(nodes: List<UiDocumentNode>): Map<String, String> {
    val fields = mutableMapOf<String, String>()
    fun visit(node: UiDocumentNode) {
        when (node) {
            is UiDocumentNode.Field -> fields[node.id] = node.initial
            is UiDocumentNode.Select -> fields[node.id] = node.initial
            is UiDocumentNode.Switch -> fields[node.id] = node.initial.toString()
            is UiDocumentNode.Column -> node.children.forEach(::visit)
            is UiDocumentNode.Row -> node.children.forEach(::visit)
            is UiDocumentNode.Disclosure -> node.children.forEach(::visit)
            else -> Unit
        }
    }
    nodes.forEach(::visit)
    return fields
}

@Composable
fun ZxDocumentCard(
    document: UiDocument,
    bindings: Map<UiDocumentBinding, UiDocumentBoundValue> = emptyMap(),
    knownScenes: Set<UiSceneId>,
    pendingApprovalId: String?,
    onAction: (String, Map<String, String>) -> UiDocumentReceipt,
) {
    val defaults = remember(document) { documentDefaults(document.nodes) }
    // A new revision must never erase the user's unfinished form entry.
    val fields = remember(document.source.sessionId, document.source.messageId, document.documentId) {
        mutableStateMapOf<String, String>().apply { putAll(defaults) }
    }
    LaunchedEffect(document.revision) { defaults.forEach { (id, initial) -> if (id !in fields) fields[id] = initial } }
    val validation = remember(document, knownScenes, pendingApprovalId) {
        document.validate(UiDocumentBudget(512, 16, 262_144), knownScenes, pendingApprovalId)
    }
    Column(Modifier.fillMaxWidth().padding(12.dp)) {
        Text(document.title.ifBlank { "文档" }, fontSize = 15.sp, color = ZhaoxiTheme.colors.ink)
        if (!validation.valid) {
            ZxBanner("文档超出安全显示限制 · 已降级", ZxTone.Warn)
            Text(document.fallbackText.take(400), fontSize = 12.sp, color = ZhaoxiTheme.colors.ink2)
        } else {
            val isWorkProgress = document.documentId.startsWith("work-live:") &&
                document.source.workMode
            if (isWorkProgress) {
                val status = document.nodes.filterIsInstance<UiDocumentNode.Text>()
                    .firstOrNull { it.id == "work_turn_status" }?.text
                if (!status.isNullOrBlank()) Text(status, fontSize = 13.sp, color = ZhaoxiTheme.colors.ink2)
                val rows = document.nodes.filterIsInstance<UiDocumentNode.Table>()
                    .firstOrNull { it.id == "work_events" }?.rows.orEmpty()
                Text("执行记录 ${rows.size} 项 · 持续更新", fontSize = 11.sp, color = ZhaoxiTheme.colors.ink2)
                var detailsExpanded by remember(document.source.sessionId, document.documentId) {
                    mutableStateOf(false)
                }
                ZxRow("执行详情", if (detailsExpanded) "收起" else "展开") {
                    detailsExpanded = !detailsExpanded
                }
                if (detailsExpanded) document.nodes.filterNot {
                    it.id == "work_turn_status" || it.id == "work_scope"
                }.forEach { node ->
                    ZxDocumentNodeView(node, bindings, fields) { actionId -> onAction(actionId, fields.toMap()) }
                }
            } else document.nodes.forEach { node ->
                ZxDocumentNodeView(node, bindings, fields) { actionId -> onAction(actionId, fields.toMap()) }
            }
        }
    }
}

@Composable
private fun ZxDocumentNodeView(
    node: UiDocumentNode,
    bindings: Map<UiDocumentBinding, UiDocumentBoundValue>,
    fields: MutableMap<String, String>,
    onAction: (String) -> Unit,
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
        is UiDocumentNode.Field -> ZxField(fields[node.id].orEmpty(), { fields[node.id] = it },
            Modifier.fillMaxWidth(), placeholder = node.label)
        is UiDocumentNode.Switch -> ZxRow(node.label, "") {
            ZxSwitch(fields[node.id].toBoolean(), { fields[node.id] = it.toString() })
        }
        is UiDocumentNode.Select -> Column {
            ZxRow(node.label, fields[node.id].orEmpty())
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                node.options.forEach { option ->
                    ZxChip(option, fields[node.id] == option, onClick = { fields[node.id] = option })
                }
            }
        }
        is UiDocumentNode.Button -> ZxChip(node.label, false, onClick = { onAction(node.actionId) })
        is UiDocumentNode.Disclosure -> {
            var expanded by remember(node.id) { mutableStateOf(false) }
            Column {
                ZxRow(node.title, if (expanded) "收起" else "展开") { expanded = !expanded }
                if (expanded) node.children.forEach {
                    ZxDocumentNodeView(it, bindings, fields, onAction)
                }
            }
        }
        is UiDocumentNode.Table -> {
            var showAll by remember(node.id) { mutableStateOf(false) }
            Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                Text(node.headers.joinToString("  │  "), fontSize = 12.sp, color = ZhaoxiTheme.colors.ink)
                (if (showAll) node.rows else node.rows.take(16)).forEach { row ->
                    Text(row.joinToString("  │  "), fontSize = 12.sp, color = ZhaoxiTheme.colors.ink2)
                }
                if (node.rows.size > 16) ZxRow(
                    if (showAll) "收起表格" else "查看全部 ${node.rows.size} 行", ""
                ) { showAll = !showAll }
            }
        }
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
        is UiDocumentNode.Media -> ZxRow("媒体", node.label.take(48))
        is UiDocumentNode.Column -> Column {
            node.children.forEach { ZxDocumentNodeView(it, bindings, fields, onAction) }
        }
        is UiDocumentNode.Row -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            node.children.forEach { ZxDocumentNodeView(it, bindings, fields, onAction) }
        }
    }
}

package com.labteto.dshmobile.uipack.zhaoxi.scenes

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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
    val defaults = remember(document.documentId, document.revision) { documentDefaults(document.nodes) }
    val fields = remember(document.documentId, document.revision) {
        mutableStateMapOf<String, String>().apply { putAll(defaults) }
    }
    val validation = remember(document, knownScenes, pendingApprovalId) {
        document.validate(UiDocumentBudget(256, 12, 100_000), knownScenes, pendingApprovalId)
    }
    Column(Modifier.fillMaxWidth().padding(12.dp)) {
        Text(document.title.ifBlank { "文档" }, fontSize = 15.sp, color = ZhaoxiTheme.colors.ink)
        if (!validation.valid) {
            ZxBanner("文档超出安全显示限制 · 已降级", ZxTone.Warn)
            Text(document.fallbackText.take(400), fontSize = 12.sp, color = ZhaoxiTheme.colors.ink2)
        } else document.nodes.forEach { node ->
            ZxDocumentNodeView(node, bindings, fields) { actionId -> onAction(actionId, fields.toMap()) }
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
        is UiDocumentNode.Disclosure -> Column {
            ZxRow(node.title, "展开")
            node.children.forEach { ZxDocumentNodeView(it, bindings, fields, onAction) }
        }
        is UiDocumentNode.Table -> Text(
            node.rows.joinToString("\n") { it.joinToString(" · ") },
            fontSize = 12.sp, color = ZhaoxiTheme.colors.ink2)
        is UiDocumentNode.BarChart, is UiDocumentNode.LineChart ->
            ZxRow("图表", "由宿主图表层渲染 · 朝夕呈现数值表")
        is UiDocumentNode.Media -> ZxRow("媒体", node.label.take(48))
        is UiDocumentNode.Column -> Column {
            node.children.forEach { ZxDocumentNodeView(it, bindings, fields, onAction) }
        }
        is UiDocumentNode.Row -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            node.children.forEach { ZxDocumentNodeView(it, bindings, fields, onAction) }
        }
    }
}

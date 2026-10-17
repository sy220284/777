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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.labteto.dshmobile.uicontract.UiDocument
import com.labteto.dshmobile.uicontract.UiDocumentAction
import com.labteto.dshmobile.uicontract.UiDocumentActionRequest
import com.labteto.dshmobile.uicontract.UiDocumentNode
import com.labteto.dshmobile.uicontract.UiDocumentUiPort
import com.labteto.dshmobile.uicontract.UiDocumentsSceneSnapshot
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxBanner
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxButton
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxButtonVariant
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxChip
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxEmpty
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxField
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxProgressBar
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxSwitch
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxTone
import com.labteto.dshmobile.uipack.zhaoxi.theme.ZhaoxiTheme
import com.labteto.dshmobile.uihost.UiSceneRenderScope
import kotlinx.coroutines.launch

/**
 * UiDocument 场景（E8.14 实装）。
 * 渲染前经 document.validate() 预算校验；节点按类型映射 Zx 组件；
 * 动作（SendUserInput/OpenKnownScene/AnswerApproval）只经 port.submit 回执。
 */

/** conversation.ui_document — 会话内文档列表与渲染。 */
@Composable
fun ZhaoxiUiDocumentScene(
    port: UiDocumentUiPort,
    scope: UiSceneRenderScope,
) {
    val co = rememberCoroutineScope()
    val snap by port.snapshots.collectAsState(
        initial = UiDocumentsSceneSnapshot(null, emptyList(), false, null))
    ZxPage("文档", onBack = { scope.onBack() }) {
        Spacer(Modifier.height(8.dp))
        snap.error?.let { ZxBanner("生成失败 · $it", ZxTone.Err) }
        if (snap.generating) {
            ZxProgressBar(progress = null)
            Spacer(Modifier.height(8.dp))
        }
        if (snap.documents.isEmpty() && !snap.generating) ZxEmpty("还没有文档", "文档由模型或工具生成")
        snap.documents.forEach { d ->
            ZxDocumentCard(
                document = d.document,
                onAction = { actionId ->
                    d.document.actions[actionId]?.let { action ->
                        co.launch { port.submit(d.document.documentId, UiDocumentActionRequest(actionId, action)) }
                    }
                },
            )
            Spacer(Modifier.height(10.dp))
        }
    }
}

/** conversation.ui_document_generate — 生成入口。 */
@Composable
fun ZhaoxiUiDocumentGenerateScene(
    port: UiDocumentUiPort,
    scope: UiSceneRenderScope,
) {
    val co = rememberCoroutineScope()
    ZxPage("生成文档", onBack = { scope.onBack() }) {
        Spacer(Modifier.height(8.dp))
        Text("从当前对话生成交互文档", fontSize = 14.sp, color = ZhaoxiTheme.colors.ink)
        Spacer(Modifier.height(10.dp))
        ZxButton("生成", { co.launch { port.generate() } }, Modifier.fillMaxWidth())
        ZxButton("本地生成（离线模板）", { co.launch { port.generateLocal() } },
            Modifier.fillMaxWidth(), variant = ZxButtonVariant.Outline)
    }
}

/** 单文档渲染：validate 前置 + 节点映射。 */
@Composable
fun ZxDocumentCard(
    document: UiDocument,
    onAction: (String) -> Unit,
) {
    val issues = remember(document) {
        runCatching { document.validate() }.getOrDefault(emptyList())
    }
    Column(
        Modifier.fillMaxWidth().padding(12.dp),
    ) {
        Text(document.title.ifBlank { "文档" }, fontSize = 15.sp, color = ZhaoxiTheme.colors.ink)
        if (issues.isNotEmpty()) {
            ZxBanner("文档超出预算 · 已降级", ZxTone.Warn)
            Text(document.fallbackText.take(400), fontSize = 12.sp, color = ZhaoxiTheme.colors.ink2)
        } else {
            document.nodes.forEach { node -> ZxDocumentNodeView(node, onAction) }
        }
    }
}

@Composable
private fun ZxDocumentNodeView(node: UiDocumentNode, onAction: (String) -> Unit) {
    when (node) {
        is UiDocumentNode.Text -> Text(node.text, fontSize = 13.sp, color = ZhaoxiTheme.colors.ink)
        is UiDocumentNode.BoundText -> Text(node.text, fontSize = 13.sp, color = ZhaoxiTheme.colors.ink2)
        is UiDocumentNode.Field -> ZxField(node.value, {}, Modifier.fillMaxWidth(), placeholder = node.label)
        is UiDocumentNode.Switch -> ZxRow(node.label, "") { ZxSwitch(node.checked, {}) }
        is UiDocumentNode.Select -> ZxRow(node.label, node.options.joinToString(" / "))
        is UiDocumentNode.Button -> ZxChip(node.label, false) { node.actionId?.let(onAction) }
        is UiDocumentNode.Disclosure -> ZxRow(node.title, "展开 ›")
        is UiDocumentNode.Table -> Text(
            node.rows.joinToString("\n") { it.joinToString(" · ") },
            fontSize = 12.sp, color = ZhaoxiTheme.colors.ink2)
        is UiDocumentNode.BarChart, is UiDocumentNode.LineChart ->
            ZxRow("图表", "由宿主图表层渲染 · 朝夕呈现数值表")
        is UiDocumentNode.Media -> ZxRow("媒体", node.toString().take(48))
        is UiDocumentNode.Column -> Column { node.children.forEach { ZxDocumentNodeView(it, onAction) } }
        is UiDocumentNode.Row -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            node.children.forEach { ZxDocumentNodeView(it, onAction) }
        }
    }
}

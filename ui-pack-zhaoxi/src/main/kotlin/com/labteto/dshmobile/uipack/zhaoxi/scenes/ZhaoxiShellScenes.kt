package com.labteto.dshmobile.uipack.zhaoxi.scenes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.labteto.dshmobile.uicontract.FilesUiPort
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.session.LocalConversationMode
import com.labteto.dshmobile.uicontract.UiCommandReceipt
import com.labteto.dshmobile.uicontract.SessionUiPort
import com.labteto.dshmobile.uicontract.SessionUiCommand
import com.labteto.dshmobile.uicontract.UiSessionsSnapshot
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxBanner
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxButton
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxButtonVariant
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxChip
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxEmpty
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxField
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxFooterNote
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxOnboardingFlow
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxOnboardingStep
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxSection
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxTone
import com.labteto.dshmobile.uipack.zhaoxi.theme.ZhaoxiTheme
import com.labteto.dshmobile.uicontract.UiSceneId
import com.labteto.dshmobile.uihost.UiSceneRenderScope

/**
 * 壳层与工作区场景（E8.14 实装）。
 * route.home/shell.new_session 用 SessionUiPort；route.workspace 用 FilesUiPort；
 * shell.onboarding/empty/error 为静态壳页。
 */

/** route.home / shell.new_session — 会话列表。 */
@Composable
fun ZhaoxiHomeScene(
    sessions: SessionUiPort?,
    scope: UiSceneRenderScope,
) {
    val snap by (sessions?.snapshots
        ?: kotlinx.coroutines.flow.flowOf(UiSessionsSnapshot(null, LocalUsageMode.CHAT, false, emptyList(), null)))
        .collectAsState(initial = UiSessionsSnapshot(null, LocalUsageMode.CHAT, false, emptyList(), null))
    ZxPage("朝夕", onBack = { scope.onBack() }) {
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ZxChip("＋ 新会话", false) {
                val receipt = sessions?.submit(SessionUiCommand.Create(snap.identity, LocalConversationMode.INDEPENDENT, LocalUsageMode.CHAT))
                // The Host opens the requested route after the Session owner activates it.
            }
            ZxChip("＋ 工作", false) {
                val receipt = sessions?.submit(SessionUiCommand.Create(snap.identity, LocalConversationMode.INDEPENDENT, LocalUsageMode.WORK))
                // Route after activation through the Host.
            }
            ZxChip("搜索", false) { scope.onNavigate(UiSceneId("shell.search")) }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ZxChip("聊天", false) { scope.onNavigate(UiSceneId("conversation.chat")) }
            ZxChip("人物", false) { scope.onNavigate(UiSceneId("route.persona_gallery")) }
            ZxChip("任务", false) { scope.onNavigate(UiSceneId("route.tasks")) }
            ZxChip("文件", false) { scope.onNavigate(UiSceneId("route.workspace")) }
            ZxChip("工具", false) { scope.onNavigate(UiSceneId("route.tools")) }
            ZxChip("设置", false) { scope.onNavigate(UiSceneId("route.settings")) }
        }
        Spacer(Modifier.height(8.dp))
        if (snap.sessions.isEmpty() && !snap.loading) ZxEmpty("还没有会话", "从一次新会话开始")
        snap.sessions.forEach { s ->
            ZxRow(s.title.take(40), s.summaryPreview.orEmpty().take(56),
                onClick = { sessions?.submit(SessionUiCommand.Open(s.id)) })
        }
    }
}

/** shell.search — 搜索。 */
@Composable
fun ZhaoxiSearchScene(sessions: SessionUiPort?, scope: UiSceneRenderScope) {
    var query by remember { mutableStateOf("") }
    val snapshot by (sessions?.snapshots
        ?: kotlinx.coroutines.flow.flowOf(UiSessionsSnapshot(null, LocalUsageMode.CHAT, false, emptyList(), null)))
        .collectAsState(initial = sessions?.snapshot() ?: UiSessionsSnapshot(
            null, LocalUsageMode.CHAT, false, emptyList(), null))
    val results = remember(query, snapshot.sessions) {
        val needle = query.trim()
        if (needle.isEmpty()) emptyList() else snapshot.sessions.filter { session ->
            session.title.contains(needle, ignoreCase = true) ||
                session.summaryPreview.orEmpty().contains(needle, ignoreCase = true) ||
                session.id.contains(needle, ignoreCase = true)
        }.take(60)
    }
    ZxPage("搜索", onBack = { scope.onBack() }) {
        Spacer(Modifier.height(8.dp))
        ZxField(query, { query = it }, Modifier.fillMaxWidth(),
            placeholder = "搜索会话标题及预览…")
        Spacer(Modifier.height(8.dp))
        if (query.isBlank()) ZxEmpty("输入关键词", "可搜索本地会话标题与最近消息摘要")
        else if (results.isEmpty()) ZxEmpty("没有匹配的会话", "试试其他关键词")
        results.forEach { item ->
            ZxRow(item.title.take(60), item.summaryPreview.orEmpty().take(80),
                onClick = {
                    sessions?.submit(SessionUiCommand.Open(item.id))
                })
        }
    }
}

/** shell.onboarding — 引导。 */
@Composable
fun ZhaoxiOnboardingScene(scope: UiSceneRenderScope) {
    var step by remember { mutableStateOf(0) }
    val steps = listOf(
        ZxOnboardingStep("01", "朝夕之间", "一个更安静的 Agent 界面"),
        ZxOnboardingStep("02", "人物图集", "创造、迁移、与人物相处"),
        ZxOnboardingStep("03", "工作模式", "计划、证据、团队协作"),
        ZxOnboardingStep("04", "准备好了", "从一次对话开始"),
    )
    ZxPage("欢迎使用", onBack = { scope.onBack() }) {
        Spacer(Modifier.height(24.dp))
        ZxOnboardingFlow(
            steps = steps, current = step,
            onNext = { step = (step + 1).coerceAtMost(steps.lastIndex) },
            onSkip = { scope.onBack() },
            onFinish = { scope.onBack() },
        )
    }
}

/** shell.empty — 空态演示。 */
@Composable
fun ZhaoxiEmptyDemoScene(scope: UiSceneRenderScope) {
    ZxPage("空态演示", onBack = { scope.onBack() }) {
        Spacer(Modifier.height(24.dp))
        ZxEmpty("这里什么都没有", "空态 = 一句结论 + 一句引导")
    }
}

/** shell.error — 错误态演示。 */
@Composable
fun ZhaoxiErrorDemoScene(scope: UiSceneRenderScope) {
    ZxPage("错误态演示", onBack = { scope.onBack() }) {
        Spacer(Modifier.height(8.dp))
        ZxBanner("渲染失败 · 示例错误", ZxTone.Err, action = "重试" to {})
        Spacer(Modifier.height(8.dp))
        ZxEmpty("内容不可用", "重试或返回上一页")
    }
}

/** route.workspace — 工作区文件。 */
@Composable
fun ZhaoxiWorkspaceScene(files: FilesUiPort?, scope: UiSceneRenderScope, onPreview: (String) -> Unit) {
    ZxPage("工作区", onBack = { scope.onBack() }) {
        Spacer(Modifier.height(8.dp))
        val list by produceState(initialValue = emptyList<com.labteto.dshmobile.local.tools.LocalWorkspaceFile>(), files) {
            value = runCatching { files?.workspaceFiles() }.getOrNull().orEmpty()
        }
        if (list.isEmpty()) ZxEmpty("工作区是空的", "产物与附件会出现在这里")
        list.forEach { f ->
            ZxRow(f.path.substringAfterLast('/').take(48), "${f.bytes} 字节 · ${f.path}".take(92),
                onClick = { onPreview(f.path) })
        }
    }
}

/** workspace.preview — 文件预览。 */
@Composable
fun ZhaoxiWorkspacePreviewScene(files: FilesUiPort?, path: String, scope: UiSceneRenderScope) {
    ZxPage("预览", onBack = { scope.onBack() }) {
        Spacer(Modifier.height(8.dp))
        val text by produceState(initialValue = "加载中…", files, path) {
            value = if (path.isBlank()) "请先从工作区选择文件" else
                runCatching { files?.previewWorkspaceFile(path)?.toString() }.getOrNull() ?: "预览不可用"
        }
        Text(text.take(2000), fontSize = 12.sp, color = ZhaoxiTheme.colors.ink)
        ZxFooterNote(path)
    }
}

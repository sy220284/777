package com.labteto.dshmobile.uipack.zhaoxi.scenes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxIcon
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxIconButton
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxModeSwitch
import kotlinx.coroutines.launch
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

/** route.home / shell.new_session — E8.17 navigation hierarchy: conversation entry + drawer. */
@Composable
fun ZhaoxiHomeScene(
    sessions: SessionUiPort?,
    scope: UiSceneRenderScope,
) {
    val snap by (sessions?.snapshots
        ?: kotlinx.coroutines.flow.flowOf(UiSessionsSnapshot(null, LocalUsageMode.CHAT, false, emptyList(), null)))
        .collectAsState(initial = UiSessionsSnapshot(null, LocalUsageMode.CHAT, false, emptyList(), null))
    var commandReceipt by remember { mutableStateOf<UiCommandReceipt?>(null) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val drawerScope = rememberCoroutineScope()
    val c = ZhaoxiTheme.colors
    val workMode = snap.usageMode == LocalUsageMode.WORK
    fun create(mode: LocalUsageMode) {
        commandReceipt = sessions?.submit(SessionUiCommand.Create(snap.identity, LocalConversationMode.INDEPENDENT, mode))
        drawerScope.launch { drawerState.close() }
    }
    fun openSession(id: String) {
        commandReceipt = sessions?.submit(SessionUiCommand.Open(id))
        drawerScope.launch { drawerState.close() }
    }
    fun navigate(target: String) {
        scope.onNavigate(UiSceneId(target))
        drawerScope.launch { drawerState.close() }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.width(296.dp),
                drawerContainerColor = c.side,
            ) {
                Column(Modifier.fillMaxHeight().padding(horizontal = 12.dp)) {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("朝夕", Modifier.weight(1f), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.ink)
                        ZxIconButton(
                            onClick = { drawerScope.launch { drawerState.close() } },
                            contentDescription = "关闭侧栏",
                        ) { ZxIcon("back") }
                    }
                    ZxHomeDrawerEntry("plus", "新会话", onClick = { create(LocalUsageMode.CHAT) })
                    ZxHomeDrawerEntry("tasks", "新工作", onClick = { create(LocalUsageMode.WORK) })
                    ZxHomeDrawerEntry("search", "搜索人物、会话、任务、文件", onClick = { navigate("shell.search") })
                    Spacer(Modifier.height(12.dp))
                    ZxModeSwitch(workMode, onChange = { selectedWork ->
                        commandReceipt = sessions?.submit(SessionUiCommand.SelectMode(if (selectedWork) LocalUsageMode.WORK else LocalUsageMode.CHAT))
                        drawerScope.launch { drawerState.close() }
                    })
                    Spacer(Modifier.height(14.dp))
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        item {
                            Text("快捷操作", fontSize = 11.sp, color = c.ink2, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                        }
                        if (workMode) {
                            item { ZxHomeDrawerEntry("files", "工作区文件", onClick = { navigate("route.workspace") }) }
                            item { ZxHomeDrawerEntry("tasks", "定时任务", onClick = { navigate("route.tasks") }) }
                            item { ZxHomeDrawerEntry("tools", "工具", onClick = { navigate("route.tools") }) }
                        } else {
                            item { ZxHomeDrawerEntry("people", "人物图集", onClick = { navigate("route.persona_gallery") }) }
                            item { ZxHomeDrawerEntry("diary", "日记", onClick = { navigate("route.diary") }) }
                            item { ZxHomeDrawerEntry("tasks", "聊天任务", onClick = { navigate("route.tasks") }) }
                        }
                        item {
                            Spacer(Modifier.height(10.dp))
                            Text("最近会话", fontSize = 11.sp, color = c.ink2, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                        }
                        if (snap.sessions.isEmpty()) {
                            item {
                                Text("尚无会话，点击上方「新会话」开始",
                                    fontSize = 12.sp, color = c.ink2, modifier = Modifier.padding(12.dp))
                            }
                        }
                        items(snap.sessions, key = { it.id }) { session ->
                            ZxHomeDrawerEntry(
                                "diary", session.title.take(40),
                                selected = snap.identity?.sessionId == session.id,
                                detail = session.summaryPreview.orEmpty().take(48),
                                onClick = { openSession(session.id) },
                            )
                        }
                    }
                    ZxHomeDrawerEntry("settings", "设置", onClick = { navigate("route.settings") })
                    Spacer(Modifier.height(12.dp))
                }
            }
        },
    ) {
        ZxPage(
            title = "朝夕",
            menu = true,
            onBack = { drawerScope.launch { drawerState.open() } },
            topRight = {
                ZxModeSwitch(workMode, onChange = { selectedWork ->
                    commandReceipt = sessions?.submit(SessionUiCommand.SelectMode(if (selectedWork) LocalUsageMode.WORK else LocalUsageMode.CHAT))
                })
            },
        ) {
            CommandFeedback(commandReceipt)
            Spacer(Modifier.height(28.dp))
            Text(if (workMode) "开始一项工作" else "开启一段对话",
                fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = c.ink)
            Spacer(Modifier.height(8.dp))
            Text(
                if (workMode) "描述目标，朝夕会带着上下文继续执行。" else "选择一段会话继续，或从这里开始新的聊天。",
                fontSize = 13.sp, color = c.ink2, lineHeight = 20.sp,
            )
            Spacer(Modifier.height(24.dp))
            ZxHomeAction(
                icon = if (workMode) "tasks" else "plus",
                title = if (workMode) "新工作" else "新会话",
                detail = if (workMode) "创建新的工作会话" else "开始新的聊天会话",
                prominent = true,
            ) { create(if (workMode) LocalUsageMode.WORK else LocalUsageMode.CHAT) }
            Spacer(Modifier.height(10.dp))
            ZxHomeAction(
                icon = if (workMode) "plus" else "tasks",
                title = if (workMode) "新会话" else "新工作",
                detail = if (workMode) "创建普通聊天会话" else "开始一项新任务",
                prominent = false,
            ) { create(if (workMode) LocalUsageMode.CHAT else LocalUsageMode.WORK) }
            Spacer(Modifier.height(30.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("最近会话", Modifier.weight(1f), fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold, color = c.ink)
                ZxIconButton(
                    onClick = { drawerScope.launch { drawerState.open() } },
                    contentDescription = "查看全部会话",
                ) { ZxIcon("menu") }
            }
            Spacer(Modifier.height(8.dp))
            if (snap.sessions.isEmpty() && !snap.loading) {
                ZxEmpty("还没有会话", "点击「新会话」开始")
            }
            snap.sessions.take(6).forEach { session ->
                ZxHomeDrawerEntry(
                    icon = "diary",
                    title = session.title.take(40),
                    detail = session.summaryPreview.orEmpty().take(56),
                    selected = snap.identity?.sessionId == session.id,
                    onClick = { openSession(session.id) },
                )
            }
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ZxHomeShortcut("people", "人物") { navigate("route.persona_gallery") }
                ZxHomeShortcut("files", "文件") { navigate("route.workspace") }
                ZxHomeShortcut("tools", "工具") { navigate("route.tools") }
                ZxHomeShortcut("settings", "设置") { navigate("route.settings") }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ZxHomeDrawerEntry(
    icon: String,
    title: String,
    detail: String = "",
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    val c = ZhaoxiTheme.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(if (selected) c.bg2 else Color.Transparent)
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ZxIcon(icon)
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = c.ink, maxLines = 1)
            if (detail.isNotBlank()) Text(detail, fontSize = 11.sp, color = c.ink2, maxLines = 1)
        }
        if (selected) ZxIcon("check")
    }
}

@Composable
private fun ZxHomeAction(
    icon: String,
    title: String,
    detail: String,
    prominent: Boolean,
    onClick: () -> Unit,
) {
    val c = ZhaoxiTheme.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
            .background(if (prominent) c.tint else c.glass)
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 76.dp).padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ZxIcon(icon)
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.ink)
            Spacer(Modifier.height(4.dp))
            Text(detail, fontSize = 12.sp, color = c.ink2)
        }
        ZxIcon("chevron-right")
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.ZxHomeShortcut(
    icon: String,
    title: String,
    onClick: () -> Unit,
) {
    val c = ZhaoxiTheme.colors
    Column(
        Modifier.weight(1f).clip(RoundedCornerShape(14.dp))
            .background(c.glass)
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 72.dp).padding(vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ZxIcon(icon)
        Text(title, fontSize = 11.sp, color = c.ink)
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

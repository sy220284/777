package com.labteto.dshmobile.uipack.zhaoxi.scenes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.interaction.MutableInteractionSource
import com.labteto.dshmobile.uipack.zhaoxi.components.zxPressSurface
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxIcon
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxIconButton
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxModeSwitch
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
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
import com.labteto.dshmobile.uihost.UiWorkspaceExportAction
import com.labteto.dshmobile.uihost.UiWorkspaceExportReceipt
import com.labteto.dshmobile.uihost.UiWorkspaceExportRequest
import com.labteto.dshmobile.uihost.UiWorkspaceBatchExportRequest
import com.labteto.dshmobile.uihost.rememberUiWorkspaceExporter
import com.labteto.dshmobile.uihost.rememberUiWorkspaceBatchExporter
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.session.LocalConversationMode
import com.labteto.dshmobile.uicontract.UiCommandReceipt
import com.labteto.dshmobile.uicontract.SessionUiPort
import com.labteto.dshmobile.uicontract.ConversationShellUiPort
import com.labteto.dshmobile.uicontract.ConversationShellUiCommand
import com.labteto.dshmobile.uicontract.UiSessionDeletionReceipt
import com.labteto.dshmobile.uicontract.SessionUiCommand
import com.labteto.dshmobile.uicontract.UiSessionsSnapshot
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxBanner
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxButton
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxButtonVariant
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxChip
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxEmpty
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxField
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxDialog
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

/** 置顶只影响呈现顺序，不改 Session Owner 的历史事实。 */
internal fun zxPinnedSessionsFirst(
    sessions: List<com.labteto.dshmobile.local.session.LocalSessionSummary>,
    pinned: Set<String>,
): List<com.labteto.dshmobile.local.session.LocalSessionSummary> =
    sessions.withIndex().sortedWith(
        compareByDescending<IndexedValue<com.labteto.dshmobile.local.session.LocalSessionSummary>> {
            it.value.id in pinned
        }.thenBy { it.index }
    ).map { it.value }

/** route.home / shell.new_session — 历史侧栏功能与当前 Owner 一一对应。 */
@Composable
fun ZhaoxiHomeScene(
    sessions: SessionUiPort?,
    scope: UiSceneRenderScope,
    conversationShell: ConversationShellUiPort? = null,
) {
    val snap by (sessions?.snapshots
        ?: kotlinx.coroutines.flow.flowOf(UiSessionsSnapshot(null, LocalUsageMode.CHAT, false, emptyList(), null)))
        .collectAsState(initial = UiSessionsSnapshot(null, LocalUsageMode.CHAT, false, emptyList(), null))
    var commandReceipt by remember { mutableStateOf<UiCommandReceipt?>(null) }
    var drawerQuery by rememberSaveable { mutableStateOf("") }
    var selectedSessionAction by remember { mutableStateOf<String?>(null) }
    var renameSessionId by remember { mutableStateOf<String?>(null) }
    var renameTitle by remember { mutableStateOf("") }
    var deleteSessionId by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf(false) }
    var managementFeedback by remember { mutableStateOf<String?>(null) }
    val pinnedIds by (conversationShell?.pinnedSessionIds
        ?: kotlinx.coroutines.flow.flowOf(emptySet<String>())).collectAsState(initial = emptySet())
    val renamedTitles by (conversationShell?.sessionTitleOverrides
        ?: kotlinx.coroutines.flow.flowOf(emptyMap<String, String>())).collectAsState(initial = emptyMap())
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val drawerScope = rememberCoroutineScope()
    val c = ZhaoxiTheme.colors
    val workMode = snap.usageMode == LocalUsageMode.WORK
    val modeSessions = snap.sessions.filter { it.usageMode == snap.usageMode }
    val visibleSessions = zxPinnedSessionsFirst(
        if (drawerQuery.isBlank()) modeSessions else modeSessions.filter { session ->
            renamedTitles.getOrDefault(session.id, session.title)
                .contains(drawerQuery.trim(), ignoreCase = true) ||
                session.summaryPreview.orEmpty().contains(drawerQuery.trim(), ignoreCase = true)
        }, pinnedIds,
    )
    fun create(mode: LocalUsageMode) {
        val receipt = sessions?.submit(SessionUiCommand.Create(
            snap.identity, LocalConversationMode.INDEPENDENT, mode,
        )) ?: UiCommandReceipt.NotAdmitted
        commandReceipt = receipt
        if (receipt.admitted()) drawerScope.launch { drawerState.close() }
    }
    fun openSession(id: String) {
        val receipt = sessions?.submit(SessionUiCommand.Open(id))
            ?: UiCommandReceipt.NotAdmitted
        commandReceipt = receipt
        if (receipt.admitted()) drawerScope.launch { drawerState.close() }
    }
    fun navigate(target: String) {
        scope.onNavigate(UiSceneId(target))
        drawerScope.launch { drawerState.close() }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.width(296.dp).then(
                    if (drawerState.targetValue == DrawerValue.Open) Modifier
                    else Modifier.clearAndSetSemantics { }
                ),
                drawerContainerColor = c.side,
            ) {
                Column(Modifier.fillMaxHeight().navigationBarsPadding().padding(horizontal = 12.dp)) {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("朝夕", Modifier.weight(1f), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.ink)
                        ZxIconButton(
                            onClick = { create(snap.usageMode) },
                            contentDescription = if (workMode) "新建工作" else "新建会话",
                        ) { ZxIcon("plus") }
                        ZxIconButton(
                            onClick = { navigate("shell.search") },
                            contentDescription = "全局搜索",
                        ) { ZxIcon("search") }
                    }
                    Spacer(Modifier.height(8.dp))
                    ZxField(
                        value = drawerQuery,
                        onValueChange = { drawerQuery = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = if (workMode) "筛选工作会话" else "筛选聊天会话",
                    )
                    CommandFeedback(commandReceipt)
                    managementFeedback?.let { ZxFooterNote(it) }
                    Spacer(Modifier.height(12.dp))
                    ZxModeSwitch(workMode, onChange = { selectedWork ->
                        val receipt = sessions?.submit(SessionUiCommand.SelectMode(
                            if (selectedWork) LocalUsageMode.WORK else LocalUsageMode.CHAT,
                            openConversation = false,
                        )) ?: UiCommandReceipt.NotAdmitted
                        commandReceipt = receipt
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
                            Text(if (workMode) "工作历史" else "聊天历史", fontSize = 11.sp, color = c.ink2, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                        }
                        if (visibleSessions.isEmpty()) {
                            item {
                                Text(
                                    if (drawerQuery.isBlank()) (if (workMode) "暂无工作会话，可点击顶部新建" else "暂无聊天会话，可点击顶部新建")
                                    else "没有匹配的会话",
                                    fontSize = 12.sp, color = c.ink2, modifier = Modifier.padding(12.dp),
                                )
                            }
                            if (drawerQuery.isNotBlank()) {
                                item {
                                    ZxButton("清除筛选", { drawerQuery = "" },
                                        modifier = Modifier.fillMaxWidth(),
                                        variant = ZxButtonVariant.Outline)
                                }
                            }
                        }
                        items(visibleSessions, key = { it.id }) { session ->
                            val displayedTitle = renamedTitles[session.id] ?: session.title
                            ZxHomeDrawerEntry(
                                "diary", displayedTitle.take(40),
                                selected = snap.identity?.sessionId == session.id,
                                detail = listOfNotNull(
                                    "已置顶".takeIf { session.id in pinnedIds },
                                    session.summaryPreview?.take(48)?.takeIf { it.isNotBlank() },
                                ).joinToString(" · "),
                                onClick = { openSession(session.id) },
                                onLongClick = if (conversationShell == null) null else {
                                    { selectedSessionAction = session.id }
                                },
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
                tag = if (workMode) "zhaoxi.home.create_work" else "zhaoxi.home.create_chat",
            ) { create(if (workMode) LocalUsageMode.WORK else LocalUsageMode.CHAT) }
            Spacer(Modifier.height(10.dp))
            ZxHomeAction(
                icon = if (workMode) "plus" else "tasks",
                title = if (workMode) "新会话" else "新工作",
                detail = if (workMode) "创建普通聊天会话" else "开始一项新任务",
                prominent = false,
                tag = if (workMode) "zhaoxi.home.create_chat" else "zhaoxi.home.create_work",
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
            if (modeSessions.isEmpty() && !snap.loading) {
                ZxEmpty("还没有会话", "点击「新会话」开始")
            }
            zxPinnedSessionsFirst(modeSessions, pinnedIds).take(6).forEach { session ->
                ZxHomeDrawerEntry(
                    icon = "diary",
                    title = (renamedTitles[session.id] ?: session.title).take(40),
                    detail = session.summaryPreview.orEmpty().take(56),
                    selected = snap.identity?.sessionId == session.id,
                    onClick = { openSession(session.id) },
                )
            }
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ZxHomeShortcut("people", "人物") { navigate("route.persona_gallery") }
                ZxHomeShortcut("files", "文件") { navigate("route.workspace") }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ZxHomeShortcut("tools", "工具") { navigate("route.tools") }
                ZxHomeShortcut("settings", "设置") { navigate("route.settings") }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    selectedSessionAction?.let { id ->
        val item = snap.sessions.firstOrNull { it.id == id }
        if (item != null) {
            Dialog(onDismissRequest = { selectedSessionAction = null }) {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
                        .background(c.card).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(renamedTitles[id] ?: item.title, fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold, color = c.ink,
                        maxLines = 2)
                    ZxButton(if (id in pinnedIds) "取消置顶" else "置顶会话", {
                        commandReceipt = conversationShell?.submit(
                            ConversationShellUiCommand.SetPinned(id, id !in pinnedIds),
                        ) ?: UiCommandReceipt.NotAdmitted
                        selectedSessionAction = null
                    }, Modifier.fillMaxWidth(), ZxButtonVariant.Outline)
                    ZxButton("重命名", {
                        renameTitle = renamedTitles[id] ?: item.title
                        renameSessionId = id
                        selectedSessionAction = null
                    }, Modifier.fillMaxWidth(), ZxButtonVariant.Outline)
                    ZxButton("删除会话", {
                        deleteSessionId = id
                        selectedSessionAction = null
                    }, Modifier.fillMaxWidth(), ZxButtonVariant.Danger)
                    ZxButton("关闭", { selectedSessionAction = null },
                        Modifier.fillMaxWidth(), ZxButtonVariant.Outline)
                }
            }
        }
    }
    renameSessionId?.let { id ->
        Dialog(onDismissRequest = { renameSessionId = null }) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
                    .background(c.card).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("重命名会话", color = c.ink, fontWeight = FontWeight.SemiBold)
                ZxField(renameTitle, { renameTitle = it }, Modifier.fillMaxWidth(),
                    placeholder = "会话标题（最多 80 字）")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ZxButton("取消", { renameSessionId = null }, Modifier.weight(1f),
                        ZxButtonVariant.Outline)
                    ZxButton("保存", {
                        commandReceipt = conversationShell?.submit(
                            ConversationShellUiCommand.Rename(id, renameTitle.trim()),
                        ) ?: UiCommandReceipt.NotAdmitted
                        if (commandReceipt?.admitted() == true) renameSessionId = null
                    }, Modifier.weight(1f), enabled = renameTitle.isNotBlank())
                }
            }
        }
    }
    deleteSessionId?.let { id ->
        val title = snap.sessions.firstOrNull { it.id == id }?.let {
            renamedTitles[id] ?: it.title
        }.orEmpty()
        ZxDialog("删除会话？", "“${title.take(64)}”及其会话历史将被删除，无法撤销。", "确认删除", {
            if (!deleting) {
                deleting = true
                drawerScope.launch {
                    try {
                        val result = sessions?.delete(setOf(id))
                        managementFeedback = when (result) {
                            is UiSessionDeletionReceipt.Deleted ->
                                if (id in result.sessionIds) "会话已删除" else "会话未删除，请刷新后再试"
                            is UiSessionDeletionReceipt.Failed ->
                                result.message ?: "删除失败，请重试"
                            null -> "无法删除会话：会话管理不可用"
                        }
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        managementFeedback = failure.message ?: "删除失败，请重试"
                    } finally {
                        deleting = false
                        deleteSessionId = null
                    }
                }
            }
        }, { if (!deleting) deleteSessionId = null })
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ZxHomeDrawerEntry(
    icon: String,
    title: String,
    detail: String = "",
    selected: Boolean = false,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val c = ZhaoxiTheme.colors
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier.fillMaxWidth().zxPressSurface(interaction).clip(RoundedCornerShape(12.dp))
            .background(if (selected) c.bg2 else Color.Transparent)
            .then(if (onLongClick != null) Modifier.combinedClickable(
                role = Role.Button,
                onClick = onClick,
                onLongClick = onLongClick,
                onLongClickLabel = "会话操作",
                interactionSource = interaction,
                indication = androidx.compose.foundation.LocalIndication.current,
            ) else Modifier.clickable(
                role = Role.Button, onClick = onClick,
                interactionSource = interaction,
                indication = androidx.compose.foundation.LocalIndication.current,
            ))
            .heightIn(min = 56.dp).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ZxIcon(icon)
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = c.ink, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            if (detail.isNotBlank()) Text(detail, fontSize = 12.sp, color = c.ink2, maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
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
    tag: String,
    onClick: () -> Unit,
) {
    val c = ZhaoxiTheme.colors
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier.fillMaxWidth().zxPressSurface(interaction).clip(RoundedCornerShape(20.dp))
            .background(if (prominent) c.tint else c.glass)
            .clickable(role = Role.Button, onClick = onClick,
                interactionSource = interaction,
                indication = androidx.compose.foundation.LocalIndication.current)
            .testTag(tag)
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
    val interaction = remember { MutableInteractionSource() }
    Column(
        Modifier.weight(1f).zxPressSurface(interaction).clip(RoundedCornerShape(14.dp))
            .background(c.glass)
            .clickable(role = Role.Button, onClick = onClick,
                interactionSource = interaction,
                indication = androidx.compose.foundation.LocalIndication.current)
            .heightIn(min = 72.dp).padding(vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ZxIcon(icon)
        Text(title, fontSize = 11.sp, color = c.ink)
    }
}

/** shell.search — 按 Owner 的只读投影跨会话、人物、任务与工作区搜索。 */
@Composable
fun ZhaoxiSearchScene(
    sessions: SessionUiPort?,
    gallery: com.labteto.dshmobile.uicontract.PersonaGalleryUiPort?,
    automation: com.labteto.dshmobile.uicontract.AutomationUiPort?,
    files: FilesUiPort?,
    scope: UiSceneRenderScope,
    onGalleryOpen: (String) -> Unit,
    onTaskOpen: (String) -> Unit,
    onFileOpen: (String) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var commandReceipt by remember { mutableStateOf<UiCommandReceipt?>(null) }
    val snapshot by (sessions?.snapshots
        ?: kotlinx.coroutines.flow.flowOf(UiSessionsSnapshot(null, LocalUsageMode.CHAT, false, emptyList(), null)))
        .collectAsState(initial = sessions?.snapshot() ?: UiSessionsSnapshot(
            null, LocalUsageMode.CHAT, false, emptyList(), null))
    val galleryRead by produceState<com.labteto.dshmobile.uicontract.UiPersonaGalleryReadReceipt?>(null, gallery) {
        value = gallery?.read()
    }
    val taskSnapshot by produceState(automation?.snapshot(), automation) {
        automation?.snapshots?.collect { value = it }
    }
    val fileRead by produceState<Result<List<com.labteto.dshmobile.local.tools.LocalWorkspaceFile>>?>(null, files) {
        value = files?.let { runCatching { it.workspaceFiles() } }
    }
    val needle = query.trim()
    val sessionResults = if (needle.isEmpty()) emptyList() else snapshot.sessions.filter {
        it.title.contains(needle, ignoreCase = true) ||
            it.summaryPreview.orEmpty().contains(needle, ignoreCase = true)
    }.take(30)
    val personaResults = if (needle.isEmpty()) emptyList() else
        (galleryRead as? com.labteto.dshmobile.uicontract.UiPersonaGalleryReadReceipt.Available)
            ?.entries.orEmpty().filter { entry ->
                entry.name.contains(needle, ignoreCase = true) ||
                    entry.stories.any { story ->
                        story.title.contains(needle, ignoreCase = true) ||
                            story.notes.contains(needle, ignoreCase = true)
                    }
            }.take(30)
    val taskResults = if (needle.isEmpty()) emptyList() else
        taskSnapshot?.tasks.orEmpty().filter { task ->
            task.prompt.contains(needle, ignoreCase = true) ||
                task.actorName.orEmpty().contains(needle, ignoreCase = true)
        }.take(30)
    val fileResults = if (needle.isEmpty()) emptyList() else
        fileRead?.getOrNull().orEmpty().filter { it.path.contains(needle, ignoreCase = true) }.take(30)
    val loading = gallery != null && galleryRead == null ||
        files != null && fileRead == null

    ZxPage("搜索", onBack = { scope.onBack() }) {
        Spacer(Modifier.height(8.dp))
        ZxField(query, { query = it }, Modifier.fillMaxWidth(),
            placeholder = "搜索会话、人物、任务和工作区文件")
        CommandFeedback(commandReceipt)
        Spacer(Modifier.height(8.dp))
        if (needle.isEmpty()) {
            ZxEmpty("搜索内容", "输入名称或关键词，在本机数据中查找")
        } else {
            if (sessionResults.isNotEmpty()) {
                ZxSection("会话")
                sessionResults.forEach { item ->
                    ZxRow(item.title.take(80), item.summaryPreview.orEmpty().take(120),
                        onClick = {
                            val receipt = sessions?.submit(SessionUiCommand.Open(item.id))
                                ?: UiCommandReceipt.NotAdmitted
                            commandReceipt = receipt
                            if (receipt == UiCommandReceipt.Accepted || receipt == UiCommandReceipt.Queued) {
                                scope.onBack()
                            }
                        })
                }
            }
            if (personaResults.isNotEmpty()) {
                ZxSection("人物")
                personaResults.forEach { item ->
                    ZxRow(item.name, item.stories.take(2).joinToString(" · ") { it.title },
                        onClick = { onGalleryOpen(item.id) })
                }
            }
            if (taskResults.isNotEmpty()) {
                ZxSection("定时任务")
                taskResults.forEach { item ->
                    ZxRow(item.prompt.take(80), item.actorName.orEmpty(),
                        onClick = { onTaskOpen(item.id) })
                }
            }
            if (fileResults.isNotEmpty()) {
                ZxSection("工作区文件")
                fileResults.forEach { item ->
                    ZxRow(item.path.substringAfterLast('/'), item.path,
                        onClick = { onFileOpen(item.path) })
                }
            }
            if (loading) {
                ZxSection("正在查找")
                Text("正在读取人物与文件…", fontSize = 12.sp,
                    color = ZhaoxiTheme.colors.ink2)
            }
            (galleryRead as? com.labteto.dshmobile.uicontract.UiPersonaGalleryReadReceipt.Failed)?.let {
                ZxBanner("人物数据读取失败：${it.message ?: "请稍后重试"}", ZxTone.Warn)
            }
            fileRead?.exceptionOrNull()?.let {
                ZxBanner("工作区文件读取失败，请返回工作区检查文件状态", ZxTone.Warn)
            }
            if (!loading && sessionResults.isEmpty() && personaResults.isEmpty() &&
                taskResults.isEmpty() && fileResults.isEmpty()) {
                ZxEmpty("没有找到匹配的内容", "可以换一个关键词重试")
            }
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
        ZxEmpty("暂无内容", "请返回上一页")
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

/** 列表使用可读单位，原始字节数仍由 Owner 保存。 */
internal fun zxHumanFileSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes 字节"
    var value = bytes.toDouble()
    var unit = 0
    val units = listOf("字节", "KB", "MB", "GB", "TB")
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024.0
        unit++
    }
    return String.format(java.util.Locale.CHINA, "%.1f %s", value, units[unit])
}

internal fun zxWorkspaceFileVisible(path: String, query: String, showInternal: Boolean): Boolean {
    val hidden = path.split('/').any { it.length > 1 && it.startsWith(".") }
    return (showInternal || !hidden) &&
        (query.isBlank() || path.contains(query.trim(), ignoreCase = true))
}

/** Only the real Host exporter may report that the user-selected destination was written. */
internal fun zxWorkspaceExportFeedback(receipt: UiWorkspaceExportReceipt): String? = when (receipt) {
    is UiWorkspaceExportReceipt.Saved -> "文件已保存（${zxHumanFileSize(receipt.bytes)}）"
    is UiWorkspaceExportReceipt.ExternalAppUnavailable -> "文件已保存，但没有可打开或分享的应用"
    UiWorkspaceExportReceipt.Cancelled -> null
    is UiWorkspaceExportReceipt.Failed -> "文件操作失败：${receipt.message ?: "请检查文件是否仍可访问"}"
}

/** route.workspace — 分离工作区文件和当前会话产物，均读取真实 FilesUiPort。 */
@Composable
fun ZhaoxiWorkspaceScene(
    files: FilesUiPort?,
    sessionId: String,
    scope: UiSceneRenderScope,
    onPreview: (String) -> Unit,
) {
    var selectedDomain by rememberSaveable { mutableStateOf(0) }
    var fileQuery by rememberSaveable { mutableStateOf("") }
    var showInternalFiles by rememberSaveable { mutableStateOf(false) }
    var refreshFiles by remember { mutableStateOf(0) }
    var selectionMode by rememberSaveable { mutableStateOf(false) }
    var selectedPaths by remember { mutableStateOf(emptySet<String>()) }
    var fileFeedback by remember { mutableStateOf<String?>(null) }
    fun clearSelection() { selectedPaths = emptySet(); selectionMode = false }
    val batchExporter = rememberUiWorkspaceBatchExporter(
        writeFile = { path, output -> requireNotNull(files).exportWorkspaceFile(path, output) },
        onResult = { receipt ->
            zxWorkspaceExportFeedback(receipt)?.let { fileFeedback = it }
            if (receipt is UiWorkspaceExportReceipt.Saved ||
                receipt is UiWorkspaceExportReceipt.ExternalAppUnavailable) clearSelection()
        },
    )
    val workspace by produceState(initialValue = emptyList<com.labteto.dshmobile.local.tools.LocalWorkspaceFile>(), files, refreshFiles) {
        value = runCatching { files?.workspaceFiles() }.getOrNull().orEmpty()
    }
    val conversation by produceState(
        initialValue = com.labteto.dshmobile.local.session.LocalConversationFiles(),
        files,
        sessionId,
        refreshFiles,
    ) {
        value = if (files == null || sessionId.isBlank()) {
            com.labteto.dshmobile.local.session.LocalConversationFiles()
        } else {
            runCatching { files.conversationFiles(sessionId) }.getOrNull()
                ?: com.labteto.dshmobile.local.session.LocalConversationFiles()
        }
    }
    ZxPage("工作区", onBack = { scope.onBack() }) {
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ZxChip("工作区", selectedDomain == 0) { selectedDomain = 0; clearSelection() }
            ZxChip("会话产物", selectedDomain == 1) { selectedDomain = 1; clearSelection() }
        }
        fileFeedback?.let { ZxFooterNote(it) }
        ZxField(fileQuery, { fileQuery = it; selectedPaths = emptySet() }, Modifier.fillMaxWidth(),
            placeholder = "搜索文件名或路径")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (selectedDomain == 0) {
                ZxChip("常用文件", !showInternalFiles) { showInternalFiles = false; selectedPaths = emptySet() }
                ZxChip("含内部文件", showInternalFiles) { showInternalFiles = true; selectedPaths = emptySet() }
            }
            ZxChip("刷新", false) { refreshFiles++; selectedPaths = emptySet() }
            if (selectedDomain == 0 && files != null)
                ZxChip(if (selectionMode) "取消选择" else "多选分享", selectionMode) {
                    if (selectionMode) clearSelection() else selectionMode = true
                }
        }
        if (selectedDomain == 0) {
            val visible = workspace.filter { zxWorkspaceFileVisible(it.path, fileQuery, showInternalFiles) }
            ZxSection("工作区文件 · ${visible.size}")
            if (selectionMode && files != null && visible.isNotEmpty()) {
                val visiblePaths = visible.map { it.path }.toSet()
                val selectedVisible = selectedPaths.intersect(visiblePaths)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ZxChip("全选本页", selectedVisible.size == visible.size) {
                        selectedPaths = if (selectedVisible.size == visible.size)
                            selectedPaths - visiblePaths else selectedPaths + visiblePaths
                    }
                    ZxChip("已选 ${selectedVisible.size} 项", false) {}
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ZxButton("打包保存", {
                        batchExporter(UiWorkspaceBatchExportRequest(selectedVisible.toList().sorted(),
                            UiWorkspaceExportAction.SAVE))
                    }, enabled = selectedVisible.isNotEmpty() && selectedVisible.size <= 100)
                    ZxButton("打包分享", {
                        batchExporter(UiWorkspaceBatchExportRequest(selectedVisible.toList().sorted(),
                            UiWorkspaceExportAction.SHARE))
                    }, enabled = selectedVisible.isNotEmpty() && selectedVisible.size <= 100,
                        variant = ZxButtonVariant.Outline)
                }
                ZxFooterNote("所选文件经系统文件选择器保存为 ZIP 后，才能分享给其他应用；最多 100 项。")
            }
            if (files == null) ZxEmpty("工作区不可用", "暂时无法读取文件")
            else if (workspace.isEmpty()) ZxEmpty("工作区是空的", "执行过程中产生的文件会出现在这里")
            else if (visible.isEmpty()) ZxEmpty("没有匹配的文件", "可调整搜索条件，或选择包含内部文件")
            visible.forEach { file ->
                val selected = file.path in selectedPaths
                ZxRow(file.path.substringAfterLast('/'),
                    "${zxHumanFileSize(file.bytes.toLong())} · ${file.path}",
                    onClick = {
                        if (selectionMode) selectedPaths = if (selected) selectedPaths - file.path
                            else selectedPaths + file.path
                        else onPreview(file.path)
                    },
                    trailing = {
                        if (selectionMode) ZxChip(if (selected) "已选" else "选择", selected) {
                            selectedPaths = if (selected) selectedPaths - file.path
                                else selectedPaths + file.path
                        }
                    })
            }
        } else {
            val visibleArtifacts = conversation.artifacts.filter {
                it.path.contains(fileQuery.trim(), ignoreCase = true)
            }
            val visibleInvolved = conversation.involved.filter {
                it.path.contains(fileQuery.trim(), ignoreCase = true)
            }
            ZxSection("当前会话产物")
            if (sessionId.isBlank()) ZxEmpty("尚未打开会话", "进入一个聊天或工作会话后可以查看产物")
            else if (conversation.isEmpty) ZxEmpty("暂无会话产物", "当前会话尚未产生可查看的文件")
            else if (visibleArtifacts.isEmpty() && visibleInvolved.isEmpty()) {
                ZxEmpty("没有匹配的会话文件", "调整搜索条件或清空搜索内容")
            }
            if (visibleArtifacts.isNotEmpty()) ZxSection("产物")
            visibleArtifacts.forEach { file ->
                ZxRow(file.path.substringAfterLast('/'),
                    "${zxHumanFileSize(file.bytes.toLong())} · ${file.path}",
                    onClick = { onPreview(file.path) })
            }
            if (visibleInvolved.isNotEmpty()) ZxSection("关联文件")
            visibleInvolved.forEach { file ->
                ZxRow(file.path.substringAfterLast('/'),
                    "${zxHumanFileSize(file.bytes.toLong())} · ${file.path}",
                    onClick = { onPreview(file.path) })
            }
        }
    }
}

/** workspace.preview — 使用 Owner 提供的实际预览与截断标记，不再截断调试字符串。 */
@Composable
fun ZhaoxiWorkspacePreviewScene(files: FilesUiPort?, path: String, scope: UiSceneRenderScope) {
    var exportFeedback by remember { mutableStateOf<String?>(null) }
    val exporter = rememberUiWorkspaceExporter(
        writeFile = { source, output -> requireNotNull(files).exportWorkspaceFile(source, output) },
        onResult = { receipt -> zxWorkspaceExportFeedback(receipt)?.let { exportFeedback = it } },
    )
    val previewState by produceState<Pair<Boolean, com.labteto.dshmobile.local.tools.LocalWorkspaceFilePreview?>>(false to null, files, path) {
        val result = if (files == null || path.isBlank()) null
            else runCatching { files.previewWorkspaceFile(path) }.getOrNull()
        value = true to result
    }
    ZxPage("文件预览", onBack = { scope.onBack() }) {
        Spacer(Modifier.height(8.dp))
        when {
            path.isBlank() -> ZxEmpty("尚未选择文件", "请先从工作区选择文件")
            files == null -> ZxEmpty("文件不可用", "暂时无法读取文件")
            !previewState.first -> ZxSection("正在读取文件…")
            previewState.second == null -> ZxEmpty("文件预览不可用", "请核实文件是否仍存在")
            else -> {
                val item = requireNotNull(previewState.second)
                ZxSection("文件信息")
                ZxValueRow("文件名", value = item.file.path.substringAfterLast('/'))
                ZxValueRow("大小", value = zxHumanFileSize(item.file.bytes.toLong()))
                exportFeedback?.let { ZxFooterNote(it) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ZxButton("保存", {
                        exporter(UiWorkspaceExportRequest(path, item.file.path.substringAfterLast('/'),
                            UiWorkspaceExportAction.SAVE))
                    })
                    ZxButton("分享", {
                        exporter(UiWorkspaceExportRequest(path, item.file.path.substringAfterLast('/'),
                            UiWorkspaceExportAction.SHARE))
                    }, variant = ZxButtonVariant.Outline)
                    ZxButton("外部打开", {
                        exporter(UiWorkspaceExportRequest(path, item.file.path.substringAfterLast('/'),
                            UiWorkspaceExportAction.OPEN))
                    }, variant = ZxButtonVariant.Outline)
                }
                ZxSection("内容预览")
                val content = item.text
                if (content == null) {
                    ZxEmpty("无法预览文本", "二进制文件不会按文字强行解码")
                } else {
                    Text(content, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        fontSize = 12.sp, color = ZhaoxiTheme.colors.ink)
                    if (item.truncated) ZxFooterNote("预览已截断")
                }
                ZxFooterNote(item.file.path)
            }
        }
    }
}

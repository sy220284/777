package com.labteto.dshmobile.uipack.v6

import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.uicontract.*
import com.labteto.dshmobile.uihost.UiSceneNavigationResult
import com.shenyan.designsystem.components.*

/** Navigation presentation only. Scene admission and every mutation remain with their Owners. */
@Composable
internal fun V6ConversationEntries(
    ports: V6ConversationPorts,
    settings: SettingsUiPort?,
    sessions: SessionUiPort?,
    scenes: Set<UiSceneId>?,
    workMode: Boolean,
    identity: UiSessionIdentity?,
    group: Boolean,
    compact: Boolean,
    onBack: () -> Unit,
    onNavigate: (UiSceneId) -> UiSceneNavigationResult,
) {
    val config = settings?.let { it.state.collectAsStateWithLifecycle(initialValue = it.initialState).value }
    var panel by remember(identity) { mutableStateOf<String?>(null) }
    var error by remember(identity) { mutableStateOf<String?>(null) }
    fun available(id: String) = scenes == null || UiSceneId(id) in scenes
    fun open(id: String, title: String) {
        val result = onNavigate(UiSceneId(id))
        if (result is UiSceneNavigationResult.Changed) { panel = null; error = null }
        else error = "暂时无法打开“$title”，请稍后再试"
    }
    fun filtered(entries: List<V6Destination>) = entries.filter { available(it.id) }
    @Composable fun Shortcut(id: String, title: String) {
        if (available(id)) SyButton(title, { open(id, title) }, modifier = Modifier.widthIn(max = 200.dp), style = SyButtonStyle.GHOST, compact = true)
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(if (workMode) "工作" else "聊天", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
        SyButton("会话", { error = null; panel = "sessions" }, style = SyButtonStyle.GHOST, compact = true)
        SyButton("更多", { error = null; panel = "more" }, style = SyButtonStyle.GHOST, compact = true)
        SyButton("返回", onBack, style = SyButtonStyle.GHOST, compact = true)
    }
    if (!compact) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (settings != null) Shortcut("conversation.model_picker", "模型：${config?.model ?: "选择模型"}")
        if (!workMode) SyButton(if (group) "群聊详情" else config?.personaName?.takeIf { it.isNotBlank() }
            ?: "人物与故事", { error = null; panel = "persona" }, modifier = Modifier.widthIn(max = 200.dp), style = SyButtonStyle.GHOST, compact = true)
        if (workMode && identity != null) {
            if (ports.workReads != null && ports.workPolicy != null) Shortcut("work.plan_review", "计划")
            if (ports.workTeam != null) Shortcut("work.team", "团队")
        }
    }
    if (panel != null) SyModalSheet(onDismiss = { panel = null }, title = when (panel) {
        "sessions" -> "会话与模式"
        "persona" -> if (group) "群聊详情" else "人物与故事"
        else -> "更多功能"
    }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            error?.let { SyNote(it) }
            if (panel == "sessions" && sessions != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(LocalUsageMode.CHAT to "聊天", LocalUsageMode.WORK to "工作").forEach { (mode, title) ->
                        SyButton(title, {
                            val receipt = sessions.submit(SessionUiCommand.SelectMode(mode))
                            if (receipt == UiCommandReceipt.Accepted) panel = null
                            else error = describeV6ConversationReceipt(receipt)
                        }, style = if (workMode == (mode == LocalUsageMode.WORK)) SyButtonStyle.PRIMARY else SyButtonStyle.SECONDARY)
                    }
                }
            }
            if (panel == "more") {
                if (compact && settings != null) Shortcut("conversation.model_picker", "切换模型")
                if (!workMode) SyCategoryRow(title = if (group) "群聊详情" else "人物与故事",
                    onClick = { error = null; panel = "persona" })
                if (compact && workMode && identity != null) {
                    if (ports.workReads != null && ports.workPolicy != null) Shortcut("work.plan_review", "计划审阅")
                    if (ports.workTeam != null) Shortcut("work.team", "团队成员")
                }
            }
            val entries = when (panel) {
                "sessions" -> listOf(V6Destination("shell.new_session", "新建与管理会话"),
                    V6Destination("shell.search", "搜索历史"), V6Destination("conversation.reader", "会话全文"))
                "persona" -> buildList {
                    if (ports.gallery != null && ports.selection != null) {
                        if (identity != null && !group) add(V6Destination("chat.persona_picker", "切换聊天人物"))
                        add(V6Destination("route.persona_gallery", "人物库与故事"))
                    }
                    add(V6Destination("chat.persona_editor", "人物资料"))
                    if (identity != null && !group) add(V6Destination("chat.character_tuning", "微调当前人物"))
                    add(V6Destination("chat.world_book", "世界书"))
                    add(V6Destination("route.diary", "人物日记"))
                    if (identity != null && ports.groups != null) {
                        add(V6Destination("chat.group_members", if (group) "群聊成员" else "创建群聊"))
                        if (group) add(V6Destination("chat.group_announcement", "群聊公告"))
                    }
                }
                else -> buildList {
                    add(V6Destination("media.creation", "媒体创作与成果"))
                    add(V6Destination("route.workspace", "工作文件"))
                    add(V6Destination("route.tools", "工具与插件"))
                    add(V6Destination("route.tasks", "自动任务"))
                    if (ports.documents != null && ports.forms != null) add(V6Destination("conversation.ui_document", "互动文档"))
                    add(V6Destination("route.settings", "应用设置"))
                }
            }
            filtered(entries).forEach { entry -> SyCategoryRow(title = entry.title, onClick = { open(entry.id, entry.title) }) }
        }
    }
}


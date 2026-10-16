package com.labteto.dshmobile.uipack.v6

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labteto.dshmobile.automation.AutomationMode
import com.labteto.dshmobile.automation.AutomationScheduleType
import com.labteto.dshmobile.automation.AutomationStatus
import com.labteto.dshmobile.automation.AutomationTask
import com.labteto.dshmobile.local.session.LocalConversationMode
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.uicontract.*
import com.labteto.dshmobile.uihost.UiSceneNavigationResult
import com.shenyan.designsystem.components.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One scheduler Owner, four real task workflows; list, edit, chat-scoped and global route. */
@Composable
fun V6AutomationScene(owner: AutomationUiPort, page: UiSceneId, onBack: () -> Unit) {
    val snapshot by owner.snapshots.collectAsStateWithLifecycle(
        initialValue = owner.snapshot())
    val onlyChat = page.value == "automation.persona_interaction"
    var editor by remember(page) { mutableStateOf(page.value == "automation.editor") }
    var editing by remember(page) { mutableStateOf<AutomationTask?>(null) }
    var remove by remember(page) { mutableStateOf<AutomationTask?>(null) }
    var prompt by remember(page) { mutableStateOf("") }
    var firstMinutes by remember(page) { mutableStateOf("60") }
    var mode by remember(page) {
        mutableStateOf(if (onlyChat) AutomationMode.CHAT else AutomationMode.WORK)
    }
    var busy by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun send(command: AutomationUiCommand) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { owner.submit(command) }
                feedback = when (result) {
                    is AutomationUiReceipt.Applied -> {
                        editor = false; editing = null
                        "已向任务 Owner 提交；实际执行状态以任务记录为准"
                    }
                    is AutomationUiReceipt.Rejected -> "任务被拒绝：" + result.reason.name
                    is AutomationUiReceipt.Failed -> result.message ?: "任务操作失败"
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { feedback = failure.message ?: "任务操作失败" }
            finally { busy = false }
        }
    }
    fun select(task: AutomationTask) {
        editing = task; prompt = task.prompt; mode = task.mode
        firstMinutes = ((task.nextRunAt - System.currentTimeMillis()).coerceAtLeast(60_000L) /
            60_000L).toString()
        editor = true
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(if (onlyChat) "人物互动自动任务" else "自动任务",
            style = MaterialTheme.typography.titleLarge)
        SyButton("返回", onBack, style = SyButtonStyle.GHOST)
        feedback?.let { SyNote(it) }
        if (!editor) {
            SyButton("新建任务", {
                prompt = ""; firstMinutes = "60"; editing = null; editor = true
            }, enabled = !busy)
            snapshot.tasks.filter { !onlyChat || it.mode == AutomationMode.CHAT }.forEach { task ->
                SyGlassCard(Modifier.fillMaxWidth()) {
                    Text(task.prompt, style = MaterialTheme.typography.titleMedium)
                    Text(task.mode.name + " · " + task.status.name + " · " + task.scheduleType.name)
                    Text("下次执行：" + java.text.DateFormat.getDateTimeInstance().format(
                        java.util.Date(task.nextRunAt)))
                    task.lastError?.let { SyNote(it) }
                    task.lastResult?.let { Text(it) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SyButton("编辑", { select(task) }, style = SyButtonStyle.SECONDARY)
                        SyButton(if (task.status == AutomationStatus.PAUSED) "恢复" else "暂停", {
                            send(if (task.status == AutomationStatus.PAUSED)
                                AutomationUiCommand.Resume(task.id)
                            else AutomationUiCommand.Pause(task.id))
                        }, enabled = !busy, style = SyButtonStyle.GHOST)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SyButton("立即执行", { send(AutomationUiCommand.RunNow(task.id)) },
                            enabled = !busy, style = SyButtonStyle.SECONDARY)
                        SyButton("删除", { remove = task }, enabled = !busy,
                            style = SyButtonStyle.DANGER)
                    }
                }
            }
        } else {
            if (editing == null && !onlyChat) {
                SyButton("Work 任务", { mode = AutomationMode.WORK },
                    style = if (mode == AutomationMode.WORK) SyButtonStyle.PRIMARY else SyButtonStyle.SECONDARY)
                SyButton("人物互动任务", { mode = AutomationMode.CHAT },
                    style = if (mode == AutomationMode.CHAT) SyButtonStyle.PRIMARY else SyButtonStyle.SECONDARY)
            }
            SyInput(prompt, { prompt = it }, label = "任务指令", singleLine = false)
            SyInput(firstMinutes, { firstMinutes = it.filter(Char::isDigit).take(8) },
                label = "距离下一次执行的分钟数")
            val minutes = firstMinutes.toLongOrNull()?.takeIf { it in 1..525600 }
            val chatAllowed = snapshot.context.canPlanChat && snapshot.context.identity != null
            if (mode == AutomationMode.CHAT && editing == null && !chatAllowed)
                SyNote("人物互动任务只允许在当前有效单人聊天中创建")
            SyButton(if (editing == null) "创建任务" else "更新任务", {
                val t = editing
                val delay = minutes ?: return@SyButton
                val timestamp = System.currentTimeMillis() + delay * 60_000
                val schedule = if (t == null) UiAutomationSchedule(
                    prompt.trim(), timestamp, null, mode, AutomationScheduleType.ONCE)
                else UiAutomationSchedule(prompt.trim(), timestamp, t.recurringMinutes,
                    t.mode, t.scheduleType, t.silenceMinutes,
                    t.windowStartMinuteOfDay, t.windowEndMinuteOfDay,
                    t.quietHoursEnabled, t.quietStartHour, t.quietStartMinute,
                    t.quietEndHour, t.quietEndMinute,
                    t.proactiveMinGapMinutes, t.proactiveMaxUnanswered, t.notify)
                send(if (t == null) AutomationUiCommand.Create(
                    if (mode == AutomationMode.CHAT) snapshot.context.identity else null, schedule)
                else AutomationUiCommand.Update(t.id, t.scheduleGeneration, schedule))
            }, enabled = !busy && prompt.isNotBlank() && minutes != null &&
                (editing != null || mode != AutomationMode.CHAT || chatAllowed))
            SyButton("取消", { editing = null; editor = false }, style = SyButtonStyle.GHOST)
        }
    }
    remove?.let { task -> AlertDialog(onDismissRequest = { remove = null },
        title = { Text("确认删除自动任务") }, text = { Text(task.prompt) },
        confirmButton = { TextButton(onClick = {
            remove = null; send(AutomationUiCommand.Delete(task.id))
        }) { Text("确认删除") } },
        dismissButton = { TextButton(onClick = { remove = null }) { Text("取消") } })
    }
}

/** Session landing, create and recovery use the exact foreground Session Owner. */
@Composable
fun V6SessionShellScene(
    page: UiSceneId,
    sessions: SessionUiPort,
    onNavigate: (UiSceneId) -> UiSceneNavigationResult,
    onBack: () -> Unit,
) {
    val state by sessions.snapshots.collectAsStateWithLifecycle(initialValue = sessions.snapshot())
    var feedback by remember(page) { mutableStateOf<String?>(null) }
    var deletion by remember { mutableStateOf<String?>(null) }
    var mode by remember { mutableStateOf(LocalConversationMode.INDEPENDENT) }
    var summary by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun create() {
        if (loading) return
        val target = state.identity
        val handoff = if (mode == LocalConversationMode.CONTINUATION && target != null)
            sessions.handoffSummary(target) else null
        if (mode == LocalConversationMode.CONTINUATION && handoff == null) {
            feedback = "当前会话没有可复用的续聊摘要"
            return
        }
        feedback = describeV6ConversationReceipt(sessions.submit(
            SessionUiCommand.Create(target, mode, state.usageMode, handoff)))
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(when(page.value) {
            "shell.new_session" -> "创建会话"
            "shell.onboarding" -> "开始使用"
            "shell.empty" -> "尚无会话"
            else -> "会话管理"
        }, style = MaterialTheme.typography.titleLarge)
        SyButton("返回", onBack, style = SyButtonStyle.GHOST)
        state.error?.let { SyNote(it) }
        feedback?.let { SyNote(it) }
        if (state.loading) SyNote("正在读取真实会话")
        if (page.value == "shell.onboarding") Text("选择聊天或工作模式，创建本地会话后开始。")
        if (page.value == "shell.empty" && state.sessions.isEmpty()) Text("当前没有已保存会话")
        val current = state.usageMode
        SyButton("聊天模式", {
            feedback = describeV6ConversationReceipt(
                sessions.submit(SessionUiCommand.SelectMode(LocalUsageMode.CHAT)))
        }, style = if (current == LocalUsageMode.CHAT) SyButtonStyle.PRIMARY else SyButtonStyle.SECONDARY)
        SyButton("Work 模式", {
            feedback = describeV6ConversationReceipt(
                sessions.submit(SessionUiCommand.SelectMode(LocalUsageMode.WORK)))
        }, style = if (current == LocalUsageMode.WORK) SyButtonStyle.PRIMARY else SyButtonStyle.SECONDARY)
        LocalConversationMode.entries.forEach { candidate ->
            SyButton(candidate.name, { mode = candidate },
                style = if (candidate == mode) SyButtonStyle.PRIMARY else SyButtonStyle.GHOST)
        }
        SyButton("创建本地会话", { create() }, enabled = !state.loading)
        Text("已保存会话：" + state.sessions.size)
        state.sessions.forEach { session ->
            SyGlassCard(Modifier.fillMaxWidth()) {
                Text(session.title)
                SyButton("打开会话", {
                    val receipt = sessions.submit(SessionUiCommand.Open(session.id))
                    feedback = describeV6ConversationReceipt(receipt)
                    if (receipt == UiCommandReceipt.Accepted ||
                        receipt == UiCommandReceipt.Queued)
                        onNavigate(UiSceneId("route.home"))
                }, enabled = !state.loading, style = SyButtonStyle.SECONDARY)
                SyButton("删除此会话", { deletion = session.id },
                    enabled = !state.loading, style = SyButtonStyle.DANGER)
            }
        }
    }
    deletion?.let { id -> AlertDialog(onDismissRequest = { deletion = null },
        title = { Text("确认删除此本地会话") }, text = { Text("此操作会删除已有历史，需要谨慎确认。") },
        confirmButton = { TextButton(onClick = {
            deletion = null
            if (loading) return@TextButton
            loading = true
            scope.launch {
                try {
                    feedback = when (val result = withContext(Dispatchers.IO) {
                        sessions.delete(setOf(id))
                    }) {
                        is UiSessionDeletionReceipt.Deleted ->
                            "Owner 已删除：" + result.sessionIds.joinToString()
                        is UiSessionDeletionReceipt.Failed ->
                            result.message ?: "删除未完成，请检查真实会话目录"
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { feedback = failure.message ?: "删除异常" }
                finally { loading = false }
            }
        }) { Text("确认删除") } },
        dismissButton = { TextButton(onClick = { deletion = null }) { Text("取消") } })
    }
}

/** Session-scoped cursors must never be replayed into a different search query. */
@Composable
fun V6GlobalHistorySearchScene(
    sessions: SessionUiPort, history: UiMessageHistoryPort,
    onOpen: () -> Unit, onBack: () -> Unit,
) {
    data class Result(val sessionId: String, val title: String, val message: UiMessageSnapshot)
    var query by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf("") }
    var revision by remember { mutableIntStateOf(0) }
    var results by remember { mutableStateOf(emptyList<Result>()) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var limited by remember { mutableStateOf(false) }
    LaunchedEffect(sessions, history, submitted, revision) {
        if (submitted.isBlank()) return@LaunchedEffect
        scanning = true; results = emptyList(); limited = false; feedback = null
        val found = mutableListOf<Result>()
        try {
            val catalog = sessions.snapshot().sessions
            for (entry in catalog) {
                var cursor: UiMessageSearchCursor? = null
                var pages = 0
                do {
                    val reply = withContext(Dispatchers.IO) {
                        history.searchMessages(entry.id, submitted, cursor)
                    }
                    when (reply) {
                        is UiMessageSearchReceipt.Failed -> {
                            feedback = reply.message ?: "部分会话搜索失败"
                            cursor = null
                        }
                        is UiMessageSearchReceipt.Available -> {
                            found.addAll(reply.result.page.messages
                                .filter { it.id in reply.result.hitIds }
                                .map { Result(entry.id, entry.title, it) })
                            results = found.take(200).toList()
                            cursor = reply.result.nextCursor
                        }
                    }
                    pages++
                    if (found.size >= 200 || pages >= 1000) {
                        limited = true
                        break
                    }
                } while (cursor != null)
                if (limited) break
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { feedback = failure.message ?: "跨会话搜索失败" }
        finally { scanning = false }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("搜索全部会话", style = MaterialTheme.typography.titleLarge)
        SyButton("返回", onBack, style = SyButtonStyle.GHOST)
        SyInput(query, { query = it.take(500) }, label = "搜索关键词")
        SyButton("执行跨会话搜索", { submitted = query.trim(); revision++ },
            enabled = !scanning && query.isNotBlank())
        if (scanning) SyNote("正在查找原消息 Owner 历史")
        feedback?.let { SyNote(it) }
        if (limited) SyNote("此次搜索已达有界检索预算，不代表扫描了所有会话历史")
        Text("找到：" + results.size)
        results.forEach { result ->
            SyGlassCard(Modifier.fillMaxWidth()) {
                Text(result.title + " · " + result.message.role.name)
                SelectionContainer { Text(result.message.text.take(3000)) }
                SyButton("打开所属会话", {
                    val receipt = sessions.submit(SessionUiCommand.Open(result.sessionId))
                    feedback = describeV6ConversationReceipt(receipt)
                    if (receipt == UiCommandReceipt.Accepted || receipt == UiCommandReceipt.Queued)
                        onOpen()
                }, style = SyButtonStyle.SECONDARY)
            }
        }
    }
}

@Composable
fun V6ShellErrorScene(ports: V6ConversationPorts,
    onDiagnostics: () -> UiSceneNavigationResult, onBack: () -> Unit) {
    val chat by ports.chat.snapshots.collectAsStateWithLifecycle(initialValue = ports.chat.snapshot())
    val work by ports.work.snapshots.collectAsStateWithLifecycle(initialValue = ports.work.snapshot())
    var feedback by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("会话故障", style = MaterialTheme.typography.titleLarge)
        SyButton("返回", onBack, style = SyButtonStyle.GHOST)
        Text("聊天状态：" + chat.status.name + " · Work 状态：" + work.status.name)
        chat.error?.let { SyNote("聊天：" + it) }
        work.error?.let { SyNote("Work：" + it) }
        feedback?.let { SyNote(it) }
        SyButton("查看诊断", {
            val receipt = onDiagnostics()
            if (receipt !is UiSceneNavigationResult.Changed)
                feedback = "诊断页面当前不可达"
        }, style = SyButtonStyle.SECONDARY)
    }
}

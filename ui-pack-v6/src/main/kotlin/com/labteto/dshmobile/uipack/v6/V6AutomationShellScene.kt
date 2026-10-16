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
import com.labteto.dshmobile.uicontract.ConversationReadingUiPort
import com.labteto.dshmobile.uicontract.UiReadingAnchor
import com.labteto.dshmobile.uicontract.*
import com.labteto.dshmobile.uihost.UiSceneNavigationResult
import com.shenyan.designsystem.components.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext

/** Calendar cadence needs non-null recurring metadata so WorkManager never treats it as once. */
internal fun v6AutomationRecurrence(type: AutomationScheduleType, interval: Long?): Long? =
    when (type) {
        AutomationScheduleType.DAILY -> 24L * 60L
        AutomationScheduleType.WEEKLY -> 7L * 24L * 60L
        AutomationScheduleType.MONTHLY -> 30L * 24L * 60L
        AutomationScheduleType.INTERVAL -> interval?.takeIf { it >= 60L }
        else -> null
    }

/** An asynchronous result can update editor state only for its original activation. */
internal fun v6AutomationResultMatchesEditor(
    admitted: UiSessionIdentity?,
    current: UiSessionIdentity?,
): Boolean = admitted == current

/** One scheduler Owner, four real task workflows; list, edit, chat-scoped and global route. */
@Composable
fun V6AutomationScene(owner: AutomationUiPort, page: UiSceneId, onBack: () -> Unit) {
    val snapshot by owner.snapshots.collectAsStateWithLifecycle(
        initialValue = owner.snapshot())
    val onlyChat = page.value == "automation.persona_interaction"
    var editor by remember(page) { mutableStateOf(page.value == "automation.editor") }
    // Capture the activation at editor entry: a reused draft must never create in a new chat.
    var editorIdentity by remember(page, owner) {
        mutableStateOf(owner.snapshot().context.identity)
    }
    var editing by remember(page) { mutableStateOf<AutomationTask?>(null) }
    var remove by remember(page) { mutableStateOf<AutomationTask?>(null) }
    var taskActions by remember(page) { mutableStateOf<String?>(null) }
    var prompt by remember(page) { mutableStateOf("") }
    var firstMinutes by remember(page) { mutableStateOf("60") }
    var scheduleType by remember(page) { mutableStateOf(AutomationScheduleType.ONCE) }
    var repeatMinutes by remember(page) { mutableStateOf("1440") }
    var silenceMinutes by remember(page) { mutableStateOf("60") }
    var windowStart by remember(page) { mutableStateOf("1200") }
    var windowEnd by remember(page) { mutableStateOf("1320") }
    var quietEnabled by remember(page) { mutableStateOf(false) }
    var quietStartHour by remember(page) { mutableStateOf("23") }
    var quietStartMinute by remember(page) { mutableStateOf("0") }
    var quietEndHour by remember(page) { mutableStateOf("8") }
    var quietEndMinute by remember(page) { mutableStateOf("0") }
    var proactiveGap by remember(page) { mutableStateOf("60") }
    var maxUnanswered by remember(page) { mutableStateOf("2") }
    var notify by remember(page) { mutableStateOf(true) }
    var mode by remember(page) {
        mutableStateOf(if (onlyChat) AutomationMode.CHAT else AutomationMode.WORK)
    }
    var busy by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    // Editor and list have very different heights. Restore the viewport when switching so
    // "新建任务" and the editor header cannot remain outside the accessible viewport.
    val editorScroll = rememberScrollState()
    LaunchedEffect(editor) { editorScroll.scrollTo(0) }
    fun send(command: AutomationUiCommand) {
        if (busy) return
        val admittedIdentity = owner.snapshot().context.identity
        busy = true
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { owner.submit(command) }
                if (!v6AutomationResultMatchesEditor(admittedIdentity, owner.snapshot().context.identity)) {
                    feedback = "任务操作来自之前的会话，请在任务列表确认结果"
                    return@launch
                }
                feedback = when (result) {
                    is AutomationUiReceipt.Applied -> {
                        editor = false; editing = null
                        "已向任务 Owner 提交；实际执行状态以任务记录为准"
                    }
                    is AutomationUiReceipt.Rejected -> "任务被拒绝：" + result.reason.name
                    is AutomationUiReceipt.Failed -> result.message ?: "任务操作失败"
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                feedback = if (v6AutomationResultMatchesEditor(admittedIdentity, owner.snapshot().context.identity))
                    failure.message ?: "任务操作失败"
                else "旧会话的任务操作失败，请检查任务列表"
            } finally { busy = false }
        }
    }
    fun resetScheduleForm() {
        scheduleType = AutomationScheduleType.ONCE
        repeatMinutes = "1440"; silenceMinutes = "60"
        windowStart = "1200"; windowEnd = "1320"
        quietEnabled = false
        quietStartHour = "23"; quietStartMinute = "0"
        quietEndHour = "8"; quietEndMinute = "0"
        proactiveGap = "60"; maxUnanswered = "2"
        notify = true
    }
    fun select(task: AutomationTask) {
        editorIdentity = snapshot.context.identity
        editing = task; prompt = task.prompt; mode = task.mode
        firstMinutes = ((task.nextRunAt - System.currentTimeMillis()).coerceAtLeast(60_000L) /
            60_000L).toString()
        scheduleType = task.scheduleType
        repeatMinutes = (task.recurringMinutes ?: 1440L).toString()
        silenceMinutes = (task.silenceMinutes ?: 60L).toString()
        windowStart = (task.windowStartMinuteOfDay ?: 1200).toString()
        windowEnd = (task.windowEndMinuteOfDay ?: 1320).toString()
        quietEnabled = task.quietHoursEnabled
        quietStartHour = task.quietStartHour.toString()
        quietStartMinute = task.quietStartMinute.toString()
        quietEndHour = task.quietEndHour.toString()
        quietEndMinute = task.quietEndMinute.toString()
        proactiveGap = task.proactiveMinGapMinutes.toString()
        maxUnanswered = task.proactiveMaxUnanswered.toString()
        notify = task.notify
        editor = true
    }
    Column(Modifier.fillMaxSize().verticalScroll(editorScroll).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(if (onlyChat) "人物互动自动任务" else "自动任务",
            style = MaterialTheme.typography.titleLarge)
        SyButton("返回", onBack, style = SyButtonStyle.GHOST)
        feedback?.let { SyNote(it) }
        if (!editor) {
            SyButton("新建任务", {
                prompt = ""; firstMinutes = "60"; editing = null
                editorIdentity = owner.snapshot().context.identity
                resetScheduleForm(); editor = true
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
                        SyButton("任务操作", { taskActions = task.id }, style = SyButtonStyle.SECONDARY)
                        SyButton(if (task.status == AutomationStatus.PAUSED) "恢复" else "暂停", {
                            send(if (task.status == AutomationStatus.PAUSED)
                                AutomationUiCommand.Resume(task.id)
                            else AutomationUiCommand.Pause(task.id))
                        }, enabled = !busy, style = SyButtonStyle.GHOST)
                    }

                }
            }
            taskActions?.let { id ->
                SyModalSheet(onDismiss = { taskActions = null }, title = "任务操作") {
                    val task = snapshot.tasks.firstOrNull { it.id == id }
                    if (task == null) SyNote("此任务已不在当前列表")
                    else {
                        SyButton("编辑任务", { taskActions = null; select(task) }, enabled = !busy, style = SyButtonStyle.GHOST)
                        SyButton("立即执行", { taskActions = null; send(AutomationUiCommand.RunNow(task.id)) },
                            enabled = !busy, style = SyButtonStyle.GHOST)
                        SyButton("删除任务", { taskActions = null; remove = task }, enabled = !busy, style = SyButtonStyle.DANGER)
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
            Text("排程类型", style = MaterialTheme.typography.titleMedium)
            listOf(
                AutomationScheduleType.ONCE to "单次",
                AutomationScheduleType.INTERVAL to "固定间隔",
                AutomationScheduleType.DAILY to "每天",
                AutomationScheduleType.WEEKLY to "每周",
                AutomationScheduleType.MONTHLY to "每月",
                AutomationScheduleType.SILENCE to "用户静默触发",
                AutomationScheduleType.WINDOW to "限定时间窗",
            ).forEach { (type, label) ->
                SyButton(label, { scheduleType = type },
                    style = if (scheduleType == type) SyButtonStyle.PRIMARY
                        else SyButtonStyle.GHOST)
            }
            if (scheduleType == AutomationScheduleType.INTERVAL ||
                scheduleType == AutomationScheduleType.SILENCE) {
                if (scheduleType == AutomationScheduleType.INTERVAL) {
                    SyInput(repeatMinutes, { repeatMinutes = it.filter(Char::isDigit).take(8) },
                        label = "重复间隔（分钟，至少 60）")
                } else {
                    SyInput(silenceMinutes, { silenceMinutes = it.filter(Char::isDigit).take(8) },
                        label = "未收到用户消息持续多久后触发（分钟）")
                }
            }
            if (scheduleType == AutomationScheduleType.WINDOW) {
                SyInput(windowStart, { windowStart = it.filter(Char::isDigit).take(4) },
                    label = "每日时间窗开始（从零点起算的分钟）")
                SyInput(windowEnd, { windowEnd = it.filter(Char::isDigit).take(4) },
                    label = "每日时间窗结束（从零点起算的分钟）")
            }
            SySwitch("通知任务结果", notify, { notify = it })
            if (mode == AutomationMode.CHAT) {
                SySwitch("启用静默时段", quietEnabled, { quietEnabled = it })
                if (quietEnabled) {
                    SyInput(quietStartHour, { quietStartHour = it.filter(Char::isDigit).take(2) },
                        label = "静默开始小时（0–23）")
                    SyInput(quietStartMinute, { quietStartMinute = it.filter(Char::isDigit).take(2) },
                        label = "静默开始分钟（0–59）")
                    SyInput(quietEndHour, { quietEndHour = it.filter(Char::isDigit).take(2) },
                        label = "静默结束小时（0–23）")
                    SyInput(quietEndMinute, { quietEndMinute = it.filter(Char::isDigit).take(2) },
                        label = "静默结束分钟（0–59）")
                }
                SyInput(proactiveGap, { proactiveGap = it.filter(Char::isDigit).take(8) },
                    label = "主动接话最短间隔（分钟）")
                SyInput(maxUnanswered, { maxUnanswered = it.filter(Char::isDigit).take(4) },
                    label = "连续未回应最多次数")
            }
            val minutes = firstMinutes.toLongOrNull()?.takeIf { it in 1..525600 }
            val repeat = repeatMinutes.toLongOrNull()
            val silence = silenceMinutes.toLongOrNull()
            val start = windowStart.toIntOrNull()
            val end = windowEnd.toIntOrNull()
            val qsh = quietStartHour.toIntOrNull()
            val qsm = quietStartMinute.toIntOrNull()
            val qeh = quietEndHour.toIntOrNull()
            val qem = quietEndMinute.toIntOrNull()
            val gap = proactiveGap.toLongOrNull()
            val unanswered = maxUnanswered.toIntOrNull()
            val scheduleValid = when (scheduleType) {
                AutomationScheduleType.INTERVAL -> repeat != null && repeat >= 60
                AutomationScheduleType.SILENCE -> silence != null && silence >= 1
                AutomationScheduleType.WINDOW -> start != null && end != null &&
                    start in 0..1439 && end in 0..1439 && start != end
                else -> true
            }
            val policyValid = mode != AutomationMode.CHAT ||
                (gap != null && gap >= 0 && unanswered != null && unanswered >= 0 &&
                    (!quietEnabled || (qsh in 0..23 && qsm in 0..59 &&
                        qeh in 0..23 && qem in 0..59)))
            val chatAllowed = snapshot.context.canPlanChat && snapshot.context.identity != null
            val sameEditorChat = snapshot.context.identity == editorIdentity
            if (mode == AutomationMode.CHAT && editing == null && !chatAllowed)
                SyNote("人物互动任务只允许在当前有效单人聊天中创建")
            if (mode == AutomationMode.CHAT && editing == null && !sameEditorChat)
                SyNote("编辑任务期间聊天已切换；请取消后在当前会话重新新建任务")
            SyButton(if (editing == null) "创建任务" else "更新任务", {
                val t = editing
                if (t == null && mode == AutomationMode.CHAT) {
                    val current = owner.snapshot().context
                    if (!current.canPlanChat || current.identity != editorIdentity) {
                        feedback = "聊天上下文已变化，未创建自动任务；请在新会话重新开始"
                        return@SyButton
                    }
                }
                val delay = minutes ?: return@SyButton
                val timestamp = System.currentTimeMillis() + delay * 60_000
                val actualMode = t?.mode ?: mode
                val schedule = UiAutomationSchedule(
                    prompt.trim(), timestamp,
                    v6AutomationRecurrence(scheduleType, repeat),
                    actualMode, scheduleType,
                    if (scheduleType == AutomationScheduleType.SILENCE) silence else null,
                    if (scheduleType == AutomationScheduleType.WINDOW) start else null,
                    if (scheduleType == AutomationScheduleType.WINDOW) end else null,
                    actualMode == AutomationMode.CHAT && quietEnabled,
                    qsh ?: 23, qsm ?: 0, qeh ?: 8, qem ?: 0,
                    gap ?: 60, unanswered ?: 2, notify)
                send(if (t == null) AutomationUiCommand.Create(
                    if (mode == AutomationMode.CHAT) editorIdentity else null, schedule)
                else AutomationUiCommand.Update(t.id, t.scheduleGeneration, schedule))
            }, enabled = !busy && prompt.isNotBlank() && minutes != null &&
                scheduleValid && policyValid &&
                (editing != null || mode != AutomationMode.CHAT ||
                    (chatAllowed && sameEditorChat)))
            SyButton("取消", {
                // An abandoned task draft from the previous conversation must not survive
                // a user-requested cancel into another session's editor.
                editing = null
                prompt = ""
                editorIdentity = owner.snapshot().context.identity
                editor = false
            }, style = SyButtonStyle.GHOST)
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
    var creatingSession by remember(page) { mutableStateOf(page.value != "shell.empty") }
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
        val receipt = sessions.submit(
            SessionUiCommand.Create(target, mode, state.usageMode, handoff))
        feedback = describeV6ConversationReceipt(receipt)
        if (receipt == UiCommandReceipt.Accepted || receipt == UiCommandReceipt.Queued) {
            // Queued is not an active session. Never show the old story as the new one.
            val previousSessionId = target?.sessionId
            loading = true
            scope.launch {
                try {
                    val active = withTimeoutOrNull(15_000L) {
                        sessions.snapshots.first { snapshot ->
                            val activeIdentity = snapshot.identity
                            !snapshot.loading && activeIdentity != null &&
                                activeIdentity.sessionId != previousSessionId
                        }
                    }
                    if (active == null) feedback = "新会话尚未确认激活，请从会话列表查看真实创建结果"
                    else if (onNavigate(UiSceneId("route.home")) !is UiSceneNavigationResult.Changed)
                        feedback = "新会话已激活，但聊天首页暂不可达"
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { feedback = failure.message ?: "无法确认新会话" }
                finally { loading = false }
            }
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(when(page.value) {
            "shell.new_session" -> "会话"
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
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SyButton("新建", { creatingSession = true },
                style = if (creatingSession) SyButtonStyle.PRIMARY else SyButtonStyle.GHOST)
            SyButton("历史会话", { creatingSession = false },
                style = if (creatingSession) SyButtonStyle.GHOST else SyButtonStyle.PRIMARY)
            SyButton("搜索", {
                if (onNavigate(UiSceneId("shell.search")) !is UiSceneNavigationResult.Changed)
                    feedback = "历史搜索暂时无法打开"
            }, style = SyButtonStyle.GHOST)
        }
        val current = state.usageMode
        if (creatingSession) {
            SyButton("聊天模式", {
                feedback = describeV6ConversationReceipt(
                    sessions.submit(SessionUiCommand.SelectMode(LocalUsageMode.CHAT)))
            }, style = if (current == LocalUsageMode.CHAT) SyButtonStyle.PRIMARY else SyButtonStyle.SECONDARY)
            SyButton("工作模式", {
                feedback = describeV6ConversationReceipt(
                    sessions.submit(SessionUiCommand.SelectMode(LocalUsageMode.WORK)))
            }, style = if (current == LocalUsageMode.WORK) SyButtonStyle.PRIMARY else SyButtonStyle.SECONDARY)
            LocalConversationMode.entries.forEach { candidate ->
                SyButton(when (candidate) {
                    LocalConversationMode.INDEPENDENT -> "独立会话"
                    LocalConversationMode.PROJECT -> "项目会话"
                    LocalConversationMode.CONTINUATION -> "承接上文"
                }, { mode = candidate },
                    style = if (candidate == mode) SyButtonStyle.PRIMARY else SyButtonStyle.GHOST)
            }
            SyButton("创建本地会话", { create() }, enabled = !state.loading)
        } else {
            Text("已保存会话：" + state.sessions.size)
            state.sessions.forEach { session ->
                SyGlassCard(Modifier.fillMaxWidth()) {
                    Text(session.title)
                    SyButton("打开会话", {
                        val receipt = sessions.submit(SessionUiCommand.Open(session.id))
                        feedback = describeV6ConversationReceipt(receipt)
                        if (receipt == UiCommandReceipt.Accepted ||
                            receipt == UiCommandReceipt.Queued) {
                            loading = true
                            scope.launch {
                                try {
                                    val opened = withTimeoutOrNull(15_000L) {
                                        sessions.snapshots.first { !it.loading && it.identity?.sessionId == session.id }
                                    }
                                    if (opened != null) {
                                        if (onNavigate(UiSceneId("route.home")) !is UiSceneNavigationResult.Changed)
                                            feedback = "会话已打开，但聊天首页暂不可达"
                                    } else feedback = "尚未确认打开目标会话，请刷新目录后重试"
                                } catch (cancelled: CancellationException) { throw cancelled }
                                catch (failure: Exception) { feedback = failure.message ?: "会话打开失败" }
                                finally { loading = false }
                            }
                        }
                    }, enabled = !state.loading, style = SyButtonStyle.SECONDARY)
                    SyButton("删除此会话", { deletion = session.id },
                        enabled = !state.loading, style = SyButtonStyle.DANGER)
                }
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
    reading: ConversationReadingUiPort?, onOpen: () -> Unit, onBack: () -> Unit,
) {
    data class Result(val sessionId: String, val title: String, val message: UiMessageSnapshot)
    var query by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf("") }
    var revision by remember { mutableIntStateOf(0) }
    var results by remember { mutableStateOf(emptyList<Result>()) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var limited by remember { mutableStateOf(false) }
    var openingSession by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
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
                    if (openingSession == null) {
                        val target = result.sessionId
                        val receipt = sessions.submit(SessionUiCommand.Open(target))
                        feedback = describeV6ConversationReceipt(receipt)
                        if (receipt == UiCommandReceipt.Accepted || receipt == UiCommandReceipt.Queued) {
                            openingSession = target
                            scope.launch {
                                try {
                                    val activated = withTimeoutOrNull(15_000L) {
                                        sessions.snapshots.first {
                                            !it.loading && it.identity?.sessionId == target
                                        }
                                    }
                                    if (openingSession == target) {
                                        if (activated != null) {
                                            // This is a presentation anchor only, not a mutating
                                            // Chat/Work action or a fabricated session activation.
                                            reading?.rememberAnchor(target,
                                                UiReadingAnchor(result.message.id, 0, false))
                                            onOpen()
                                        } else feedback = "会话切换尚未生效，请从会话列表确认后重试"
                                    }
                                } catch (cancelled: CancellationException) { throw cancelled }
                                catch (failure: Exception) {
                                    if (openingSession == target)
                                        feedback = failure.message ?: "会话打开失败"
                                } finally {
                                    if (openingSession == target) openingSession = null
                                }
                            }
                        }
                    }
                }, enabled = openingSession == null, style = SyButtonStyle.SECONDARY)
            }
        }
    }
}

@Composable
fun V6ShellErrorScene(ports: V6ConversationPorts,
    onDiagnostics: () -> UiSceneNavigationResult, onBack: () -> Unit,
    sessions: SessionUiPort? = null,
    onReopen: () -> UiSceneNavigationResult = { UiSceneNavigationResult.AtRoot },
) {
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
        val sessionOwner = sessions
        val session = sessionOwner?.snapshot()
        val activeId = session?.identity
        if (activeId != null && sessionOwner != null && session != null && !session.loading) {
            SyButton("重新打开当前本地会话", {
                val latest = sessionOwner.snapshot()
                if (latest.identity != activeId) {
                    feedback = "会话已切换，请刷新错误页"
                } else {
                    val receipt = sessionOwner.submit(SessionUiCommand.Open(activeId.sessionId))
                    feedback = describeV6ConversationReceipt(receipt)
                    if (receipt == UiCommandReceipt.Accepted || receipt == UiCommandReceipt.Queued) {
                        val navigation = onReopen()
                        if (navigation !is UiSceneNavigationResult.Changed)
                            feedback = "已提交重新打开原会话，请手动返回聊天首页查看"
                    }
                }
            }, style = SyButtonStyle.SECONDARY)
            SyNote("重新打开不会自动重发消息或重试可能计费的模型请求")
        }
        SyButton("查看诊断", {
            val receipt = onDiagnostics()
            if (receipt !is UiSceneNavigationResult.Changed)
                feedback = "诊断页面当前不可达"
        }, style = SyButtonStyle.SECONDARY)
    }
}

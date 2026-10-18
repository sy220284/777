package com.labteto.dshmobile.uipack.zhaoxi.scenes

import androidx.compose.foundation.layout.FlowRow
import com.labteto.dshmobile.automation.AutomationMode
import com.labteto.dshmobile.automation.AutomationScheduleType
import com.labteto.dshmobile.uicontract.AutomationUiReceipt
import com.labteto.dshmobile.uicontract.UiAutomationSchedule
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxField
import kotlinx.coroutines.CancellationException
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.labteto.dshmobile.uicontract.UiSceneId
import com.labteto.dshmobile.uicontract.submit
import com.labteto.dshmobile.uicontract.PlanChatAutomationCommand
import com.labteto.dshmobile.uicontract.AutomationUiCommand
import com.labteto.dshmobile.uicontract.AutomationUiPort
import com.labteto.dshmobile.uicontract.UiAutomationSnapshot
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxBadge
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxBadgeTone
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxButton
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxButtonVariant
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxChip
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxEmpty
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxFooterNote
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxSection
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxSwitch
import com.labteto.dshmobile.uipack.zhaoxi.theme.ZhaoxiTheme
import com.labteto.dshmobile.uihost.UiSceneRenderScope
import kotlinx.coroutines.launch

/**
 * 自动化与工具/媒体域场景（E8.14 实装）。
 * 启停/删除/立即运行只经 AutomationUiPort.submit；任务详情展示快照投影。
 */

/** automation.tasks / route.tasks — 自动化任务列表。 */
@Composable
fun ZhaoxiAutomationTasksScene(
    automation: AutomationUiPort,
    scope: UiSceneRenderScope,
    onOpenEditor: (String) -> Unit = {},
    onCreate: () -> Unit = { scope.onNavigate(UiSceneId("automation.editor")) },
) {
    val snap by automation.snapshots.collectAsState(initial = automation.snapshot())
    var filter by remember { mutableStateOf("全部") }
    var feedback by remember { mutableStateOf<String?>(null) }
    val filtered = remember(snap.tasks, filter) {
        snap.tasks.filter { task ->
            when (filter) {
                "已计划" -> task.status == com.labteto.dshmobile.automation.AutomationStatus.SCHEDULED
                "运行中" -> task.status == com.labteto.dshmobile.automation.AutomationStatus.RUNNING
                "已暂停" -> task.status == com.labteto.dshmobile.automation.AutomationStatus.PAUSED
                "失败/受阻" -> task.status in setOf(com.labteto.dshmobile.automation.AutomationStatus.FAILED,
                    com.labteto.dshmobile.automation.AutomationStatus.BLOCKED)
                else -> true
            }
        }
    }
    ZxPage("自动化", onBack = { scope.onBack() }) {
        Spacer(Modifier.height(8.dp))
        ZxSection("任务状态")
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("全部", "已计划", "运行中", "已暂停", "失败/受阻").forEach { label ->
                ZxChip(label, filter == label) { filter = label }
            }
        }
        ZxValueRow("当前列表", value = "${filtered.size} / ${snap.tasks.size}")
        feedback?.let { Text(it, color = ZhaoxiTheme.colors.ink2) }
        if (filtered.isEmpty()) ZxEmpty("没有符合条件的任务", "可切换状态筛选或创建新任务")
        filtered.forEach { task ->
            val paused = task.status == com.labteto.dshmobile.automation.AutomationStatus.PAUSED
            val mayRetry = task.status in setOf(
                com.labteto.dshmobile.automation.AutomationStatus.FAILED,
                com.labteto.dshmobile.automation.AutomationStatus.PAUSED,
                com.labteto.dshmobile.automation.AutomationStatus.SCHEDULED,
            )
            val canChangeScheduleState = task.status in setOf(
                com.labteto.dshmobile.automation.AutomationStatus.SCHEDULED,
                com.labteto.dshmobile.automation.AutomationStatus.PAUSED,
            )
            androidx.compose.foundation.layout.Column(
                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                ZxRow(
                    task.prompt.take(100),
                    "${if (task.mode == AutomationMode.CHAT) "聊天" else "工作"} · ${task.status.wireValue} · ${task.scheduleType.name}",
                    onClick = { onOpenEditor(task.id) },
                )
                task.lastError?.takeIf(String::isNotBlank)?.let { error ->
                    com.labteto.dshmobile.uipack.zhaoxi.components.ZxBanner(
                        "上次执行失败：${error.take(160)}",
                        com.labteto.dshmobile.uipack.zhaoxi.components.ZxTone.Warn)
                }
                task.runReceipts.takeLast(3).asReversed().forEachIndexed { index, receipt ->
                    val result = receipt.errorPreview?.take(120)
                        ?: receipt.resultPreview?.take(120) ?: "未提供运行摘要"
                    ZxValueRow(if (index == 0) "最近执行" else "较早执行 ${index + 1}",
                        value = receipt.status.wireValue, sub = result)
                }
                if (task.failureStreak > 0) {
                    val policy = when {
                        task.mode == AutomationMode.CHAT && task.recurringMinutes != null ->
                            "聊天周期任务连续失败 3 次会由后台自动暂停"
                        task.recurringMinutes != null -> "后台会按原周期继续排程"
                        else -> "单次任务失败后需人工重试"
                    }
                    ZxFooterNote("连续失败 ${task.failureStreak} 次 · $policy")
                }
                if (task.status == com.labteto.dshmobile.automation.AutomationStatus.BLOCKED) {
                    ZxFooterNote("任务已阻断，请先检查所属会话、授权或调度配置")
                    ZxChip("检查任务配置", false) { onOpenEditor(task.id) }
                }
                if (mayRetry || canChangeScheduleState) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 10.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (mayRetry) ZxChip(
                            if (task.status == com.labteto.dshmobile.automation.AutomationStatus.FAILED)
                                "重新执行" else "立即运行", false
                        ) {
                            feedback = when (val result = automation.submit(AutomationUiCommand.RunNow(task.id))) {
                                is AutomationUiReceipt.Applied -> "已提交执行；完成情况以运行记录为准"
                                is AutomationUiReceipt.Rejected -> "执行未被接受：${result.reason}"
                                is AutomationUiReceipt.Failed -> result.message ?: "执行失败"
                            }
                        }
                        androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                        Text(when (task.status) {
                            com.labteto.dshmobile.automation.AutomationStatus.FAILED -> "失败待处理"
                            com.labteto.dshmobile.automation.AutomationStatus.PAUSED -> "已暂停"
                            else -> "按计划运行"
                        }, color = ZhaoxiTheme.colors.ink2)
                        if (canChangeScheduleState) ZxSwitch(checked = !paused, onCheckedChange = { enabled ->
                            feedback = when (val result = automation.submit(
                                if (enabled) AutomationUiCommand.Resume(task.id)
                                else AutomationUiCommand.Pause(task.id)
                            )) {
                                is AutomationUiReceipt.Applied -> if (enabled) "任务已恢复" else "任务已暂停"
                                is AutomationUiReceipt.Rejected -> "修改未被接受：${result.reason}"
                                is AutomationUiReceipt.Failed -> result.message ?: "状态修改失败"
                            }
                        }, contentDescription = "${task.prompt.take(20)} · 启用状态")
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        ZxButton("新建自动化", onCreate, Modifier.fillMaxWidth())

    }
}

/** E8.18 form-based creation/editing, using the existing scheduler and immutable generation fence. */
@Composable
fun ZhaoxiAutomationEditorScene(
    automation: AutomationUiPort,
    scope: UiSceneRenderScope,
    editingTaskId: String? = null,
) {
    val co = rememberCoroutineScope()
    val snap by automation.snapshots.collectAsState(initial = automation.snapshot())
    val editing = snap.tasks.firstOrNull { it.id == editingTaskId }
    val chatMode = editing?.mode == AutomationMode.CHAT ||
        (editingTaskId == null && snap.context.canPlanChat)
    val mode = if (chatMode) AutomationMode.CHAT else AutomationMode.WORK
    val formatter = remember {
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).apply {
            isLenient = false
        }
    }
    var prompt by remember(editingTaskId, editing?.id) { mutableStateOf(editing?.prompt.orEmpty()) }
    var selected by remember(editingTaskId, editing?.id) {
        mutableStateOf(editing?.scheduleType?.takeUnless { it == AutomationScheduleType.LEGACY }
            ?: AutomationScheduleType.ONCE)
    }
    var firstRun by remember(editingTaskId, editing?.id) {
        mutableStateOf(formatter.format(java.util.Date(editing?.nextRunAt ?: (System.currentTimeMillis() + 3_600_000L))))
    }
    var interval by remember(editingTaskId, editing?.id) {
        mutableStateOf((editing?.recurringMinutes ?: 60L).toString())
    }
    var silence by remember(editingTaskId, editing?.id) {
        mutableStateOf((editing?.silenceMinutes ?: 60L).toString())
    }
    var windowStart by remember(editingTaskId, editing?.id) {
        mutableStateOf(editing?.windowStartMinuteOfDay?.let { "%02d:%02d".format(it / 60, it % 60) } ?: "09:00")
    }
    var windowEnd by remember(editingTaskId, editing?.id) {
        mutableStateOf(editing?.windowEndMinuteOfDay?.let { "%02d:%02d".format(it / 60, it % 60) } ?: "18:00")
    }
    var notify by remember(editingTaskId, editing?.id) { mutableStateOf(editing?.notify ?: true) }
    var natural by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var firstRunError by remember(editingTaskId) { mutableStateOf<String?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    fun minuteOfDay(value: String): Int? {
        val pieces = value.trim().split(":")
        if (pieces.size != 2) return null
        val h = pieces[0].toIntOrNull() ?: return null
        val m = pieces[1].toIntOrNull() ?: return null
        return if (h in 0..23 && m in 0..59) h * 60 + m else null
    }
    fun showReceipt(receipt: AutomationUiReceipt) {
        feedback = when (receipt) {
            is AutomationUiReceipt.Applied -> "已保存"
            is AutomationUiReceipt.Rejected -> when (receipt.reason) {
                com.labteto.dshmobile.uicontract.UiAutomationRejection.STALE_TASK -> "任务已在其他位置修改，请返回列表重新进入"
                com.labteto.dshmobile.uicontract.UiAutomationRejection.STALE_CONTEXT -> "会话已变化，请切回任务所属会话"
                com.labteto.dshmobile.uicontract.UiAutomationRejection.INVALID_INPUT -> "调度参数无效，请检查时间和间隔"
                com.labteto.dshmobile.uicontract.UiAutomationRejection.MISSING -> "任务已不存在，请返回列表"
                com.labteto.dshmobile.uicontract.UiAutomationRejection.OWNER_REJECTED -> "当前运行状态不允许该修改"
            }
            is AutomationUiReceipt.Failed -> receipt.message ?: "调度请求失败"
        }
    }
    ZxPage(if (editingTaskId == null) "新建自动化" else "编辑自动化", onBack = { scope.onBack() }) {
        Spacer(Modifier.height(8.dp))
        if (editingTaskId != null && editing == null) {
            ZxEmpty("任务已不可用", "原任务可能被删除或当前尚未加载；请返回任务列表")
        } else if (editing?.mode == AutomationMode.CHAT &&
            (snap.context.identity?.sessionId != editing.targetSessionId || !snap.context.canPlanChat)) {
            ZxEmpty("切换到任务所属的聊天会话", "为防止跨会话修改，聊天自动化只能在绑定会话编辑")
        } else {
            ZxSection("目标")
            ZxValueRow("执行模式", value = if (chatMode) "聊天 · 当前会话" else "工作")
            if (chatMode && editingTaskId == null) {
                ZxChip("智能理解时间", natural) { natural = !natural }

            }
            ZxSection("任务内容")
            ZxField(prompt, { prompt = it }, Modifier.fillMaxWidth().height(104.dp),
                placeholder = "描述要完成的任务…")
            if (!natural) {
                ZxSection("调度方式")
                val options = buildList {
                    add(AutomationScheduleType.ONCE to "单次")
                    add(AutomationScheduleType.INTERVAL to "固定间隔")
                    add(AutomationScheduleType.DAILY to "每天")
                    add(AutomationScheduleType.WEEKLY to "每周")
                    add(AutomationScheduleType.MONTHLY to "每月")
                    if (chatMode) {
                        add(AutomationScheduleType.SILENCE to "沉默后")
                        add(AutomationScheduleType.WINDOW to "指定时段")
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    options.forEach { (type, title) ->
                        ZxChip(title, selected == type) { selected = type }
                    }
                }
                if (selected !in setOf(AutomationScheduleType.SILENCE, AutomationScheduleType.WINDOW)) {
                    ZxSection("首次运行时间")
                    ZxField(firstRun, {
                        firstRun = it
                        firstRunError = null
                    }, Modifier.fillMaxWidth(),
                        placeholder = "YYYY-MM-DD HH:mm",
                        error = firstRunError != null)
                    firstRunError?.let { Text(it, color = ZhaoxiTheme.colors.err, fontSize = 12.sp) }
                    ZxButton("选择日期和时间", {
                        // Android native pickers follow the device locale, 12/24h preference,
                        // accessibility, and IME conventions. Keep text input as a fallback.
                        val initial = java.util.Calendar.getInstance()
                        runCatching { formatter.parse(firstRun) }.getOrNull()?.let {
                            initial.time = it
                        }
                        android.app.DatePickerDialog(
                            context,
                            { _, year, month, day ->
                                android.app.TimePickerDialog(
                                    context,
                                    { _, hour, minute ->
                                        firstRun = String.format(
                                            java.util.Locale.US,
                                            "%04d-%02d-%02d %02d:%02d",
                                            year, month + 1, day, hour, minute,
                                        )
                                        firstRunError = null
                                    },
                                    initial.get(java.util.Calendar.HOUR_OF_DAY),
                                    initial.get(java.util.Calendar.MINUTE),
                                    android.text.format.DateFormat.is24HourFormat(context),
                                ).show()
                            },
                            initial.get(java.util.Calendar.YEAR),
                            initial.get(java.util.Calendar.MONTH),
                            initial.get(java.util.Calendar.DAY_OF_MONTH),
                        ).apply {
                            datePicker.minDate = System.currentTimeMillis() - 86_400_000L
                        }.show()
                    }, Modifier.fillMaxWidth(), enabled = !busy)
                    ZxFooterNote("采用设备本地时区；运行时间必须晚于当前时间")
                }
                if (selected == AutomationScheduleType.INTERVAL) {
                    ZxSection("重复间隔（分钟）")
                    ZxField(interval, { interval = it }, Modifier.fillMaxWidth(),
                        placeholder = if (chatMode) "最短 60 分钟" else "最短 15 分钟")
                }
                if (selected == AutomationScheduleType.SILENCE) {
                    ZxSection("沉默触发（分钟）")
                    ZxField(silence, { silence = it }, Modifier.fillMaxWidth(),
                        placeholder = "至少 60 分钟")
                }
                if (selected == AutomationScheduleType.WINDOW) {
                    ZxSection("活动时段")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ZxField(windowStart, { windowStart = it }, Modifier.weight(1f),
                            placeholder = "开始 HH:mm")
                        ZxField(windowEnd, { windowEnd = it }, Modifier.weight(1f),
                            placeholder = "结束 HH:mm")
                    }
                }
            }
            ZxSection("通知与失败处理")
            ZxSwitchRow("运行通知", "执行结束后根据系统通知权限提醒", notify) { notify = it }
            if (editing != null) {
                ZxValueRow("连续失败", value = "${editing.failureStreak} 次")
                editing.lastError?.takeIf(String::isNotBlank)?.let {
                    ZxFooterNote("最近失败：${it.take(160)}")
                }
                ZxFooterNote(
                    if (editing.mode == AutomationMode.CHAT && editing.recurringMinutes != null)
                        "当前策略：聊天周期任务连续失败 3 次后自动暂停，任务列表可手动重新执行或恢复。"
                    else if (editing.recurringMinutes != null)
                        "当前策略：失败后仍按原定周期排程，可在任务列表查看回执并立即运行。"
                    else "当前策略：单次任务失败后保留错误记录，可从任务列表手动重新执行。"
                )
                ZxFooterNote("以上为后台已生效策略；目前不支持为单个任务单独覆盖。")
            }
            feedback?.let { Text(it, color = ZhaoxiTheme.colors.ink2) }
            Spacer(Modifier.height(12.dp))
            ZxButton(if (editingTaskId == null) "创建任务" else "保存修改", {
                if (prompt.isBlank()) {
                    feedback = "请填写任务内容"
                } else if (natural) {
                    val identity = snap.context.identity
                    if (!snap.context.canPlanChat || identity == null) {
                        feedback = "请切换到有效的单人聊天会话"
                    } else {
                        co.launch {
                            busy = true
                            try {
                                showReceipt(automation.planAndSave(PlanChatAutomationCommand(
                                    identity, prompt, editingTaskId)))
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (failure: Exception) {
                                feedback = failure.message ?: "规划失败"
                            } finally {
                                busy = false
                            }
                        }
                    }
                } else {
                    val date = runCatching { formatter.parse(firstRun)?.time }.getOrNull()
                    val duration = interval.toLongOrNull()
                    val silenceDuration = silence.toLongOrNull()
                    val start = minuteOfDay(windowStart)
                    val end = minuteOfDay(windowEnd)
                    val recurring = when (selected) {
                        AutomationScheduleType.INTERVAL -> duration
                        AutomationScheduleType.DAILY -> 1_440L
                        AutomationScheduleType.WEEKLY -> 10_080L
                        AutomationScheduleType.MONTHLY -> 43_200L
                        else -> null
                    }
                    val timed = selected !in setOf(AutomationScheduleType.SILENCE, AutomationScheduleType.WINDOW)
                    val minGap = if (chatMode) 60L else 15L
                    firstRunError = if (timed && (date == null || date <= System.currentTimeMillis()))
                        "请选择晚于当前时间的有效日期和时间" else null
                    feedback = when {
                        timed && firstRunError != null ->
                            "请输入晚于当前时间的有效本地日期"
                        selected == AutomationScheduleType.INTERVAL && (duration == null || duration < minGap) ->
                            "间隔不得小于 ${minGap} 分钟"
                        selected == AutomationScheduleType.SILENCE && (silenceDuration == null || silenceDuration < 60L) ->
                            "沉默触发最短为 60 分钟"
                        selected == AutomationScheduleType.WINDOW && (start == null || end == null || start == end) ->
                            "请输入两个不同的 HH:mm 时间"
                        else -> null
                    }
                    if (feedback == null) {
                        val schedule = UiAutomationSchedule(
                            prompt = prompt.trim(),
                            firstRunAt = date ?: System.currentTimeMillis() + 60_000L,
                            recurringMinutes = recurring,
                            mode = mode,
                            scheduleType = selected,
                            silenceMinutes = if (selected == AutomationScheduleType.SILENCE) silenceDuration else null,
                            windowStartMinuteOfDay = if (selected == AutomationScheduleType.WINDOW) start else null,
                            windowEndMinuteOfDay = if (selected == AutomationScheduleType.WINDOW) end else null,
                            notify = notify,
                        )
                        busy = true
                        try {
                            showReceipt(automation.submit(if (editing == null) {
                                AutomationUiCommand.Create(snap.context.identity, schedule)
                            } else {
                                AutomationUiCommand.Update(editing.id, editing.scheduleGeneration, schedule)
                            }))
                        } catch (failure: Exception) {
                            feedback = failure.message ?: "保存未完成，请确认任务状态后重试"
                        } finally {
                            busy = false
                        }
                    }
                }
            }, Modifier.fillMaxWidth(), enabled = !busy && prompt.isNotBlank(), loading = busy)

        }
    }
}

/** Current-chat automation page backed by the real scheduler rather than sample event cards. */
@Composable
fun ZhaoxiPersonaInteractionScene(
    automation: AutomationUiPort,
    scope: UiSceneRenderScope,
    onCreate: () -> Unit,
    onEdit: (String) -> Unit,
) {
    val snap by automation.snapshots.collectAsState(initial = automation.snapshot())
    val currentId = snap.context.identity?.sessionId
    val tasks = snap.tasks.filter {
        it.mode == AutomationMode.CHAT && it.targetSessionId == currentId
    }
    ZxPage("聊天自动化", onBack = { scope.onBack() }) {
        Spacer(Modifier.height(8.dp))
        if (!snap.context.canPlanChat || currentId == null) {
            ZxEmpty("请进入单人聊天", "选择会话后可设置互动")
        } else {
            ZxSection("互动任务")
            if (tasks.isEmpty()) ZxEmpty("暂无互动任务", "可以为当前聊天创建提醒或互动")
            tasks.forEach { task ->
                ZxRow(task.prompt.take(100),
                    "${task.status.wireValue} · ${task.scheduleType.name}",
                    onClick = { onEdit(task.id) })
            }
            ZxButton("新建互动", onCreate, Modifier.fillMaxWidth())
        }
    }
}

/** 按 E8.18 独立场景分派工具中心、MCP、插件和 GitHub；所有状态来自 ToolsUiPort。 */
@Composable
fun ZhaoxiToolsScene(
    tools: com.labteto.dshmobile.uicontract.ToolsUiPort,
    scope: UiSceneRenderScope,
    destination: String = "route.tools",
) {
    val co = rememberCoroutineScope()
    var feedback by remember { mutableStateOf<String?>(null) }
    var endpoint by remember { mutableStateOf("") }
    var serverId by remember { mutableStateOf("") }
    var refresh by remember { mutableStateOf(0) }
    val catalogue = remember(tools) { tools.capabilityCatalogue() }
    val plugins = remember(tools, refresh) { tools.pluginLifecycleSnapshots() }
    val installedIds = remember(tools, refresh) { tools.installedPluginIds() }
    val skills by produceState(initialValue = emptyList<com.labteto.dshmobile.uicontract.UiSkillSnapshot>(), tools) {
        value = tools.installedSkills()
    }
    val servers by produceState(initialValue = emptyList<com.labteto.dshmobile.uicontract.UiMcpServerSnapshot>(), tools, refresh) {
        value = tools.servers()
    }
    val githubConfigured by produceState(initialValue = false, tools, refresh) {
        value = tools.githubConfigured()
    }
    val heading = when (destination) {
        "tools.mcp" -> "MCP 连接"
        "tools.plugins" -> "插件管理"
        "tools.github" -> "GitHub"
        "work.capabilities" -> "能力目录"
        else -> "工具中心"
    }
    ZxPage(heading, onBack = { scope.onBack() }) {
        Spacer(Modifier.height(8.dp))
        feedback?.let { ZxSection("操作回执"); Text(it, color = ZhaoxiTheme.colors.ink2) }
        when (destination) {
            "tools.mcp" -> {
                ZxSection("连接方式 · HTTP")
                com.labteto.dshmobile.uipack.zhaoxi.components.ZxField(
                    serverId, { serverId = it }, Modifier.fillMaxWidth(), placeholder = "服务器名称")
                com.labteto.dshmobile.uipack.zhaoxi.components.ZxField(
                    endpoint, { endpoint = it }, Modifier.fillMaxWidth(), placeholder = "HTTP 地址")
                ZxButton("连接服务器", { co.launch {
                    feedback = tools.submit(
                        com.labteto.dshmobile.uicontract.ToolsUiCommand.ConnectHttp(serverId, endpoint)
                    ).toString()
                    refresh++
                } }, enabled = serverId.isNotBlank() && endpoint.isNotBlank())
                ZxSection("已登记服务器")
                if (servers.isEmpty()) ZxEmpty("尚未添加 MCP 服务器", "填写名称和地址后连接")
                servers.forEach { server ->
                    ZxRow(server.id, "${server.transport} · ${server.connection} · ${server.tools.size} 个工具")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ZxChip("检查连接", false) { co.launch {
                            feedback = tools.checkServerHealth(server.id).toString()
                            refresh++
                        } }
                        ZxChip("断开", false) { co.launch {
                            feedback = tools.submit(
                                com.labteto.dshmobile.uicontract.ToolsUiCommand.Disconnect(server.id)
                            ).toString()
                            refresh++
                        } }
                    }
                }

            }
            "tools.plugins" -> {
                ZxSection("已安装插件")
                ZxValueRow("已安装数量", value = installedIds.size.toString())
                if (installedIds.isEmpty() && plugins.isEmpty()) ZxEmpty("尚无已安装插件", "插件由当前工具管理能力提供")
                installedIds.forEach { id ->
                    val lifecycle = plugins.firstOrNull { it.id == id }
                    ZxRow(id, lifecycle?.state?.name ?: "已安装 · 未提供生命周期状态")
                }
                plugins.filter { it.id !in installedIds }.forEach { item ->
                    ZxRow(item.id, item.state.name)
                }
                plugins.filter { it.state == com.labteto.dshmobile.uicontract.UiPluginLifecycleState.FAILED }
                    .forEach { item ->
                        ZxButton("恢复 ${item.id}", { co.launch {
                            feedback = tools.submit(
                                com.labteto.dshmobile.uicontract.ToolsUiCommand.RecoverFailedPlugin(item.id)
                            ).toString()
                            refresh++
                        } })
                    }

            }
            "tools.github" -> {
                ZxSection("连接状态")
                ZxValueRow("GitHub", value = if (githubConfigured) "已配置" else "未配置")
                ZxSection("可用能力")
                catalogue.filter { it.family.contains("github", ignoreCase = true) }
                    .forEach { cap -> ZxRow(cap.name, "${cap.access} · ${cap.approval}") }
                if (!githubConfigured) ZxEmpty("未连接 GitHub", "请先完成授权")
            }
            "work.capabilities" -> {
                val grouped = catalogue.groupBy { it.family }
                if (grouped.isEmpty()) ZxEmpty("暂无能力", "已注册的能力会在这里出现")
                grouped.forEach { (group, items) ->
                    ZxSection(group)
                    items.forEach { cap ->
                        ZxRow(cap.name, "${cap.access} · ${cap.approval}")
                    }
                }
            }
            else -> {
                ZxSection("可用能力")
                catalogue.groupBy { it.family }.forEach { (family, items) ->
                    ZxRow(family, "${items.size} 项已登记能力",
                        onClick = { scope.onNavigate(UiSceneId("work.capabilities")) })
                }
                ZxSection("技能")
                if (skills.isEmpty()) ZxRow("暂无已安装技能", "当前未读取到技能")
                skills.forEach { item -> ZxRow(item.displayName, item.description) }
                ZxSection("连接与扩展")
                ZxRow("MCP 服务器", "${servers.size} 个已登记连接",
                    onClick = { scope.onNavigate(UiSceneId("tools.mcp")) })
                ZxRow("插件", "${installedIds.size} 个已安装插件",
                    onClick = { scope.onNavigate(UiSceneId("tools.plugins")) })
                ZxRow("GitHub", if (githubConfigured) "已配置" else "未配置",
                    onClick = { scope.onNavigate(UiSceneId("tools.github")) })

            }
        }
    }
}

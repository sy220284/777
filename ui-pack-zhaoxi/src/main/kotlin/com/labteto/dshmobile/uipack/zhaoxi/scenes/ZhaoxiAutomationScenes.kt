package com.labteto.dshmobile.uipack.zhaoxi.scenes

import java.util.Calendar
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import android.text.format.DateFormat
import android.app.TimePickerDialog
import android.app.DatePickerDialog
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import com.labteto.dshmobile.automation.AutomationUiDefaults
import com.labteto.dshmobile.automation.AutomationStatus
import com.labteto.dshmobile.automation.AutomationMode
import com.labteto.dshmobile.automation.AutomationScheduleType
import com.labteto.dshmobile.uicontract.UiAutomationRejection
import com.labteto.dshmobile.uicontract.AutomationUiReceipt
import com.labteto.dshmobile.uicontract.UiAutomationSchedule
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxField
import kotlinx.coroutines.CancellationException
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.labteto.dshmobile.uicontract.ToolsUiCommand
import com.labteto.dshmobile.uicontract.ToolsUiReceipt
import com.labteto.dshmobile.uicontract.UiToolError
import com.labteto.dshmobile.uicontract.UiMcpHealth
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxDialog
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.saveable.rememberSaveable
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

/** Admission is not execution completion; never display Kotlin enum names as errors. */
internal fun zxAutomationReceiptText(action: String, receipt: AutomationUiReceipt): String = when (receipt) {
    is AutomationUiReceipt.Applied -> if (action == "立即运行") "运行请求已提交" else "${action}已提交"
    is AutomationUiReceipt.Rejected -> when (receipt.reason) {
        UiAutomationRejection.STALE_CONTEXT -> "会话已变化，请返回刷新"
        UiAutomationRejection.STALE_TASK -> "任务已更新，请重新载入"
        UiAutomationRejection.INVALID_INPUT -> "输入不符合调度条件"
        UiAutomationRejection.MISSING -> "任务已不存在"
        UiAutomationRejection.OWNER_REJECTED -> "调度操作未被接受"
    }
    is AutomationUiReceipt.Failed -> receipt.message ?: "${action}失败"
}

/**
 * 自动化与工具/媒体域场景（E8.14 实装）。
 * 启停/删除/立即运行只经 AutomationUiPort.submit；任务详情展示快照投影。
 */

/** 列表检索只过滤已有任务，不额外查询或改动调度事实。 */
internal fun zxAutomationTaskMatches(
    query: String, prompt: String, actorName: String?, scheduleName: String,
): Boolean {
    val needle = query.trim()
    return needle.isEmpty() ||
        prompt.contains(needle, ignoreCase = true) ||
        actorName.orEmpty().contains(needle, ignoreCase = true) ||
        scheduleName.contains(needle, ignoreCase = true)
}

/** automation.tasks / route.tasks — 自动化任务列表。 */
@Composable
fun ZhaoxiAutomationTasksScene(
    automation: AutomationUiPort,
    scope: UiSceneRenderScope,
    onOpenEditor: (String) -> Unit = {},
    onCreate: () -> Unit = { scope.onNavigate(UiSceneId("automation.editor")) },
) {
    val snap by automation.snapshots.collectAsState(initial = automation.snapshot())
    var filter by rememberSaveable { mutableStateOf("全部") }
    var taskQuery by rememberSaveable { mutableStateOf("") }
    var modeFilter by rememberSaveable { mutableStateOf("全部模式") }
    var deleteTaskId by remember { mutableStateOf<String?>(null) }
    var feedback by remember { mutableStateOf<String?>(null) }
    val filtered = remember(snap.tasks, filter, taskQuery, modeFilter) {
        snap.tasks.filter { task ->
            val matchingStatus = when (filter) {
                "已计划" -> task.status == com.labteto.dshmobile.automation.AutomationStatus.SCHEDULED
                "运行中" -> task.status == com.labteto.dshmobile.automation.AutomationStatus.RUNNING
                "已暂停" -> task.status == com.labteto.dshmobile.automation.AutomationStatus.PAUSED
                "失败/受阻" -> task.status in setOf(com.labteto.dshmobile.automation.AutomationStatus.FAILED,
                    com.labteto.dshmobile.automation.AutomationStatus.BLOCKED)
                else -> true
            }
            matchingStatus &&
                (modeFilter == "全部模式" ||
                    (modeFilter == "聊天" && task.mode == AutomationMode.CHAT) ||
                    (modeFilter == "工作" && task.mode == AutomationMode.WORK)) &&
                zxAutomationTaskMatches(taskQuery, task.prompt, task.actorName,
                    task.scheduleType.zxAutomationScheduleLabel())
        }
    }
    ZxPage("自动化", onBack = { scope.onBack() }) {
        Spacer(Modifier.height(8.dp))
        ZxField(taskQuery, { taskQuery = it }, Modifier.fillMaxWidth(),
            placeholder = "搜索任务内容、人物或执行周期")
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("全部模式", "聊天", "工作").forEach { label ->
                ZxChip(label, modeFilter == label) { modeFilter = label }
            }
        }
        ZxSection("任务状态")
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("全部", "已计划", "运行中", "已暂停", "失败/受阻").forEach { label ->
                ZxChip(label, filter == label) { filter = label }
            }
        }
        ZxValueRow("当前列表", value = "${filtered.size} / ${snap.tasks.size}")
        feedback?.let { Text(it, color = ZhaoxiTheme.colors.ink2) }
        if (snap.tasks.isEmpty()) ZxEmpty("暂无自动化任务", "可以创建第一个真实任务")
        else if (filtered.isEmpty()) ZxEmpty("没有符合条件的任务", "可调整搜索、模式或状态筛选")
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
                    "${if (task.mode == AutomationMode.CHAT) "聊天" else "工作"} · ${task.status.zxAutomationStatusLabel()} · ${task.scheduleType.zxAutomationScheduleLabel()}",
                    onClick = { onOpenEditor(task.id) },
                )
                if (task.nextRunAt > 0L) {
                    val scheduledTime = java.text.DateFormat.getDateTimeInstance(
                        java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT,
                    ).format(java.util.Date(task.nextRunAt))
                    val timeLabel = when (task.status) {
                        AutomationStatus.SCHEDULED -> "下次执行"
                        AutomationStatus.PAUSED -> "暂停前计划时间"
                        AutomationStatus.RUNNING -> "本轮计划时间"
                        else -> "计划时间"
                    }
                    ZxValueRow(timeLabel, value = scheduledTime)
                }
                task.lastError?.takeIf(String::isNotBlank)?.let { error ->
                    com.labteto.dshmobile.uipack.zhaoxi.components.ZxBanner(
                        "上次执行失败：${error.take(160)}",
                        com.labteto.dshmobile.uipack.zhaoxi.components.ZxTone.Warn)
                }
                task.runReceipts.takeLast(3).asReversed().forEachIndexed { index, receipt ->
                    val result = receipt.errorPreview?.take(120)
                        ?: receipt.resultPreview?.take(120) ?: "未提供运行摘要"
                    ZxValueRow(if (index == 0) "最近执行" else "较早执行 ${index + 1}",
                        value = receipt.status.zxAutomationStatusLabel(), sub = result)
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
                ZxChip("删除任务", false) { deleteTaskId = task.id }
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
                            feedback = zxAutomationReceiptText("立即运行", automation.submit(AutomationUiCommand.RunNow(task.id)))
                        }
                        androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                        Text(when (task.status) {
                            com.labteto.dshmobile.automation.AutomationStatus.FAILED -> "失败待处理"
                            com.labteto.dshmobile.automation.AutomationStatus.PAUSED -> "已暂停"
                            else -> "按计划运行"
                        }, color = ZhaoxiTheme.colors.ink2)
                        if (canChangeScheduleState) ZxSwitch(checked = !paused, onCheckedChange = { enabled ->
                            feedback = zxAutomationReceiptText(if (enabled) "启用任务" else "暂停任务", automation.submit(
                                if (enabled) AutomationUiCommand.Resume(task.id)
                                else AutomationUiCommand.Pause(task.id)
                            ))
                        }, contentDescription = "${task.prompt.take(20)} · 启用状态")
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        ZxButton("新建自动化", onCreate, Modifier.fillMaxWidth())
        deleteTaskId?.let { id ->
            com.labteto.dshmobile.uipack.zhaoxi.components.ZxDialog(
                "删除自动化任务？", "删除后不再调度此任务，已产生的会话和结果会保留。", "删除任务",
                {
                    val result = automation.submit(AutomationUiCommand.Delete(id))
                    feedback = zxAutomationReceiptText("删除任务", result)
                    if (result is AutomationUiReceipt.Applied) deleteTaskId = null
                },
                { deleteTaskId = null },
            )
        }

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
    // Declare before the native picker so a newly chosen valid time clears stale errors.
    var firstRunError by remember(editingTaskId) { mutableStateOf<String?>(null) }
    val androidContext = LocalContext.current
    fun chooseFirstRun() {
        val calendar = Calendar.getInstance().apply {
            timeInMillis = runCatching { formatter.parse(firstRun)?.time }.getOrNull()
                ?: (System.currentTimeMillis() + 3_600_000L)
        }
        DatePickerDialog(
            androidContext,
            { _, year, month, day ->
                calendar.set(Calendar.YEAR, year)
                calendar.set(Calendar.MONTH, month)
                calendar.set(Calendar.DAY_OF_MONTH, day)
                TimePickerDialog(androidContext, { _, hour, minute ->
                    calendar.set(Calendar.HOUR_OF_DAY, hour)
                    calendar.set(Calendar.MINUTE, minute)
                    calendar.set(Calendar.SECOND, 0)
                    calendar.set(Calendar.MILLISECOND, 0)
                    firstRun = formatter.format(calendar.time)
                    firstRunError = null
                }, calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE),
                    DateFormat.is24HourFormat(androidContext)).show()
            },
            calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH),
        ).apply { datePicker.minDate = System.currentTimeMillis() - 86_400_000L }.show()
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
    var quietEnabled by remember(editingTaskId, editing?.id) { mutableStateOf(editing?.quietHoursEnabled ?: false) }
    var quietStart by remember(editingTaskId, editing?.id) {
        mutableStateOf("%02d:%02d".format(editing?.quietStartHour ?: AutomationUiDefaults.DEFAULT_QUIET_START_HOUR,
            editing?.quietStartMinute ?: AutomationUiDefaults.DEFAULT_QUIET_START_MINUTE))
    }
    var quietEnd by remember(editingTaskId, editing?.id) {
        mutableStateOf("%02d:%02d".format(editing?.quietEndHour ?: AutomationUiDefaults.DEFAULT_QUIET_END_HOUR,
            editing?.quietEndMinute ?: AutomationUiDefaults.DEFAULT_QUIET_END_MINUTE))
    }
    var proactiveGapInput by remember(editingTaskId, editing?.id) {
        mutableStateOf((editing?.proactiveMinGapMinutes ?: AutomationUiDefaults.DEFAULT_PROACTIVE_MIN_GAP_MINUTES).toString())
    }
    var maxUnanswered by remember(editingTaskId, editing?.id) {
        mutableStateOf((editing?.proactiveMaxUnanswered ?: AutomationUiDefaults.DEFAULT_PROACTIVE_MAX_UNANSWERED).toString())
    }
    var natural by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }
    fun minuteOfDay(value: String): Int? {
        val pieces = value.trim().split(":")
        if (pieces.size != 2) return null
        val h = pieces[0].toIntOrNull() ?: return null
        val m = pieces[1].toIntOrNull() ?: return null
        return if (h in 0..23 && m in 0..59) h * 60 + m else null
    }
    fun showReceipt(receipt: AutomationUiReceipt) {
        feedback = zxAutomationReceiptText("任务", receipt)
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
                    }, Modifier.fillMaxWidth(), placeholder = "YYYY-MM-DD HH:mm",
                        error = firstRunError != null, errorMessage = firstRunError)
                    ZxButton("选择日期与时间", { chooseFirstRun() },
                        Modifier.fillMaxWidth(), variant = ZxButtonVariant.Outline)
                    ZxFooterNote("设备本地时间")
                }
                if (selected == AutomationScheduleType.INTERVAL) {
                    ZxSection("重复间隔（分钟）")
                    ZxField(interval, { interval = it }, Modifier.fillMaxWidth(),
                        placeholder = if (chatMode) "最短 60 分钟" else "最短 15 分钟",
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
                if (selected == AutomationScheduleType.SILENCE) {
                    ZxSection("沉默触发（分钟）")
                    ZxField(silence, { silence = it }, Modifier.fillMaxWidth(),
                        placeholder = "至少 60 分钟",
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
                if (selected == AutomationScheduleType.WINDOW) {
                    ZxSection("活动时段")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ZxField(windowStart, { windowStart = it }, Modifier.weight(1f),
                            placeholder = "开始 HH:mm")
                        ZxField(windowEnd, { windowEnd = it }, Modifier.weight(1f),
                            placeholder = "结束 HH:mm")
                    }
                }
            }
            if (!natural) {
                ZxSection("通知与互动")
                ZxSettingsGroup {
                    ZxSwitchRow("运行通知", "完成与需要处理时提醒", notify) { notify = it }
                    if (chatMode) {
                        ZxSwitchRow("免打扰时段", "可设置跨夜时段", quietEnabled) { quietEnabled = it }
                        if (quietEnabled) {
                            ZxField(quietStart, { quietStart = it }, Modifier.fillMaxWidth(), fieldLabel = "免打扰开始", placeholder = "23:00")
                            ZxField(quietEnd, { quietEnd = it }, Modifier.fillMaxWidth(), fieldLabel = "免打扰结束", placeholder = "07:00")
                        }
                        ZxField(proactiveGapInput, { proactiveGapInput = it }, Modifier.fillMaxWidth(), fieldLabel = "最短互动间隔（至少60分钟）",
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        ZxField(maxUnanswered, { maxUnanswered = it }, Modifier.fillMaxWidth(), fieldLabel = "连续未回复上限（1–5次）",
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    }
                }
            } else ZxFooterNote("智能规划会确定任务内容和时间；创建后可在表单中调整通知与互动规则。")
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
                    firstRunError = if (timed && (date == null || date <= System.currentTimeMillis()))
                        "请选择有效且晚于当前时间的日期" else null
                    val minGap = if (chatMode) 60L else 15L
                    feedback = when {
                        chatMode && quietEnabled && zxQuietHoursError(quietStart, quietEnd) != null -> zxQuietHoursError(quietStart, quietEnd)
                        chatMode && (proactiveGapInput.toLongOrNull() == null || (proactiveGapInput.toLongOrNull() ?: 0) < 60L) -> "互动间隔至少为60分钟"
                        chatMode && maxUnanswered.toIntOrNull() !in 1..5 -> "连续未回复上限应为1–5次"
                        timed && firstRunError != null -> firstRunError
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
                            quietHoursEnabled = if (chatMode) quietEnabled else editing?.quietHoursEnabled ?: false,
                            quietStartHour = if (chatMode && quietEnabled) requireNotNull(minuteOfDay(quietStart)) / 60 else editing?.quietStartHour ?: AutomationUiDefaults.DEFAULT_QUIET_START_HOUR,
                            quietStartMinute = if (chatMode && quietEnabled) requireNotNull(minuteOfDay(quietStart)) % 60 else editing?.quietStartMinute ?: AutomationUiDefaults.DEFAULT_QUIET_START_MINUTE,
                            quietEndHour = if (chatMode && quietEnabled) requireNotNull(minuteOfDay(quietEnd)) / 60 else editing?.quietEndHour ?: AutomationUiDefaults.DEFAULT_QUIET_END_HOUR,
                            quietEndMinute = if (chatMode && quietEnabled) requireNotNull(minuteOfDay(quietEnd)) % 60 else editing?.quietEndMinute ?: AutomationUiDefaults.DEFAULT_QUIET_END_MINUTE,
                            proactiveMinGapMinutes = if (chatMode) requireNotNull(proactiveGapInput.toLongOrNull()) else editing?.proactiveMinGapMinutes ?: AutomationUiDefaults.DEFAULT_PROACTIVE_MIN_GAP_MINUTES,
                            proactiveMaxUnanswered = if (chatMode) requireNotNull(maxUnanswered.toIntOrNull()) else editing?.proactiveMaxUnanswered ?: AutomationUiDefaults.DEFAULT_PROACTIVE_MAX_UNANSWERED,
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
                            feedback = failure.message ?: "保存未完成，请先确认任务状态"
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
                    "${task.status.zxAutomationStatusLabel()} · ${task.scheduleType.zxAutomationScheduleLabel()}",
                    onClick = { onEdit(task.id) })
            }
            ZxButton("新建互动", onCreate, Modifier.fillMaxWidth())
        }
    }
}

/** Map management facts to human-readable feedback; Applied never means tools were authorized or executed. */
internal fun zxToolReceiptText(receipt: ToolsUiReceipt): String = when (receipt) {
    is ToolsUiReceipt.Applied -> "设置已保存"
    is ToolsUiReceipt.Failed -> when (receipt.reason) {
        UiToolError.DNS -> "无法解析服务器地址"
        UiToolError.TIMEOUT -> "连接超时，请重试"
        UiToolError.UNREACHABLE -> "无法连接服务器"
        UiToolError.PERMISSION -> "缺少连接权限，请检查授权"
        UiToolError.CONFIGURATION -> "连接配置无效"
        UiToolError.UNKNOWN -> "操作失败，请检查配置后重试"
    }
}

/** A responded ping proves only this one health check, not access to a tool. */
internal fun zxMcpHealthText(health: UiMcpHealth): String = when (health) {
    UiMcpHealth.RESPONDED -> "连接已响应"
    UiMcpHealth.BUSY -> "服务器忙，请稍后检查"
    UiMcpHealth.NOT_CONNECTED -> "尚未建立连接"
    UiMcpHealth.UNCONFIRMED -> "无法确认当前连接状态"
    UiMcpHealth.UNSUPPORTED -> "不支持连接检查"
}

/** 分类标签不改变工具 ID、授权规则或调用方式。 */
internal fun zxToolFamilyLabel(name: String): String = when (name.lowercase()) {
    "android" -> "设备操作"
    "runtime" -> "运行环境"
    "lsp" -> "代码分析"
    "agent" -> "智能体"
    "network" -> "网络"
    "media" -> "媒体创作"
    "files" -> "文件"
    "tasks" -> "任务"
    else -> name
}

/** Preserve argv exactly; never split a command string or run it through an extra shell. */
internal fun zxMcpStdioCommand(value: String): List<String>? {
    val array = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(value) }
        .getOrNull() as? kotlinx.serialization.json.JsonArray ?: return null
    val args = array.map { element ->
        val text = element as? kotlinx.serialization.json.JsonPrimitive ?: return null
        if (!text.isString) return null
        text.content
    }
    return args.takeIf { it.isNotEmpty() && it.first().isNotBlank() }
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
    var transport by rememberSaveable { mutableStateOf("http") }
    var stdioCommand by remember { mutableStateOf("") }
    var workingDirectory by remember { mutableStateOf("") }
    val argv = remember(stdioCommand) { zxMcpStdioCommand(stdioCommand) }
    var expandedServer by rememberSaveable { mutableStateOf<String?>(null) }
    var githubToken by remember { mutableStateOf("") }
    var pendingClearGitHub by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var refresh by remember { mutableStateOf(0) }
    var expandedFamily by rememberSaveable { mutableStateOf<String?>(null) }
    var skillQuery by rememberSaveable { mutableStateOf("") }
    var pluginQuery by rememberSaveable { mutableStateOf("") }
    fun submitConfiguration(command: ToolsUiCommand, clearTokenOnSuccess: Boolean = false) {
        if (busy) return
        busy = true
        co.launch {
            try {
                val receipt = tools.submit(command)
                feedback = zxToolReceiptText(receipt)
                if (clearTokenOnSuccess && receipt is ToolsUiReceipt.Applied) githubToken = ""
                refresh++
            } catch (cancelled: CancellationException) {
                throw cancelled
            } finally {
                busy = false
            }
        }
    }
    val catalogue = remember(tools) { tools.capabilityCatalogue() }
    val plugins = remember(tools, refresh) { tools.pluginLifecycleSnapshots() }
    val installedIds = remember(tools, refresh) { tools.installedPluginIds() }
    val skills by produceState<List<com.labteto.dshmobile.uicontract.UiSkillSnapshot>?>(null, tools) {
        value = tools.installedSkills()
    }
    androidx.compose.runtime.LaunchedEffect(tools) {
        tools.serverChanges().collect { refresh++ }
    }
    val servers by produceState(initialValue = emptyList<com.labteto.dshmobile.uicontract.UiMcpServerSnapshot>(), tools, refresh) {
        value = try { tools.servers() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { feedback = "服务器列表读取失败，请刷新后重试"; emptyList() }
    }
    val githubConfigured by produceState(initialValue = false, tools, refresh) {
        value = tools.githubConfigured()
    }
    val heading = when (destination) {
        "tools.mcp" -> "MCP 连接"
        "tools.plugins" -> "插件与技能管理"
        "tools.github" -> "GitHub"
        "work.capabilities" -> "能力目录"
        else -> "工具中心"
    }
    ZxPage(heading, onBack = { scope.onBack() }) {
        Spacer(Modifier.height(8.dp))
        feedback?.let { ZxSection("操作回执"); Text(it, color = ZhaoxiTheme.colors.ink2) }
        when (destination) {
            "tools.mcp" -> {
                ZxSection("添加连接")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ZxChip("HTTP 服务", transport == "http") { transport = "http" }
                    ZxChip("本机 stdio", transport == "stdio") { transport = "stdio" }
                    ZxChip("刷新", false) { refresh++ }
                }
                ZxField(serverId, { serverId = it }, Modifier.fillMaxWidth(), placeholder = "服务器名称")
                if (transport == "http") {
                    ZxField(endpoint, { endpoint = it }, Modifier.fillMaxWidth(), placeholder = "HTTP 地址")
                } else {
                    ZxFooterNote("启动本机 MCP 服务进程。命令及每个参数分别填写在 JSON 字符串数组中；工作目录可选。")
                    ZxField(stdioCommand, { stdioCommand = it }, Modifier.fillMaxWidth(),
                        fieldLabel = "本机启动命令", placeholder = "[\"node\", \"/path/to/server.js\"]",
                        error = argv == null, errorMessage = if (argv == null) "请输入非空的命令字符串数组" else null)
                    ZxField(workingDirectory, { workingDirectory = it }, Modifier.fillMaxWidth(),
                        placeholder = "工作目录（可选）")
                }
                ZxButton("连接服务器", {
                    val command = if (transport == "http")
                        ToolsUiCommand.ConnectHttp(serverId.trim(), endpoint.trim())
                    else argv?.let { ToolsUiCommand.ConnectStdio(serverId.trim(), it,
                        workingDirectory.trim().takeIf(String::isNotBlank)) }
                    if (command != null) submitConfiguration(command)
                }, enabled = !busy && serverId.isNotBlank() &&
                    (if (transport == "stdio") argv != null else
                        endpoint.trim().startsWith("https://") || endpoint.trim().startsWith("http://")),
                    loading = busy)
                ZxSection("已登记服务器")
                if (servers.isEmpty()) ZxEmpty("尚未添加 MCP 服务器", "填写名称和地址后连接")
                servers.forEach { server ->
                    ZxRow(server.id, "${server.transport} · ${server.connection} · ${server.tools.size} 个工具",
                        onClick = { expandedServer = if (expandedServer == server.id) null else server.id })
                    if (expandedServer == server.id) {
                        ZxSection("已登记工具 · ${server.tools.size}")
                        if (server.tools.isEmpty())
                            ZxFooterNote("当前服务没有上报工具；检查连接并刷新后查看。")
                        server.tools.forEach { name ->
                            val registered = catalogue.firstOrNull { it.name == name }
                            ZxRow(name,
                                registered?.let {
                                    "权限 ${it.access} · 审批 ${it.approval}" +
                                        if (it.requirements.isEmpty()) "" else " · ${it.requirements.joinToString("、").take(110)}"
                                } ?: "仅服务端上报名称，应用尚未核实其调用权限",
                                showChevron = false)
                        }
                        ZxFooterNote("这里只显示工具与授权要求；任何执行仍需真实工作任务和工具审批。")
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ZxChip("检查连接", false) { if (!busy) co.launch {
                            busy = true
                            try { feedback = zxMcpHealthText(tools.checkServerHealth(server.id)); refresh++ }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (failure: Exception) { feedback = "检查失败，请稍后重试" }
                            finally { busy = false }
                        } }
                        ZxChip("断开", false) { submitConfiguration(ToolsUiCommand.Disconnect(server.id)) }
                    }
                }

            }
            "tools.plugins" -> {
                ZxSection("已安装插件")
                ZxValueRow("已安装数量", value = installedIds.size.toString())
                ZxField(pluginQuery, { pluginQuery = it }, Modifier.fillMaxWidth(),
                    placeholder = "搜索插件名称或运行状态")
                val matchingInstalled = installedIds.filter { id ->
                    val state = plugins.firstOrNull { it.id == id }?.state?.name.orEmpty()
                    pluginQuery.isBlank() || id.contains(pluginQuery.trim(), ignoreCase = true) ||
                        state.contains(pluginQuery.trim(), ignoreCase = true)
                }
                val matchingOthers = plugins.filter { it.id !in installedIds &&
                    (pluginQuery.isBlank() ||
                        it.id.contains(pluginQuery.trim(), ignoreCase = true) ||
                        it.state.name.contains(pluginQuery.trim(), ignoreCase = true)) }
                if (installedIds.isEmpty() && plugins.isEmpty())
                    ZxEmpty("尚无插件", "当前工具服务尚未提供已安装插件")
                else if (matchingInstalled.isEmpty() && matchingOthers.isEmpty())
                    ZxEmpty("没有匹配的插件", "尝试搜索其他名称或状态")
                matchingInstalled.forEach { id ->
                    val lifecycle = plugins.firstOrNull { it.id == id }
                    ZxRow(id, lifecycle?.state?.name ?: "已安装 · 未提供生命周期状态")
                }
                matchingOthers.forEach { item ->
                    ZxRow(item.id, item.state.name)
                }
                plugins.filter { it.state == com.labteto.dshmobile.uicontract.UiPluginLifecycleState.FAILED &&
                        (pluginQuery.isBlank() ||
                            it.id.contains(pluginQuery.trim(), ignoreCase = true)) }
                    .forEach { item ->
                        ZxButton("恢复 ${item.id}", {
                            submitConfiguration(ToolsUiCommand.RecoverFailedPlugin(item.id))
                        }, enabled = !busy, loading = busy)
                    }
                Spacer(Modifier.height(12.dp))
                ZhaoxiSkillManager(tools)
            }
            "tools.github" -> {
                ZxSection("连接状态")
                ZxValueRow("GitHub", value = if (githubConfigured) "已配置" else "未配置")
                ZxSection("可用能力")
                catalogue.filter { it.family.contains("github", ignoreCase = true) }
                    .forEach { cap -> ZxRow(cap.name, "${cap.access} · ${cap.approval}") }
                if (githubConfigured) ZhaoxiGitHubBrowser(tools)
                else ZxFooterNote("先连接 GitHub，再查看真实仓库目录。")
                if (!githubConfigured) {
                    ZxSection("连接账户")
                    com.labteto.dshmobile.uipack.zhaoxi.components.ZxField(
                        githubToken, { githubToken = it }, Modifier.fillMaxWidth(),
                        placeholder = "GitHub 访问令牌",
                        visualTransformation = PasswordVisualTransformation(),
                    )
                    ZxButton("保存 GitHub 授权", {
                        val submittedToken = githubToken.trim()
                        if (submittedToken.isNotBlank()) submitConfiguration(
                            ToolsUiCommand.ConfigureGitHub(submittedToken), clearTokenOnSuccess = true)
                    }, enabled = !busy && githubToken.isNotBlank(), loading = busy)
                } else {
                    ZxButton("解除 GitHub 连接", { pendingClearGitHub = true },
                        variant = ZxButtonVariant.Outline)
                }
                if (pendingClearGitHub) ZxDialog(
                    "解除 GitHub 连接？", "连接设置将被清除，现有仓库内容不会删除。", "解除连接",
                    {
                        pendingClearGitHub = false
                        submitConfiguration(ToolsUiCommand.ClearGitHub)
                    },
                    { pendingClearGitHub = false },
                )
            }
            "work.capabilities" -> {
                val grouped = catalogue.groupBy { it.family }
                if (grouped.isEmpty()) ZxEmpty("暂无能力", "已注册的能力会在这里出现")
                grouped.forEach { (group, items) ->
                    ZxSection(zxToolFamilyLabel(group))
                    items.forEach { cap ->
                        ZxRow(cap.name, "${cap.access} · ${cap.approval}")
                    }
                }
            }
            else -> {
                ZxSection("可用能力")
                catalogue.groupBy { it.family }.forEach { (family, items) ->
                    ZxRow(zxToolFamilyLabel(family), "${items.size} 项已登记能力",
                        onClick = { expandedFamily = if (expandedFamily == family) null else family })
                    if (expandedFamily == family) {
                        items.forEach { cap ->
                            ZxRow(cap.name, "${cap.access} · ${cap.approval}")
                        }
                    }
                }
                ZxSection("技能")
                ZxField(skillQuery, { skillQuery = it }, Modifier.fillMaxWidth(),
                    placeholder = "搜索已安装技能及说明")
                val matchedSkills = skills.orEmpty().filter { item ->
                    skillQuery.isBlank() ||
                        item.displayName.contains(skillQuery.trim(), ignoreCase = true) ||
                        item.description.contains(skillQuery.trim(), ignoreCase = true)
                }
                if (skills == null) ZxFooterNote("正在读取已安装技能…")
                else if (skills.orEmpty().isEmpty()) ZxEmpty("暂无已安装技能", "打开插件与技能管理，可上传 SKILL.md 或创建技能")
                else if (matchedSkills.isEmpty()) ZxEmpty("没有匹配的技能", "可修改名称或说明关键词")
                matchedSkills.forEach { item ->
                    ZxRow(item.displayName, item.description, onClick = {
                        scope.onNavigate(UiSceneId("tools.plugins"))
                    })
                }
                ZxSection("连接与扩展")
                ZxRow("MCP 服务器", "${servers.size} 个已登记连接",
                    onClick = { scope.onNavigate(UiSceneId("tools.mcp")) })
                ZxRow("插件与技能管理", "${installedIds.size} 个插件 · ${skills.orEmpty().size} 项已安装技能",
                    onClick = { scope.onNavigate(UiSceneId("tools.plugins")) })
                ZxRow("GitHub", if (githubConfigured) "已配置" else "未配置",
                    onClick = { scope.onNavigate(UiSceneId("tools.github")) })

            }
        }
    }
}

/** Display-only labels. Decisions and status transitions remain owned by AutomationUiPort. */
internal fun AutomationStatus.zxAutomationStatusLabel(): String = when (this) {
    AutomationStatus.SCHEDULED -> "已计划"
    AutomationStatus.RUNNING -> "运行中"
    AutomationStatus.WAITING_USER -> "等待处理"
    AutomationStatus.PAUSED -> "已暂停"
    AutomationStatus.BLOCKED -> "已受阻"
    AutomationStatus.FAILED -> "执行失败"
    AutomationStatus.COMPLETED -> "已完成"
    AutomationStatus.CANCELLED -> "已取消"
    AutomationStatus.SKIPPED -> "已跳过"
    AutomationStatus.QUEUED -> "排队中"
}

internal fun AutomationScheduleType.zxAutomationScheduleLabel(): String = when (this) {
    AutomationScheduleType.LEGACY -> "旧版周期"
    AutomationScheduleType.ONCE -> "单次"
    AutomationScheduleType.INTERVAL -> "固定间隔"
    AutomationScheduleType.DAILY -> "每天"
    AutomationScheduleType.WEEKLY -> "每周"
    AutomationScheduleType.MONTHLY -> "每月"
    AutomationScheduleType.SILENCE -> "沉默后"
    AutomationScheduleType.WINDOW -> "指定时段"
}


/** Read-only GitHub repository browsing. Writable GitHub tools still use Work approval. */
@Composable
private fun ZhaoxiGitHubBrowser(tools: com.labteto.dshmobile.uicontract.ToolsUiPort) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var revision by remember { mutableStateOf(0) }
    var selected by remember { mutableStateOf<com.labteto.dshmobile.uicontract.UiGitHubRepo?>(null) }
    var directory by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val repositories by produceState<List<com.labteto.dshmobile.uicontract.UiGitHubRepo>?>(null, tools, revision) {
        value = try {
            tools.githubRepositories()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message ?: "读取仓库失败，请确认 GitHub 权限和网络"
            emptyList()
        }
    }
    val entries by produceState<List<com.labteto.dshmobile.uicontract.UiGitHubEntry>?>(
        null, tools, selected?.fullName, directory, revision
    ) {
        value = try {
            selected?.let { tools.githubEntries(it.fullName, directory) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message ?: "GitHub 目录读取失败"
            emptyList()
        }
    }
    ZxSection("真实仓库浏览 · 只读")
    ZxFooterNote("仓库列表来自当前已授权的 GitHub 账户；创建 PR、提交修改和其他写操作仍经原有 Work/GitHub 工具审批。")
    error?.let { ZxFooterNote(it) }
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ZxButton("刷新仓库", { error = null; revision++ },
            variant = ZxButtonVariant.Outline)
        if (selected != null) {
            ZxButton("返回仓库列表", { selected = null; directory = ""; error = null },
                variant = ZxButtonVariant.Outline)
        }
    }
    if (selected == null) {
        when {
            repositories == null -> ZxFooterNote("正在读取仓库…")
            repositories.orEmpty().isEmpty() -> ZxEmpty("没有可展示的仓库", "请确认授权范围；可刷新重试")
            else -> repositories.orEmpty().forEach { repo ->
                ZxRow(repo.fullName, buildString {
                    append(if (repo.privateRepo) "私有" else "公开")
                    if (repo.description.isNotBlank()) append(" · ").append(repo.description.take(130))
                }, onClick = { selected = repo; directory = ""; error = null })
            }
        }
    } else {
        val repo = selected!!
        ZxSection(repo.fullName)
        ZxFooterNote("当前目录：/" + directory)
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ZxButton("复制仓库地址", {
                clipboard.setText(AnnotatedString("https://github.com/${repo.fullName}"))
            }, variant = ZxButtonVariant.Outline)
            if (directory.isNotBlank()) ZxButton("上级目录", {
                directory = directory.substringBeforeLast('/', "")
                error = null
            }, variant = ZxButtonVariant.Outline)
        }
        when {
            entries == null -> ZxFooterNote("读取目录中…")
            entries.orEmpty().isEmpty() -> ZxEmpty("目录没有可显示内容", "可返回上级或刷新")
            else -> entries.orEmpty().forEach { file ->
                ZxRow(
                    if (file.directory) "文件夹 · ${file.name}" else file.name,
                    if (file.directory) "进入目录" else "${file.size} 字节 · 在 GitHub 查看",
                    onClick = {
                        if (file.directory) {
                            directory = file.path
                            error = null
                        } else {
                            val url = "https://github.com/${repo.fullName}/blob/" +
                                android.net.Uri.encode(repo.defaultBranch) + "/" +
                                file.path.split('/').joinToString("/") { android.net.Uri.encode(it) }
                            try {
                                context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse(url)))
                            } catch (failure: Exception) {
                                error = "没有可用的浏览器，请复制仓库地址后打开"
                            }
                        }
                    })
            }
        }
    }
}

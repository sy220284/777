package com.labteto.dshmobile.uipack.zhaoxi.scenes

import androidx.compose.foundation.layout.FlowRow
import com.labteto.dshmobile.automation.AutomationMode
import com.labteto.dshmobile.automation.AutomationScheduleType
import com.labteto.dshmobile.uicontract.AutomationUiReceipt
import com.labteto.dshmobile.uicontract.UiAutomationSchedule
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxField
import kotlinx.coroutines.CancellationException
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
                else -> true
            }
        }
    }
    ZxPage("自动化", onBack = { scope.onBack() }) {
        Spacer(Modifier.height(8.dp))
        ZxSection("任务状态")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("全部", "已计划", "运行中", "已暂停").forEach { label ->
                ZxChip(label, filter == label) { filter = label }
            }
        }
        ZxValueRow("当前列表", value = "${filtered.size} / ${snap.tasks.size}")
        feedback?.let { Text(it, color = ZhaoxiTheme.colors.ink2) }
        if (filtered.isEmpty()) ZxEmpty("没有符合条件的任务", "可切换状态筛选或创建新任务")
        filtered.forEach { task ->
            val paused = task.status == com.labteto.dshmobile.automation.AutomationStatus.PAUSED
            val actionAvailable = task.status !in setOf(
                com.labteto.dshmobile.automation.AutomationStatus.CANCELLED,
                com.labteto.dshmobile.automation.AutomationStatus.COMPLETED,
                com.labteto.dshmobile.automation.AutomationStatus.BLOCKED,
            )
            ZxRow(task.prompt.take(80),
                "${if (task.mode == AutomationMode.CHAT) "聊天" else "工作"} · ${task.status.wireValue} · ${task.scheduleType.name}",
                onClick = { onOpenEditor(task.id) },
                showChevron = false,
                trailing = {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (actionAvailable) ZxChip("立即运行", false) {
                            feedback = automation.submit(AutomationUiCommand.RunNow(task.id)).toString()
                        }
                        if (actionAvailable) {
                            ZxSwitch(checked = !paused, onCheckedChange = { on ->
                                feedback = automation.submit(
                                    if (on) AutomationUiCommand.Resume(task.id) else AutomationUiCommand.Pause(task.id)
                                ).toString()
                            }, contentDescription = "${task.prompt.take(20)} · 启用状态")
                        }
                    }
                },
            )
        }
        Spacer(Modifier.height(8.dp))
        ZxButton("新建自动化", onCreate, Modifier.fillMaxWidth())
        ZxFooterNote("任务状态和开关来自 Automation Owner；立即运行仅代表提交请求，不代表执行成功")
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
    fun minuteOfDay(value: String): Int? {
        val pieces = value.trim().split(":")
        if (pieces.size != 2) return null
        val h = pieces[0].toIntOrNull() ?: return null
        val m = pieces[1].toIntOrNull() ?: return null
        return if (h in 0..23 && m in 0..59) h * 60 + m else null
    }
    fun showReceipt(receipt: AutomationUiReceipt) {
        feedback = when (receipt) {
            is AutomationUiReceipt.Applied -> "已提交给调度系统；执行结果请从任务列表核对"
            is AutomationUiReceipt.Rejected -> "调度请求被拒绝：${receipt.reason}"
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
                if (natural) ZxFooterNote("直接描述执行时间和任务，使用原有聊天规划服务解析；保存仍由 Owner 校验")
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
                    ZxField(firstRun, { firstRun = it }, Modifier.fillMaxWidth(),
                        placeholder = "YYYY-MM-DD HH:mm")
                    ZxFooterNote("按设备本地时区设置；必须晚于当前时间")
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
            ZxSection("通知")
            ZxSwitchRow("运行通知", "通过现有自动化通知策略提示结果", notify) { notify = it }
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
                    feedback = when {
                        timed && (date == null || date <= System.currentTimeMillis()) ->
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
                        showReceipt(automation.submit(if (editing == null) {
                            AutomationUiCommand.Create(snap.context.identity, schedule)
                        } else {
                            AutomationUiCommand.Update(editing.id, editing.scheduleGeneration, schedule)
                        }))
                    }
                }
            }, Modifier.fillMaxWidth(), enabled = !busy && prompt.isNotBlank(), loading = busy)
            ZxFooterNote("单次、间隔、每天、每周、每月及聊天特殊触发均复用原有调度 Owner；请求被接收不代表任务已运行")
        }
    }
}

/** automation.persona_interaction — 人物互动编排。 */
@Composable
fun ZhaoxiPersonaInteractionScene(
    automation: AutomationUiPort,
    scope: UiSceneRenderScope,
) {
    val co = rememberCoroutineScope()
    ZxPage("人物互动编排", onBack = { scope.onBack() }) {
        Spacer(Modifier.height(8.dp))
        ZxSection("规则")
        ZxRow("相遇事件 → 生成互动", "事件总线触发 · 双人物会话", trailing = {
            ZxBadge("示例", ZxBadgeTone.Neutral)
        })
        ZxFooterNote("互动占用各自人物的对话额度")
        Spacer(Modifier.height(10.dp))
        ZxButton("编排新互动", {
            scope.onNavigate(UiSceneId("automation.editor"))
        }, Modifier.fillMaxWidth())
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
                ZxFooterNote("连接状态不代表工具已经授权执行；每次执行仍由原 Owner 验证")
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
                ZxFooterNote("未提供的启停、安装操作不伪造；插件状态以 Owner 快照为准")
            }
            "tools.github" -> {
                ZxSection("连接状态")
                ZxValueRow("GitHub", value = if (githubConfigured) "已配置" else "未配置")
                ZxSection("可用能力")
                catalogue.filter { it.family.contains("github", ignoreCase = true) }
                    .forEach { cap -> ZxRow(cap.name, "${cap.access} · ${cap.approval}") }
                if (!githubConfigured) ZxFooterNote("GitHub 尚未配置；请通过已有授权配置入口连接")
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
                ZxFooterNote("能力登记不等于运行许可；受控操作由原工具 Owner 执行")
            }
        }
    }
}

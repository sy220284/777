package com.labteto.dshmobile.uipack.zhaoxi.scenes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.labteto.dshmobile.uicontract.UiSessionIdentity
import com.labteto.dshmobile.uicontract.UiWorkEvidenceReadReceipt
import com.labteto.dshmobile.uicontract.UiWorkPlanSnapshot
import com.labteto.dshmobile.uicontract.WorkEvidenceUiPort
import com.labteto.dshmobile.uicontract.WorkReadUiPort
import com.labteto.dshmobile.uicontract.WorkTeamUiCommand
import com.labteto.dshmobile.uicontract.WorkTeamUiPort
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxBadge
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxBadgeTone
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxButton
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxButtonVariant
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxDialog
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxField
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxChip
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxFooterNote
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxEmpty
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxPlanReviewCard
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxPlanStep
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxSection
import com.labteto.dshmobile.uipack.zhaoxi.theme.ZhaoxiTheme
import com.labteto.dshmobile.uihost.UiSceneRenderScope
import kotlinx.coroutines.launch

/**
 * 工作域场景（E8.14 实装）：work.plan_review / requirement_evidence / team /
 * capabilities / steering_choice / workspace.preview。
 * 证据与团队数据全部来自 Work Owner 端口投影；不做任何完成性断言的改写。
 */

/** work.plan_review — 计划审查（方案呈现 + 逐步确认 + 批准执行）。 */
@Composable
fun ZhaoxiPlanReviewScene(workReads: WorkReadUiPort, work: com.labteto.dshmobile.uicontract.WorkConversationUiPort,
    sessionId: String, scope: UiSceneRenderScope, canOpenEvidence: Boolean = false) {
    val snapshot by work.snapshots.collectAsState(initial = work.snapshot())
    val plan = snapshot.let { workReads.planForUi(sessionId) }
    var historyRefresh by remember(sessionId) { mutableStateOf(0) }
    val sequence = workReads.eventSequenceForUi(sessionId)
    val history = remember(sessionId, snapshot, sequence, historyRefresh) {
        workReads.historyPageForUi(sessionId, null)
    }
    var olderEvents by remember(sessionId, history.older, historyRefresh) {
        mutableStateOf<List<com.labteto.dshmobile.local.presentation.LocalWorkHistoryRecord>>(emptyList())
    }
    var olderCursor by remember(sessionId, history.older, historyRefresh) {
        mutableStateOf(history.older)
    }
    var historyExpired by remember(sessionId, historyRefresh) { mutableStateOf(false) }
    var expandedLatest by remember(sessionId, historyRefresh) { mutableStateOf(false) }
    ZxPage("计划审查", onBack = { scope.onBack() }) {
        if (plan == null) {
            com.labteto.dshmobile.uipack.zhaoxi.components.ZxEmpty("没有进行中的计划", "计划由工作运行时提供")
        } else {
            // Plan and Todo lists are separate Owner projections: their positions are not related.
            val progress = zxWorkTodoProgress(plan)
            ZxSection("实际待办进度")
            Row(
                Modifier.fillMaxWidth()
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                    .background(ZhaoxiTheme.colors.card)
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (progress.total > 0 && progress.reported > 0) {
                    Box(Modifier.size(66.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            progress = { progress.done.toFloat() / progress.total },
                            modifier = Modifier.size(66.dp),
                            color = ZhaoxiTheme.colors.accent,
                            trackColor = ZhaoxiTheme.colors.line,
                            strokeWidth = 5.dp,
                        )
                        Text("${progress.done * 100 / progress.total}%",
                            color = ZhaoxiTheme.colors.ink, fontSize = 14.sp)
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("${progress.done} / ${progress.total} 项待办已完成",
                        color = ZhaoxiTheme.colors.ink, fontSize = 16.sp)
                    Text(
                        if (progress.total == 0) "暂无待办执行记录"
                        else if (progress.reported == 0) "等待运行时报告待办状态"
                        else "已更新 ${progress.reported} 项 · 以实际待办回执为准",
                        color = ZhaoxiTheme.colors.ink2, fontSize = 12.sp,
                    )
                    Text("待办进度不代表任务整体成功",
                        color = ZhaoxiTheme.colors.ink3, fontSize = 11.sp)
                }
            }
            ZxSection("计划步骤")
            if (plan.plan.isEmpty()) ZxEmpty("暂无计划步骤", "运行时更新后会显示")
            plan.plan.forEachIndexed { index, step ->
                ZxRow("${index + 1}. $step", "")
            }
            if (plan.todos.isNotEmpty()) {
                ZxSection("待办执行状态")
                plan.todos.forEachIndexed { index, todo ->
                    ZxRow("${index + 1}. ${todo.content}", "", trailing = {
                        ZxBadge(zxWorkStepLabel(todo.ownerStatus), zxWorkStepTone(todo.ownerStatus))
                    })
                }
            }
            if (snapshot.question != null || snapshot.approval != null) {
                ZxSection("需要操作")
                snapshot.question?.let { ZxNavRow("回答问题") {
                    scope.onNavigate(com.labteto.dshmobile.uicontract.UiSceneId("work.question"))
                } }
                snapshot.approval?.let { ZxNavRow("处理审批") {
                    scope.onNavigate(com.labteto.dshmobile.uicontract.UiSceneId("work.approval"))
                } }
            }
            ZxSection("运行事件")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                ZxBadge("已读取 ${history.records.size + olderEvents.size} 条", ZxBadgeTone.Neutral)
                ZxChip("刷新日志", false) { historyRefresh++ }
            }
            if (history.invalidated || historyExpired) {
                ZxFooterNote("工作日志已更换或重置，刷新后重新读取，旧游标不能继续使用")
            } else {
                if (history.records.isEmpty() && olderEvents.isEmpty())
                    ZxEmpty("尚无运行事件", "执行开始后显示真实工作事件")
                val latestVisible = if (expandedLatest) history.records else history.records.takeLast(24)
                latestVisible.asReversed().forEach { event ->
                    ZhaoxiWorkEventTimelineItem(event)
                }
                if (history.records.size > 24) {
                    ZxChip(
                        if (expandedLatest) "收起当前记录" else "展开本页其余 ${history.records.size - 24} 条",
                        expandedLatest,
                    ) { expandedLatest = !expandedLatest }
                }
                if (olderEvents.isNotEmpty()) {
                    ZxSection("较早记录 · ${olderEvents.size} 条")
                    olderEvents.asReversed().forEach { event ->
                        ZhaoxiWorkEventTimelineItem(event)
                    }
                }
                if (olderCursor != null && (expandedLatest || history.records.size <= 24))
                    ZxChip("加载较早事件", false) {
                    val page = workReads.historyPageForUi(sessionId, olderCursor)
                    if (page.invalidated) {
                        historyExpired = true
                        olderCursor = null
                        olderEvents = emptyList()
                    } else {
                        olderEvents = (page.records + olderEvents).distinctBy { it.sequence }
                        olderCursor = page.older
                    }
                }
            }
            val tools = remember(sessionId, snapshot, sequence, historyRefresh) {
                workReads.toolActivitiesForUi(sessionId)
            }
            if (tools.isNotEmpty()) {
                ZxSection("工具执行 · ${tools.size}")
                tools.takeLast(16).asReversed().forEach { item ->
                    val label = when (item.phase) {
                        com.labteto.dshmobile.local.presentation.LocalToolUiPhase.DECLARED -> "等待执行"
                        com.labteto.dshmobile.local.presentation.LocalToolUiPhase.RUNNING -> "执行中"
                        com.labteto.dshmobile.local.presentation.LocalToolUiPhase.COMPLETED -> "已完成"
                        com.labteto.dshmobile.local.presentation.LocalToolUiPhase.FAILED -> "执行失败"
                        com.labteto.dshmobile.local.presentation.LocalToolUiPhase.OUTCOME_UNKNOWN -> "结果未确认"
                        com.labteto.dshmobile.local.presentation.LocalToolUiPhase.CANCELLED -> "已取消"
                    }
                    ZxRow(item.name, label +
                        (item.errorCode?.let { " · 错误 ${it}" } ?: "") +
                        (item.resultPreview?.let { " · ${it.take(90)}" } ?: ""))
                }
            }
            if (canOpenEvidence) ZxNavRow("需求与证据") {
                scope.onNavigate(com.labteto.dshmobile.uicontract.UiSceneId("work.requirement_evidence"))
            }
        }
    }
}

/** Work 事件只有 Owner 报告的状态；不推断最终成功或凭空填入时间。 */
@Composable
private fun ZhaoxiWorkEventTimelineItem(
    event: com.labteto.dshmobile.local.presentation.LocalWorkHistoryRecord,
) {
    val c = ZhaoxiTheme.colors
    val phase = event.phase
    val phaseText = when (phase) {
        com.labteto.dshmobile.local.presentation.LocalToolUiPhase.DECLARED -> "待执行"
        com.labteto.dshmobile.local.presentation.LocalToolUiPhase.RUNNING -> "执行中"
        com.labteto.dshmobile.local.presentation.LocalToolUiPhase.COMPLETED -> "已完成"
        com.labteto.dshmobile.local.presentation.LocalToolUiPhase.FAILED -> "失败"
        com.labteto.dshmobile.local.presentation.LocalToolUiPhase.OUTCOME_UNKNOWN -> "结果未知"
        com.labteto.dshmobile.local.presentation.LocalToolUiPhase.CANCELLED -> "已取消"
        null -> event.type
    }
    val tone = when (phase) {
        com.labteto.dshmobile.local.presentation.LocalToolUiPhase.FAILED,
        com.labteto.dshmobile.local.presentation.LocalToolUiPhase.OUTCOME_UNKNOWN -> ZxBadgeTone.Err
        com.labteto.dshmobile.local.presentation.LocalToolUiPhase.RUNNING -> ZxBadgeTone.Accent
        else -> ZxBadgeTone.Neutral
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(11.dp).clip(CircleShape).background(
                if (tone == ZxBadgeTone.Err) c.err else c.accent))
            Box(Modifier.width(2.dp).height(40.dp).background(c.line))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(event.name.ifBlank { event.type }, Modifier.weight(1f),
                    color = c.ink, fontSize = 14.sp, lineHeight = 20.sp)
                ZxBadge(phaseText, tone)
            }
            event.content.takeIf(String::isNotBlank)?.let {
                Text(it.take(180), color = c.ink2, fontSize = 12.sp, lineHeight = 18.sp)
            }
            Text("事件 #${event.sequence}", color = c.ink3, fontSize = 11.sp)
        }
    }
}

/** work.requirement_evidence — 需求证据关联。 */
@Composable
fun ZhaoxiRequirementEvidenceScene(
    evidence: WorkEvidenceUiPort,
    identity: UiSessionIdentity,
    scope: UiSceneRenderScope,
) {
    val snap by produceState<UiWorkEvidenceReadReceipt?>(null, identity) {
        evidence.changes(identity).collect { value = evidence.read(identity) }
        if (value == null) value = evidence.read(identity)
    }
    ZxPage("需求证据", onBack = { scope.onBack() }) {
        Spacer(Modifier.height(8.dp))
        when (val r = snap) {
            null -> repeat(3) { com.labteto.dshmobile.uipack.zhaoxi.components.ZxSkeleton(com.labteto.dshmobile.uipack.zhaoxi.components.ZxSkeletonVariant.Row, Modifier.padding(vertical = 6.dp)) }
            is UiWorkEvidenceReadReceipt.Available -> {
                ZxSection("需求清单 · ${r.snapshot.requirements.size}")
                ZxValueRow("已关联证据", value = "${r.snapshot.references.size} 条")
                r.snapshot.requirements.forEach { req ->
                    ZxRow("「${req.text}」", "需求 #${req.index} · ${req.reportedStatus}") {
                        ZxBadge(req.reportedStatus, ZxBadgeTone.Neutral)
                    }
                }
                ZxSection("证据引用")
                if (r.snapshot.references.isEmpty()) {
                    com.labteto.dshmobile.uipack.zhaoxi.components.ZxEmpty("暂无关联证据", "关联文件后会显示在这里")
                }
                r.snapshot.references.forEach { link ->
                    ZxRow(link.artifactPath.substringAfterLast('/'),
                        "需求 #${link.requirementIndex} · ${if (link.versionNow == null) "文件不可用" else if (link.versionNow == link.versionAtLink) "版本一致" else "文件已变化"}")
                }
                if (r.snapshot.artifacts.isNotEmpty()) {
                    ZxSection("运行产物 · ${r.snapshot.artifacts.size}")
                    r.snapshot.artifacts.forEach { item ->
                        ZxRow(item.reference.substringAfterLast('/').ifBlank { item.reference },
                            "${item.category} · " + when (item.currentlyAvailable) {
                                true -> "可访问"
                                false -> "已不可访问"
                                null -> "未核对当前可用性"
                            })
                    }
                }
                r.snapshot.machine?.let { m ->
                    ZxSection("机器条件核验")
                    val status = when (m.status) {
                        com.labteto.dshmobile.uicontract.UiWorkMachineVerificationStatus.NO_MACHINE_CONDITIONS -> "没有可自动核验的条件"
                        com.labteto.dshmobile.uicontract.UiWorkMachineVerificationStatus.UNSATISFIED -> "仍有未满足的条件"
                        com.labteto.dshmobile.uicontract.UiWorkMachineVerificationStatus.VERIFIED_CONDITIONS -> "可机检条件已满足"
                    }
                    ZxRow("状态", status)
                    ZxRow("检查范围", "文件条件 ${m.artifactConditionCount} · 命令条件 ${m.commandConditionCount}")
                    if (m.missing.isNotEmpty()) {
                        ZxSection("缺失条件 · ${m.missing.size}")
                        m.missing.forEach { item -> ZxRow(item, "") }
                    }
                }
            }
            is UiWorkEvidenceReadReceipt.Rejected ->
                com.labteto.dshmobile.uipack.zhaoxi.components.ZxBanner("读取被拒 · ${r.reason}", com.labteto.dshmobile.uipack.zhaoxi.components.ZxTone.Err)
            is UiWorkEvidenceReadReceipt.Failed ->
                com.labteto.dshmobile.uipack.zhaoxi.components.ZxBanner("读取失败 · ${r.message ?: "未知"}", com.labteto.dshmobile.uipack.zhaoxi.components.ZxTone.Err)
        }

    }
}

/** work.team — 集群工坊（团队实时分工）。 */
@Composable
fun ZhaoxiWorkTeamScene(team: WorkTeamUiPort, scope: UiSceneRenderScope) {
    val snap by team.snapshots.collectAsState(initial = team.snapshot())
    val co = rememberCoroutineScope()
    var selectedMember by remember(snap.identity?.sessionId) { mutableStateOf<Pair<String, String>?>(null) }
    var textDraft by remember(selectedMember) { mutableStateOf("") }
    var output by remember(selectedMember) { mutableStateOf<String?>(null) }
    var feedback by remember(snap.identity?.sessionId) { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var confirmMember by remember { mutableStateOf<Pair<String, String>?>(null) }
    var confirmTeam by remember { mutableStateOf(false) }
    val current = snap.members.firstOrNull { it.id == selectedMember?.first && it.jobId == selectedMember?.second }
    fun send(command: WorkTeamUiCommand, successfulAction: String) {
        if (busy) return
        busy = true
        co.launch {
            try {
                val result = team.submit(command)
                feedback = zxWorkTeamResultLabel(result, successfulAction)
                if (result.receipt.admitted() && command is WorkTeamUiCommand.SendMessage) textDraft = ""
                // Owner acceptance is not a verified task or final outcome.
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (failure: Exception) { feedback = failure.message ?: "团队操作没有确认成功" }
            finally { busy = false }
        }
    }
    ZxPage("Agent 团队", onBack = { scope.onBack() }) {
        Spacer(Modifier.height(8.dp))
        if (snap.rebuilding) ZxFooterNote("团队正在恢复状态，以下成员信息以运行时投影为准")
        snap.failure?.let {
            com.labteto.dshmobile.uipack.zhaoxi.components.ZxBanner(
                it, com.labteto.dshmobile.uipack.zhaoxi.components.ZxTone.Err)
        }
        feedback?.let { ZxFooterNote(it) }
        ZxSection("团队成员 · ${snap.members.size}")
        if (snap.members.isEmpty())
            ZxEmpty("当前没有运行团队", "可从 Work 中发送带团队的任务后查看进展")
        snap.members.forEach { member ->
            val selected = current?.let { it.id == member.id && it.jobId == member.jobId } == true
            ZxRow(member.name, buildString {
                append(member.phase)
                if (member.activity.isNotBlank()) append(" · ").append(member.activity.take(90))
                if (member.pendingMessages > 0) append(" · 待处理消息 ${member.pendingMessages}")
                member.error?.let { append(" · 错误：").append(it.take(80)) }
            }, onClick = { selectedMember = if (selected) null else member.id to member.jobId },
                trailing = {
                    ZxChip(if (selected) "收起" else "详情", selected) {
                        selectedMember = if (selected) null else member.id to member.jobId
                    }
                })
        }
        if (current != null) {
            ZxSection("成员详情 · ${current.name}")
            ZxValueRow("当前阶段", value = current.phase.ifBlank { "未上报" })
            ZxValueRow("正在做什么", value = current.activity.ifBlank { "暂无运行描述" })
            current.task?.let { ZxRow("任务目标", it.take(600), showChevron = false) }
            if (current.pendingMessages > 0) {
                com.labteto.dshmobile.uipack.zhaoxi.components.ZxBanner(
                    "还有 ${current.pendingMessages} 条成员消息待处理",
                    com.labteto.dshmobile.uipack.zhaoxi.components.ZxTone.Info)
            }
            current.error?.let { com.labteto.dshmobile.uipack.zhaoxi.components.ZxBanner(
                "成员错误：$it", com.labteto.dshmobile.uipack.zhaoxi.components.ZxTone.Warn) }
            ZxSection("成员产出")
            ZxButton("查看真实产出", {
                val identity = snap.identity
                if (identity == null) feedback = "工作会话身份已失效，请重新进入"
                else {
                    val result = team.readOutput(identity, current.id, current.jobId)
                    if (result.receipt.admitted()) {
                        output = result.message
                        feedback = if (result.message.isBlank()) "成员尚未生成可展示的输出" else "已读取成员产出"
                    } else {
                        output = null
                        feedback = zxWorkTeamResultLabel(result, "读取成员产出")
                    }
                }
            }, enabled = snap.identity != null && !busy,
                variant = ZxButtonVariant.Outline)
            output?.takeIf(String::isNotBlank)?.let { value ->
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(value, color = ZhaoxiTheme.colors.ink, fontSize = 13.sp)
                }
            }
            ZxSection("发送消息给成员")
            ZxField(textDraft, { textDraft = it }, Modifier.fillMaxWidth().heightIn(min = 96.dp),
                placeholder = "给此成员补充指令或问题")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ZxButton("发送给成员", {
                    val id = snap.identity
                    if (id != null) send(WorkTeamUiCommand.SendMessage(
                        id, current.id, current.jobId, textDraft.trim()), "成员消息")
                }, Modifier.weight(1f), enabled = !busy && snap.identity != null && textDraft.isNotBlank())
                ZxButton("停止成员", { confirmMember = current.id to current.jobId },
                    Modifier.weight(1f), variant = ZxButtonVariant.Danger,
                    enabled = !busy && snap.identity != null)
            }
        }
        if (snap.members.isNotEmpty()) {
            ZxSection("团队控制")
            ZxFooterNote("执行步骤数只表示成员运行步骤；任务是否完成需以 Work 的真实待办和 Lead 验收为准。")
            ZxButton("停止整个团队", { confirmTeam = true },
                Modifier.fillMaxWidth(), variant = ZxButtonVariant.Danger,
                enabled = !busy && snap.identity != null)
        }
    }
    confirmMember?.let { target ->
        ZxDialog("停止这个 Agent？",
            "将向当前成员发送中断请求，已产生的结果仍须核验。",
            "确认停止", {
                confirmMember = null
                val id = snap.identity
                if (id != null && snap.members.any { it.id == target.first && it.jobId == target.second })
                    send(WorkTeamUiCommand.StopMember(id, target.first, target.second), "停止成员")
                else feedback = "成员任务已变化，取消过时的停止指令"
            }, { confirmMember = null })
    }
    if (confirmTeam) ZxDialog("停止整个 Agent 团队？",
        "将尝试中断当前会话的所有成员。仅发送指令不代表全部成员已经停止。",
        "确认停止", {
            confirmTeam = false
            val id = snap.identity
            val jobs = snap.members.map { it.jobId }.filter(String::isNotBlank).toSet()
            if (id != null && jobs.isNotEmpty())
                send(WorkTeamUiCommand.StopTeam(id, jobs), "停止团队")
            else feedback = "团队状态已变化，无法提交旧任务的停止指令"
        }, { confirmTeam = false })
}

/** Only the Owner's result is displayed; Accepted means admitted, never verified execution. */
internal fun zxWorkTeamResultLabel(result: com.labteto.dshmobile.uicontract.UiWorkTeamResult,
    action: String): String = when (val receipt = result.receipt) {
    com.labteto.dshmobile.uicontract.UiCommandReceipt.Accepted -> "$action：已受理 · ${result.message}"
    com.labteto.dshmobile.uicontract.UiCommandReceipt.Queued -> "$action：已排队 · ${result.message}"
    else -> "$action：${receipt.userHint()} · ${result.message}"
}

/** work.capabilities — 能力就绪（交接 Sheet 内容页面化）。 */
@Composable
fun ZhaoxiWorkCapabilitiesScene(items: List<Triple<String, String, Int>>, scope: UiSceneRenderScope) {
    ZxPage("能力就绪", onBack = { scope.onBack() }) {
        Spacer(Modifier.height(8.dp))
        if (items.isEmpty())
            com.labteto.dshmobile.uipack.zhaoxi.components.ZxEmpty(
                "能力清单未注入", "由宿主能力目录投影提供 · 连接后自动出现")
        items.forEach { (name, sub, state) ->
            ZxRow(name, sub) {
                ZxBadge(
                    when (state) { 1 -> "已配置"; 2 -> "需连接"; else -> "已停用" },
                    when (state) { 1 -> ZxBadgeTone.Accent; 2 -> ZxBadgeTone.Warn; else -> ZxBadgeTone.Err },
                )
            }
        }

    }
}

/** work.steering_choice — 运行中纠偏说明页。 */
@Composable
fun ZhaoxiSteeringScene(scope: UiSceneRenderScope) {
    ZxPage("运行中纠偏", onBack = { scope.onBack() }) {
        Spacer(Modifier.height(8.dp))
        com.labteto.dshmobile.uipack.zhaoxi.components.ZxBanner("运行中直接发送即插队纠偏（steer）", com.labteto.dshmobile.uipack.zhaoxi.components.ZxTone.Info)
        ZxRow("发送即纠偏", "不等当前步骤结束 · 指令进入当前执行上下文")
        ZxRow("排队语义", "队列满时拒收并提示 · 草稿保留")

    }
}

/** Only count the separate Todo Owner list, never assign its statuses to Plan positions. */
internal data class ZxWorkTodoProgress(val done: Int, val reported: Int, val total: Int)

internal fun zxWorkTodoProgress(plan: UiWorkPlanSnapshot): ZxWorkTodoProgress =
    ZxWorkTodoProgress(
        done = plan.todos.count { zxWorkStepIsDone(it.ownerStatus) },
        reported = plan.todos.count { it.ownerStatus.isNotBlank() },
        total = plan.todos.size,
    )

/** The Work Owner status is authoritative; only explicit terminal-success states count as done. */
internal fun zxWorkStepIsDone(status: String): Boolean =
    status.trim().lowercase() in setOf("completed", "done", "success", "finished", "已完成", "完成")

internal fun zxWorkStepLabel(status: String): String = when (status.trim().lowercase()) {
    "completed", "done", "success", "finished", "已完成", "完成" -> "已完成"
    "running", "in_progress", "in-progress", "processing", "进行中", "执行中" -> "进行中"
    "failed", "error", "失败" -> "失败"
    "blocked", "受阻" -> "受阻"
    "pending", "todo", "queued", "not_started", "待执行" -> "待执行"
    "" -> "待同步"
    else -> status.take(20)
}

internal fun zxWorkStepTone(status: String): ZxBadgeTone = when (status.trim().lowercase()) {
    "completed", "done", "success", "finished", "已完成", "完成" -> ZxBadgeTone.Accent
    "running", "in_progress", "in-progress", "processing", "进行中", "执行中" -> ZxBadgeTone.Accent
    "failed", "error", "失败", "blocked", "受阻" -> ZxBadgeTone.Err
    else -> ZxBadgeTone.Neutral
}

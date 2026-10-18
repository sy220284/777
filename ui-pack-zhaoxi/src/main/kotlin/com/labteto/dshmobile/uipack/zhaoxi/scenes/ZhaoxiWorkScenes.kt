package com.labteto.dshmobile.uipack.zhaoxi.scenes

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
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
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxChip
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxFooterNote
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
    sessionId: String, scope: UiSceneRenderScope) {
    val snapshot by work.snapshots.collectAsState(initial = work.snapshot())
    val plan = snapshot.let { workReads.planForUi(sessionId) }
    ZxPage("计划审查", onBack = { scope.onBack() }) {
        if (plan == null) {
            com.labteto.dshmobile.uipack.zhaoxi.components.ZxEmpty("没有进行中的计划", "计划由工作运行时提供")
        } else {
            ZxSection("计划步骤 · ${plan.plan.size}")
            plan.plan.forEachIndexed { index, step ->
                ZxRow("${index + 1}. $step",
                    plan.todos.getOrNull(index)?.ownerStatus?.takeIf(String::isNotBlank)
                        ?: "等待工作运行时更新")
            }
            // Approval and questions carry the actual Owner-issued pending identifier.
            snapshot.question?.let { ZxNavRow("回答计划问题") { scope.onNavigate(com.labteto.dshmobile.uicontract.UiSceneId("work.question")) } }
            snapshot.approval?.let { ZxNavRow("处理审批") { scope.onNavigate(com.labteto.dshmobile.uicontract.UiSceneId("work.approval")) } }
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
                com.labteto.dshmobile.uipack.zhaoxi.components.ZxValueRow(
                    "已关联证据", value = "${r.snapshot.references.size} 条")
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
    ZxPage("集群工坊", onBack = { scope.onBack() }) {
        Spacer(Modifier.height(8.dp))
        snap.failure?.let {
            com.labteto.dshmobile.uipack.zhaoxi.components.ZxBanner(it, com.labteto.dshmobile.uipack.zhaoxi.components.ZxTone.Err)
        }
        if (snap.members.isEmpty()) {
            com.labteto.dshmobile.uipack.zhaoxi.components.ZxEmpty("集群空闲", "发送带团队的任务后，子 Agent 会出现在这里")
        }
        snap.members.forEach { m ->
            ZxRow(m.name, "${m.phase} · ${m.activity}" + (m.error?.let { " · $it" } ?: ""),
                trailing = {
                    Row {
                        if (m.pendingMessages > 0) ZxBadge("${m.pendingMessages}", ZxBadgeTone.Accent)
                        ZxChip("停止", false) {
                            snap.identity?.let { id ->
                                co.launch {
                                    team.submit(WorkTeamUiCommand.StopMember(id, m.id, m.jobId))
                                }
                            }
                        }
                    }
                })
        }

    }
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

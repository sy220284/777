package com.labteto.dshmobile.uipack.v6

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labteto.dshmobile.uicontract.UiSessionIdentity
import com.labteto.dshmobile.uicontract.UiWorkPlanSnapshot
import com.labteto.dshmobile.uicontract.UiWorkTeamMember
import com.labteto.dshmobile.uicontract.UiCommandReceipt
import com.labteto.dshmobile.uicontract.WorkPolicyUiCommand
import com.labteto.dshmobile.uicontract.WorkPolicyUiPort
import com.labteto.dshmobile.uicontract.WorkReadUiPort
import com.labteto.dshmobile.uicontract.WorkTeamUiCommand
import com.labteto.dshmobile.uicontract.WorkTeamUiPort
import com.shenyan.designsystem.components.SyButton
import com.shenyan.designsystem.components.SyButtonStyle
import com.shenyan.designsystem.components.SyGlassCard
import com.shenyan.designsystem.components.SyInput
import com.shenyan.designsystem.components.SyNote
import com.shenyan.designsystem.components.SySwitch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A Work plan is a read projection and a policy toggle, never a model-verified checklist. */
@Composable
fun V6WorkPlanScene(
    read: WorkReadUiPort,
    policy: WorkPolicyUiPort,
    identity: UiSessionIdentity?,
    onBack: () -> Unit,
) {
    val settings by policy.snapshots.collectAsStateWithLifecycle(
        initialValue = remember(policy) { policy.snapshot() })
    var plan by remember(identity) { mutableStateOf<UiWorkPlanSnapshot?>(null) }
    var reading by remember(identity) { mutableStateOf(false) }
    var feedback by remember(identity) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun refresh() {
        val target = identity ?: return
        if (reading) return
        reading = true
        scope.launch {
            try {
                val snapshot = withContext(Dispatchers.IO) { read.planForUi(target.sessionId) }
                if (settings.identity == target) {
                    plan = snapshot
                    feedback = null
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (settings.identity == target) feedback = failure.message ?: "无法读取工作计划"
            } finally { reading = false }
        }
    }
    LaunchedEffect(identity, settings.identity) {
        if (identity != null && identity == settings.identity) refresh()
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Work 计划", style = MaterialTheme.typography.titleLarge)
            SyButton("返回", onBack, style = SyButtonStyle.GHOST)
        }
        if (identity == null || settings.identity != identity) {
            SyNote("Work 会话尚未稳定载入，暂不允许修改计划策略")
        } else {
            SySwitch("计划模式", settings.planMode, onCheckedChange = { enabled ->
                if (policy.snapshot().identity != identity) {
                    feedback = "Work 会话已变更，未修改计划模式"
                } else {
                    feedback = describeV6ConversationReceipt(
                        policy.submit(WorkPolicyUiCommand.SetPlanMode(identity, enabled)))
                }
            })
            SyButton("刷新真实计划", { refresh() }, enabled = !reading,
                style = SyButtonStyle.SECONDARY)
            if (reading) SyNote("正在读取 Work Owner 计划")
            feedback?.let { SyNote(it) }
            val current = plan
            if (current == null || current.sessionId != identity.sessionId) {
                SyNote("当前尚无可读取的 Work 计划")
            } else {
                if (current.planMode != settings.planMode)
                    SyNote("执行状态与策略可能仍在同步，以 Work Owner 实际状态为准")
                current.plan.forEachIndexed { index, line ->
                    SyGlassCard(Modifier.fillMaxWidth()) {
                        Text("${index + 1}. $line")
                    }
                }
                Text("待办步骤（状态由 Owner 报告）", style = MaterialTheme.typography.titleMedium)
                current.todos.forEachIndexed { index, step ->
                    SyGlassCard(Modifier.fillMaxWidth()) {
                        Text("${index + 1}. ${step.content}")
                        Text("报告状态：${step.ownerStatus}")
                    }
                }
            }
        }
    }
}

/** Team child jobs are inspected and controlled only through original Work Team owner. */
@Composable
fun V6WorkTeamScene(
    team: WorkTeamUiPort,
    onBack: () -> Unit,
) {
    val snapshot by team.snapshots.collectAsStateWithLifecycle(
        initialValue = remember(team) { team.snapshot() })
    val identity = snapshot.identity
    var selected by remember(identity) { mutableStateOf<String?>(null) }
    var draft by remember(identity, selected) { mutableStateOf("") }
    var feedback by remember(identity) { mutableStateOf<String?>(null) }
    var output by remember(identity, selected) { mutableStateOf<String?>(null) }
    var confirming by remember(identity) { mutableStateOf<Pair<String,String>?>(null) }
    var pending by remember(identity) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val member = snapshot.members.firstOrNull { it.id == selected }

    fun submit(command: WorkTeamUiCommand) {
        val target = identity ?: return
        if (pending || team.snapshot().identity != target) {
            feedback = "Work 会话已变更，未执行操作"
            return
        }
        pending = true
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { team.submit(command) }
                if (team.snapshot().identity == target) {
                    feedback = result.message + " · " + describeV6ConversationReceipt(result.receipt)
                    if (result.receipt == UiCommandReceipt.Accepted &&
                        command is WorkTeamUiCommand.SendMessage) draft = ""
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (team.snapshot().identity == target)
                    feedback = failure.message ?: "Work 团队操作失败"
            } finally { pending = false }
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Work 团队", style = MaterialTheme.typography.titleLarge)
            SyButton("返回", onBack, style = SyButtonStyle.GHOST)
        }
        if (identity == null) SyNote("当前尚未进入有效 Work 会话")
        if (snapshot.rebuilding) SyNote("Work Owner 正在恢复团队状态")
        snapshot.failure?.let { SyNote(it) }
        feedback?.let { SyNote(it) }
        if (snapshot.members.isEmpty()) SyNote("当前没有运行中的团队任务")
        snapshot.members.forEach { item ->
            SyGlassCard(Modifier.fillMaxWidth()) {
                SyButton(item.name, { selected = item.id; output = null },
                    style = if (selected == item.id) SyButtonStyle.PRIMARY else SyButtonStyle.SECONDARY)
                Text("执行阶段：${item.phase} · 当前动作：${item.activity}")
                item.task?.let { Text("任务：$it") }
                Text("待处理消息：${item.pendingMessages}")
                item.error?.let { SyNote(it) }
            }
        }
        if (member != null && identity != null) {
            Text("成员详情：" + member.name, style = MaterialTheme.typography.titleMedium)
            SyButton("读取真实输出", {
                val latest = team.snapshot()
                if (latest.identity != identity ||
                    latest.members.none { it.id == member.id && it.jobId == member.jobId }) {
                    feedback = "成员任务已变化，请刷新"
                } else {
                    val result = team.readOutput(identity, member.id, member.jobId)
                    feedback = describeV6ConversationReceipt(result.receipt)
                    output = result.message
                }
            }, style = SyButtonStyle.SECONDARY, enabled = !snapshot.rebuilding && !pending)
            output?.let { SyGlassCard(Modifier.fillMaxWidth()) { Text(it) } }
            SyInput(draft, { draft = it }, label = "发送给该团队成员", singleLine = false)
            SyButton("发送消息", {
                val latest = team.snapshot()
                if (latest.identity != identity ||
                    latest.members.none { it.id == member.id && it.jobId == member.jobId }) {
                    feedback = "成员任务已改变，消息仍保留在输入框"
                } else {
                    submit(WorkTeamUiCommand.SendMessage(identity, member.id, member.jobId, draft))
                }
            }, enabled = draft.isNotBlank() && !snapshot.rebuilding && !pending)
            SyButton("停止该成员", {
                confirming = member.id to member.jobId
            }, style = SyButtonStyle.DANGER, enabled = !pending && !snapshot.rebuilding)
        }
    }
    confirming?.let { (memberId, jobId) ->
        AlertDialog(onDismissRequest = { if (!pending) confirming = null },
            title = { Text("确认停止团队成员") },
            text = { Text("将向 Work Owner 请求停止成员 $memberId 的任务 $jobId。") },
            confirmButton = { TextButton(onClick = {
                val target = identity
                confirming = null
                if (target != null && team.snapshot().identity == target &&
                    team.snapshot().members.any { it.id == memberId && it.jobId == jobId })
                    submit(WorkTeamUiCommand.StopMember(target, memberId, jobId))
                else feedback = "该成员任务已失效"
            }) { Text("确认停止") } },
            dismissButton = { TextButton(onClick = { confirming = null }) { Text("取消") } })
    }
}

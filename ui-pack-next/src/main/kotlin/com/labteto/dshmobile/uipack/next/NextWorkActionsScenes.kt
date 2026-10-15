package com.labteto.dshmobile.uipack.next

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labteto.dshmobile.uicontract.*
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.session.LocalConversationMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Native Work prompt surfaces; execute only the current Owner-issued pending identity. */
@Composable
internal fun NextWorkPromptScene(work: WorkConversationUiPort, approval: Boolean, onBack: () -> Unit) {
    val snap by work.snapshots.collectAsStateWithLifecycle(initialValue = work.snapshot())
    var customAnswer by rememberSaveable(snap.identity) { mutableStateOf("") }
    var notice by remember { mutableStateOf<String?>(null) }
    var decision by remember { mutableStateOf<Boolean?>(null) }
    val current = snap.identity
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(if (approval) "工具审批" else "工作问题", style = MaterialTheme.typography.titleLarge)
        TextButton(onClick = onBack) { Text("返回") }
        notice?.let { Text(it) }
        if (current == null) Text("没有当前 Work 会话")
        else if (approval) {
            val pending = snap.approval
            if (pending == null) Text("当前没有待审批的工具调用")
            else {
                Text(pending.toolName, style = MaterialTheme.typography.titleMedium)
                Text("影响等级：${pending.impact}")
                Text(pending.summary)
                Text(pending.arguments)
                Row {
                    Button(onClick = { decision = true }) { Text("批准本次") }
                    TextButton(onClick = { decision = false }) { Text("拒绝") }
                }
                decision?.let { agree ->
                    AlertDialog(onDismissRequest = { decision = null },
                        title = { Text(if (agree) "确认批准" else "确认拒绝") },
                        text = { Text("仅针对当前待决工具 ${pending.toolName}，不会扩大权限。") },
                        confirmButton = { TextButton(onClick = {
                            decision = null
                            notice = when (val answer = work.submit(
                                WorkConversationUiCommand.AnswerApproval(current, pending.pendingId, agree))) {
                                UiCommandReceipt.Accepted -> "审批决策已受理"
                                is UiCommandReceipt.Rejected -> "审批已失效：${answer.reason}"
                                is UiCommandReceipt.Failed -> answer.message ?: "审批提交失败"
                                else -> "审批状态尚未确认"
                            }
                        }) { Text("提交决定") } },
                        dismissButton = { TextButton(onClick = { decision = null }) { Text("取消") } })
                }
            }
        } else {
            val pending = snap.question
            if (pending == null) Text("当前没有待回答的问题")
            else {
                Text(pending.question, style = MaterialTheme.typography.titleMedium)
                pending.options.forEach { option ->
                    TextButton(onClick = {
                        notice = when (val reply = work.submit(
                            WorkConversationUiCommand.AnswerQuestion(current, pending.pendingId, option))) {
                            UiCommandReceipt.Accepted -> "答案已提交"
                            is UiCommandReceipt.Rejected -> "问题已失效：${reply.reason}"
                            is UiCommandReceipt.Failed -> reply.message ?: "提交失败"
                            else -> "操作结果未确认"
                        }
                    }) { Text(option) }
                }
                OutlinedTextField(customAnswer, { customAnswer = it }, label = { Text("其他回答") },
                    modifier = Modifier.fillMaxWidth())
                Button(enabled = customAnswer.isNotBlank(), onClick = {
                    notice = when (val reply = work.submit(
                        WorkConversationUiCommand.AnswerQuestion(current, pending.pendingId, customAnswer))) {
                        UiCommandReceipt.Accepted -> "答案已提交"
                        is UiCommandReceipt.Rejected -> "问题已失效：${reply.reason}"
                        is UiCommandReceipt.Failed -> reply.message ?: "提交失败"
                        else -> "操作结果未确认"
                    }
                }) { Text("发送回答") }
                TextButton(onClick = {
                    work.submit(WorkConversationUiCommand.AnswerQuestion(current, pending.pendingId, null))
                }) { Text("取消这个问题") }
            }
        }
    }
}

/** Work evidence is an explicit reference, not an assertion that the file passed verification. */
@Composable
internal fun NextWorkEvidenceScene(owner: WorkEvidenceUiPort, work: WorkConversationUiPort,
    onBack: () -> Unit,
) {
    val identity = work.snapshots.collectAsStateWithLifecycle(initialValue = work.snapshot()).value.identity
    var revision by remember { mutableIntStateOf(0) }
    var snapshot by remember { mutableStateOf<UiWorkEvidenceSnapshot?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var selectedIndex by remember { mutableIntStateOf(-1) }
    var selectedArtifact by remember { mutableStateOf<com.labteto.dshmobile.local.presentation.LocalArtifactUiItem?>(null) }
    var inspected by remember { mutableStateOf<com.labteto.dshmobile.local.presentation.LocalArtifactUiItem?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(owner, identity, revision) {
        snapshot = null
        val id = identity ?: return@LaunchedEffect
        try {
            when (val receipt = withContext(Dispatchers.IO) { owner.read(id) }) {
                is UiWorkEvidenceReadReceipt.Available -> snapshot = receipt.snapshot
                is UiWorkEvidenceReadReceipt.Failed -> notice = receipt.message ?: "读取证据失败"
                is UiWorkEvidenceReadReceipt.Rejected -> notice = "证据不可用：${receipt.reason}"
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { notice = failure.message ?: "读取证据失败" }
    }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Work 证据引用", style = MaterialTheme.typography.titleLarge)
        TextButton(onClick = onBack) { Text("返回") }
        notice?.let { Text(it) }
        val facts = snapshot
        if (facts == null) Text("正在读取原始任务与文件事实")
        else {
            Text("关联文件只表示引用；不能替代自动验收或文件校验。")
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(facts.requirements, key = { it.index }) { row ->
                    FilterChip(selected = selectedIndex == row.index,
                        onClick = { selectedIndex = row.index; inspected = null },
                        label = { Text("${row.index + 1}. ${row.text} · ${row.reportedStatus}") })
                }
                items(facts.artifacts, key = { it.reference + ":" + it.asOfSequence }) { file ->
                    Column {
                        FilterChip(selected = selectedArtifact == file,
                            onClick = { selectedArtifact = file; inspected = null },
                            label = { Text(file.reference) })
                        Text(file.category)
                    }
                }
            }
            TextButton(enabled = selectedArtifact != null && !busy, onClick = {
                val file = selectedArtifact ?: return@TextButton
                val id = identity ?: return@TextButton
                scope.launch {
                    busy = true
                    try {
                        when (val receipt = withContext(Dispatchers.IO) { owner.inspect(id, file) }) {
                            is UiWorkArtifactInspectionReceipt.Ready -> {
                                inspected = receipt.artifact
                                notice = "已重新检查文件当前版本"
                            }
                            is UiWorkArtifactInspectionReceipt.Failed -> notice = receipt.message ?: "校验失败"
                            is UiWorkArtifactInspectionReceipt.Rejected -> notice = "文件引用已失效：${receipt.reason}"
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { notice = failure.message ?: "文件检查失败" }
                    finally { busy = false }
                }
            }) { Text("检查文件当前版本") }
            Button(enabled = identity != null && !busy && inspected != null &&
                facts.requirements.any { it.index == selectedIndex }, onClick = {
                val id = identity ?: return@Button
                val requirement = facts.requirements.firstOrNull { it.index == selectedIndex } ?: return@Button
                val artifact = inspected ?: return@Button
                scope.launch {
                    busy = true
                    try {
                        notice = when (val receipt = withContext(Dispatchers.IO) {
                            owner.submit(LinkWorkEvidenceReferenceCommand(id, requirement.index,
                                requirement.text, artifact, facts.goalId, verifyGoalId = true))
                        }) {
                            UiCommandReceipt.Accepted -> "已登记引用；任务状态未被自动判定为通过"
                            is UiCommandReceipt.Rejected -> "登记拒绝：${receipt.reason}"
                            is UiCommandReceipt.Failed -> receipt.message ?: "登记失败"
                            else -> "证据操作结果未确认"
                        }
                        revision++
                        inspected = null
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { notice = failure.message ?: "登记失败" }
                    finally { busy = false }
                }
            }) { Text("将文件版本关联到选中要求") }
        }
    }
}

/** All new-session actions go through the Session Owner; no duplicate catalog is created. */
@Composable
internal fun NextNewSessionScene(owner: SessionUiPort, onBack: () -> Unit, onCreated: () -> Unit) {
    val state by owner.snapshots.collectAsStateWithLifecycle(initialValue = owner.snapshot())
    var mode by remember { mutableStateOf(state.usageMode) }
    var notice by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("新建会话", style = MaterialTheme.typography.titleLarge)
        TextButton(onClick = onBack) { Text("返回") }
        notice?.let { Text(it) }
        Row {
            FilterChip(selected = mode == LocalUsageMode.WORK, onClick = { mode = LocalUsageMode.WORK }, label = { Text("工作") })
            Spacer(Modifier.width(8.dp))
            FilterChip(selected = mode == LocalUsageMode.CHAT, onClick = { mode = LocalUsageMode.CHAT }, label = { Text("人物聊天") })
        }
        Button(enabled = !state.loading, onClick = {
            val receipt = owner.submit(SessionUiCommand.Create(
                state.identity, LocalConversationMode.INDEPENDENT, mode))
            notice = when (receipt) {
                UiCommandReceipt.Accepted -> { onCreated(); "已创建新会话" }
                is UiCommandReceipt.Rejected -> "无法创建：${receipt.reason}"
                is UiCommandReceipt.Failed -> receipt.message ?: "新建失败"
                else -> "会话创建结果未确认，请检查列表，不要重复创建"
            }
        }) { Text("创建独立会话") }
    }
}

/** Work planning is an Owner policy mutation, not an authored document action. */
@Composable
internal fun NextWorkPolicyScene(owner: WorkPolicyUiPort, onBack: () -> Unit) {
    val state by owner.snapshots.collectAsStateWithLifecycle(initialValue = owner.snapshot())
    var notice by remember { mutableStateOf<String?>(null) }
    var requested by remember { mutableStateOf<Boolean?>(null) }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Work 策略", style = MaterialTheme.typography.titleLarge)
        TextButton(onClick = onBack) { Text("返回") }
        Text("计划模式：${if (state.planMode) "开启" else "关闭"}")
        Text("当前审批规则：${state.approvalMode}")
        Text("设备授权：${if (state.deviceApprovalLease) "本轮有效" else "未启用"}")
        notice?.let { Text(it) }
        Button(enabled = state.identity != null, onClick = { requested = !state.planMode }) {
            Text(if (state.planMode) "关闭计划模式" else "开启计划模式")
        }
        if (state.deviceApprovalLease && state.identity != null) {
            TextButton(onClick = {
                notice = when (val result = owner.submit(
                    WorkPolicyUiCommand.RevokeDeviceTurn(state.identity!!))) {
                    UiCommandReceipt.Accepted -> "已提交撤销设备本轮权限"
                    is UiCommandReceipt.Rejected -> "撤销失败：${result.reason}"
                    is UiCommandReceipt.Failed -> result.message ?: "撤销失败"
                    else -> "撤销状态未知"
                }
            }) { Text("撤销设备授权") }
        }
    }
    requested?.let { desired ->
        AlertDialog(onDismissRequest = { requested = null }, title = { Text("修改工作模式？") },
            text = { Text("仅切换当前会话的 Work Owner 策略。") },
            confirmButton = { TextButton(onClick = {
                requested = null
                val identity = state.identity ?: return@TextButton
                notice = when (val result = owner.submit(
                    WorkPolicyUiCommand.SetPlanMode(identity, desired))) {
                    UiCommandReceipt.Accepted -> "设置已提交"
                    is UiCommandReceipt.Rejected -> "模式未切换：${result.reason}"
                    is UiCommandReceipt.Failed -> result.message ?: "模式未切换"
                    else -> "模式切换状态未知"
                }
            }) { Text("确认切换") } },
            dismissButton = { TextButton(onClick = { requested = null }) { Text("取消") } })
    }
}

/** Shell welcome and empty views reflect the real Session Owner catalog. */
@Composable
internal fun NextSessionLandingScene(owner: SessionUiPort, onBack: () -> Unit,
    onCreate: () -> Unit, onOpen: () -> Unit,
) {
    val snapshot by owner.snapshots.collectAsStateWithLifecycle(initialValue = owner.snapshot())
    var notice by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(if (snapshot.sessions.isEmpty()) "开始使用" else "你的会话",
            style = MaterialTheme.typography.titleLarge)
        TextButton(onClick = onBack) { Text("返回") }
        snapshot.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        notice?.let { Text(it) }
        Text("会话仍由本机 Session Owner 管理，不会在 UI 中创建第二份列表。")
        Button(onClick = onCreate) { Text("新建会话") }
        LazyColumn {
            items(snapshot.sessions, key = { it.id }) { session ->
                TextButton(onClick = {
                    when (val receipt = owner.submit(SessionUiCommand.Open(session.id))) {
                        UiCommandReceipt.Accepted -> onOpen()
                        is UiCommandReceipt.Rejected -> notice = "无法打开：${receipt.reason}"
                        is UiCommandReceipt.Failed -> notice = receipt.message ?: "无法打开"
                        else -> notice = "会话切换未确认"
                    }
                }) { Text(session.title) }
            }
        }
    }
}

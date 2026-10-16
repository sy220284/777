package com.labteto.dshmobile.uipack.v6

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labteto.dshmobile.uicontract.*
import com.labteto.dshmobile.local.interaction.LocalApprovalMode
import com.shenyan.designsystem.components.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Separate message action, edit, reader and branch operations; each uses the same Chat/Work owners. */
enum class V6MessageOperation { ACTIONS, EDIT, READER, BRANCHES }
@Composable
fun V6MessageOperationScene(
    ports: V6ConversationPorts,
    workMode: Boolean,
    mode: V6MessageOperation,
    onBack: () -> Unit,
) {
    val chat by ports.chat.snapshots.collectAsStateWithLifecycle(initialValue = ports.chat.snapshot())
    val work by ports.work.snapshots.collectAsStateWithLifecycle(initialValue = ports.work.snapshot())
    val target = if (workMode) work.identity else chat.identity
    val snapshots = if (workMode) work.messages else chat.messages
    val phase = if (workMode) work.phase else chat.phase
    val scope = rememberCoroutineScope()
    var focusId by remember(target, mode) { mutableStateOf<String?>(null) }
    var edit by remember(target, focusId) { mutableStateOf("") }
    var feedback by remember(target) { mutableStateOf<String?>(null) }
    var history by remember(target) { mutableStateOf(emptyList<UiMessageSnapshot>()) }
    var cursor by remember(target) { mutableStateOf<UiTranscriptCursor?>(null) }
    var historyRead by remember(target) { mutableStateOf(false) }
    var loading by remember(target) { mutableStateOf(false) }
    val focused = snapshots.firstOrNull { it.id == focusId } ?: snapshots.lastOrNull()
    val ready = target != null && phase == UiExecutionPhase.IDLE &&
        (if (workMode) work.status else chat.status) != UiSceneStatus.LOADING
    fun send(command: () -> UiCommandReceipt) {
        if (!ready || target == null || (if (workMode) ports.work.snapshot().identity
            else ports.chat.snapshot().identity) != target) {
            feedback = "会话已变更，请刷新后操作"
            return
        }
        feedback = try { describeV6ConversationReceipt(command()) }
        catch (failure: Exception) { failure.message ?: "消息操作未完成" }
    }
    fun readOlder(reset: Boolean) {
        val id = target ?: return
        val reader = ports.history ?: return
        if (loading || (!reset && historyRead && cursor == null)) return
        loading = true
        scope.launch {
            try {
                val page = withContext(Dispatchers.IO) {
                    reader.messagePage(id.sessionId, if (reset) null else cursor, 100)
                }
                if ((if (workMode) ports.work.snapshot().identity
                    else ports.chat.snapshot().identity) != id) return@launch
                history = if (reset) page.messages
                    else (page.messages + history).distinctBy { it.id }
                cursor = page.nextCursor
                historyRead = true
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { feedback = failure.message ?: "历史读取失败" }
            finally { loading = false }
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(when(mode) {
            V6MessageOperation.ACTIONS -> "消息操作"
            V6MessageOperation.EDIT -> "编辑消息"
            V6MessageOperation.READER -> "消息阅读"
            V6MessageOperation.BRANCHES -> "回复版本"
        }, style = MaterialTheme.typography.titleLarge)
        SyButton("返回", onBack, style = SyButtonStyle.GHOST)
        feedback?.let { SyNote(it) }
        if (target == null) SyNote("当前没有可以操作的会话")
        if (mode == V6MessageOperation.READER) {
            if (ports.history != null) {
                SyButton("加载历史", { readOlder(!historyRead) }, enabled = !loading)
                if (historyRead && cursor != null)
                    SyButton("加载更早记录", { readOlder(false) }, enabled = !loading,
                        style = SyButtonStyle.SECONDARY)
            }
            val visible = (history + snapshots).distinctBy { it.id }
            visible.forEach { message ->
                SyGlassCard(Modifier.fillMaxWidth()) {
                    Text(message.role.name, style = MaterialTheme.typography.labelMedium)
                    SelectionContainer { Text(message.text) }
                }
            }
        } else {
            snapshots.forEach { message ->
                SyButton(message.role.name + " · " + message.text.take(48), {
                    focusId = message.id
                    edit = message.editableText.orEmpty()
                }, style = if (focused?.id == message.id) SyButtonStyle.PRIMARY
                    else SyButtonStyle.SECONDARY)
            }
            focused?.let { message ->
                SyGlassCard(Modifier.fillMaxWidth()) {
                    SelectionContainer { Text(message.text) }
                    if (mode == V6MessageOperation.EDIT ||
                        mode == V6MessageOperation.ACTIONS) {
                        if (message.role == UiMessageRole.USER && message.editableText != null) {
                            SyInput(edit, { edit = it }, label = "修改用户消息", singleLine = false)
                            SyButton("提交编辑", {
                                send {
                                    if (workMode) ports.work.submit(
                                        WorkConversationUiCommand.EditUserMessage(target!!, message.id, edit))
                                    else ports.chat.submit(
                                        ChatUiCommand.EditUserMessage(target!!, message.id, edit))
                                }
                            }, enabled = ready && edit.isNotBlank())
                        }
                        if (message.role == UiMessageRole.ASSISTANT && !message.proactive &&
                            message.id == snapshots.lastOrNull()?.id) {
                            SyButton("重新生成", {
                                send {
                                    if (workMode) ports.work.submit(
                                        WorkConversationUiCommand.RegenerateReply(target!!, message.id))
                                    else ports.chat.submit(
                                        ChatUiCommand.RegenerateReply(target!!, message.id))
                                }
                            }, enabled = ready, style = SyButtonStyle.SECONDARY)
                        }
                    }
                    if (!workMode && (mode == V6MessageOperation.BRANCHES ||
                        mode == V6MessageOperation.ACTIONS)) {
                        message.branch?.let { branch ->
                            Text("当前分支 ${branch.index + 1}/${branch.count}")
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                SyButton("前一版", {
                                    send { ports.chat.submit(ChatUiCommand.SelectVariant(
                                        target!!, message.id, branch.index - 1)) }
                                }, enabled = ready && branch.index > 0,
                                    style = SyButtonStyle.GHOST)
                                SyButton("下一版", {
                                    send { ports.chat.submit(ChatUiCommand.SelectVariant(
                                        target!!, message.id, branch.index + 1)) }
                                }, enabled = ready && branch.index + 1 < branch.count,
                                    style = SyButtonStyle.GHOST)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Suggestions are Owner-projected text; tapping only prepares the user's ordinary draft. */
@Composable
fun V6ReplySuggestionsScene(
    owner: ReplySuggestionsUiPort,
    drafts: ConversationDraftUiPort,
    onBack: () -> Unit,
) {
    val state by owner.snapshots.collectAsStateWithLifecycle(
        initialValue = UiReplySuggestionsSnapshot(null, emptyList()))
    var busy by remember(state.identity) { mutableStateOf(false) }
    var notice by remember(state.identity) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("回复建议", style = MaterialTheme.typography.titleLarge)
        SyButton("返回", onBack, style = SyButtonStyle.GHOST)
        notice?.let { SyNote(it) }
        val identity = state.identity
        if (identity == null) SyNote("没有有效聊天会话")
        else {
            SyButton("获取本轮回复建议", {
                if (!busy) {
                    busy = true
                    scope.launch {
                        try { notice = describeV6ConversationReceipt(owner.generate(identity)) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) { notice = failure.message ?: "建议生成失败" }
                        finally { busy = false }
                    }
                }
            }, enabled = !busy)
            state.values.forEach { idea ->
                SyGlassCard(Modifier.fillMaxWidth()) {
                    Text(idea.label, style = MaterialTheme.typography.titleMedium)
                    Text(idea.text)
                    SyButton("填写到普通草稿", {
                        drafts.updateDraft(identity.sessionId, idea.text)
                        notice = "已填写草稿，发送前仍可修改"
                    }, style = SyButtonStyle.SECONDARY)
                }
            }
        }
    }
}

/** Work approval policy remains at WorkPolicyUiPort: toggles aren't authorizations. */
@Composable
fun V6WorkSteeringScene(
    policy: WorkPolicyUiPort,
    onBack: () -> Unit,
) {
    val state by policy.snapshots.collectAsStateWithLifecycle(
        initialValue = policy.snapshot())
    var feedback by remember(state.identity) { mutableStateOf<String?>(null) }
    var pendingMode by remember(state.identity) { mutableStateOf<LocalApprovalMode?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Work 运行策略", style = MaterialTheme.typography.titleLarge)
        SyButton("返回", onBack, style = SyButtonStyle.GHOST)
        feedback?.let { SyNote(it) }
        Text("当前审批模式：" + state.approvalMode.name)
        Text("设备授权租约：" + (if (state.deviceApprovalLease) "当前生效" else "未生效"))
        val id = state.identity
        Text("审批策略变更仅调整后续操作规则；当前等待中的工具调用仍须单独审批。")
        LocalApprovalMode.entries.forEach { choice ->
            SyButton(
                when (choice) {
                    LocalApprovalMode.DEFAULT -> "默认审批模式"
                    LocalApprovalMode.MANUAL -> "逐次人工确认"
                    LocalApprovalMode.AUTO -> "符合策略的请求自动批准"
                }, onClick = {
                    if (policy.snapshot().approvalMode != choice) pendingMode = choice
                }, enabled = choice != state.approvalMode,
                style = if (choice == state.approvalMode) SyButtonStyle.PRIMARY
                    else SyButtonStyle.SECONDARY,
            )
        }
        if (id != null) {
            SySwitch("计划优先", state.planMode, { enabled ->
                if (policy.snapshot().identity != id)
                    feedback = "会话已变更"
                else feedback = describeV6ConversationReceipt(
                    policy.submit(WorkPolicyUiCommand.SetPlanMode(id, enabled)))
            })
            if (state.deviceApprovalLease) SyButton("撤销本轮设备授权", {
                feedback = describeV6ConversationReceipt(
                    policy.submit(WorkPolicyUiCommand.RevokeDeviceTurn(id)))
            }, style = SyButtonStyle.DANGER)
        }
        SyNote("等待中的工具审批使用原 Work Owner 的 pendingId；切换模式不会直接调用批准命令。")
    }
    pendingMode?.let { next ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pendingMode = null },
            title = { Text("确认变更 Work 审批模式") },
            text = { Text("将后续请求审批模式设为 ${next.name}。AUTO 可能使符合原 Owner 策略的请求自动获批；正在等待的审批不会被此操作代为同意。") },
            confirmButton = { androidx.compose.material3.TextButton(onClick = {
                pendingMode = null
                val now = policy.snapshot()
                if (now.identity != state.identity) {
                    feedback = "会话已切换，审批策略未更改"
                } else {
                    feedback = describeV6ConversationReceipt(
                        policy.submit(WorkPolicyUiCommand.SetApprovalMode(now.identity, next)))
                }
            }) { Text("确认变更") } },
            dismissButton = { androidx.compose.material3.TextButton(
                onClick = { pendingMode = null }) { Text("取消") } },
        )
    }
}

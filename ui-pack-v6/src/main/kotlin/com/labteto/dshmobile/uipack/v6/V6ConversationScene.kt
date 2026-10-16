package com.labteto.dshmobile.uipack.v6

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labteto.dshmobile.uicontract.AttachmentUiPort
import com.labteto.dshmobile.uicontract.ChatUiCommand
import com.labteto.dshmobile.uicontract.ChatUiPort
import com.labteto.dshmobile.uicontract.ConversationDraftUiPort
import com.labteto.dshmobile.uicontract.ConversationReadingUiPort
import com.labteto.dshmobile.uicontract.ImportUiAttachmentCommand
import com.labteto.dshmobile.uicontract.UiAttachmentReceipt
import com.labteto.dshmobile.uicontract.UiCommandReceipt
import com.labteto.dshmobile.uicontract.UiExecutionPhase
import com.labteto.dshmobile.uicontract.UiMessageEditOutcome
import com.labteto.dshmobile.uicontract.UiMessageRole
import com.labteto.dshmobile.uicontract.UiMessageSnapshot
import com.labteto.dshmobile.uicontract.UiReadingAnchor
import com.labteto.dshmobile.uicontract.UiSceneStatus
import com.labteto.dshmobile.uicontract.UiSessionIdentity
import com.labteto.dshmobile.uicontract.UiMessageHistoryPort
import com.labteto.dshmobile.uicontract.UiTranscriptCursor
import com.labteto.dshmobile.uicontract.WorkConversationUiCommand
import com.labteto.dshmobile.uicontract.WorkConversationUiPort
import com.labteto.dshmobile.uihost.rememberUiAttachmentPicker
import com.shenyan.designsystem.components.SyButton
import com.shenyan.designsystem.components.SyButtonStyle
import com.shenyan.designsystem.components.SyGlassCard
import com.shenyan.designsystem.components.SyInput
import com.shenyan.designsystem.components.SyNote
import com.shenyan.designsystem.foundation.LocalSyColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Single Owner-backed UI binding for V6, never a second business engine. */
data class V6ConversationPorts(
    val chat: ChatUiPort,
    val work: WorkConversationUiPort,
    val drafts: ConversationDraftUiPort,
    val reading: ConversationReadingUiPort,
    val attachments: AttachmentUiPort? = null,
    val history: UiMessageHistoryPort? = null,
    val workMode: () -> Boolean,
    val workModeUpdates: Flow<Boolean>? = null,
)

internal fun describeV6ConversationReceipt(receipt: UiCommandReceipt): String = when (receipt) {
    UiCommandReceipt.Accepted -> "请求已受理，尚不代表执行完成"
    UiCommandReceipt.Queued -> "请求已排队，等待原业务调度"
    is UiCommandReceipt.Rejected -> "未执行：${receipt.reason.name}"
    is UiCommandReceipt.Failed -> receipt.message
    UiCommandReceipt.NotAdmitted -> "当前操作未获准执行"
    is UiCommandReceipt.MessageEdited -> when (receipt.result) {
        UiMessageEditOutcome.SENT -> "修改已提交，新回复正在处理"
        UiMessageEditOutcome.COMMITTED_NOT_STARTED -> "修改已保存，回复尚未启动"
        else -> "消息修改未完成：${receipt.result.name}"
    }
    UiCommandReceipt.OutcomeUnknown -> "结果未确认，查看消息状态后再决定是否重试"
}

internal fun acceptedV6ConversationReceipt(receipt: UiCommandReceipt): Boolean =
    receipt == UiCommandReceipt.Accepted || receipt == UiCommandReceipt.Queued

/**
 * V6-native conversation scene. Sends/edits/history/attachments/approvals remain
 * Owner commands; a UI receipt never pretends to prove completion.
 * More advanced tool/media/document scenes are intentionally NOT registered yet.
 */
@Composable
fun V6ConversationScene(
    ports: V6ConversationPorts,
    workMode: Boolean,
    onBack: () -> Unit,
) {
    val chat by ports.chat.snapshots.collectAsStateWithLifecycle(initialValue = ports.chat.snapshot())
    val work by ports.work.snapshots.collectAsStateWithLifecycle(initialValue = ports.work.snapshot())
    val identity = if (workMode) work.identity else chat.identity
    val status = if (workMode) work.status else chat.status
    val phase = if (workMode) work.phase else chat.phase
    val hot = if (workMode) work.messages else chat.messages
    val available = if (workMode) work.send.available else chat.send.available
    val rejection = if (workMode) work.send.reason else chat.send.reason
    val queued = if (workMode) work.queuedInputCount else chat.queuedInputCount
    val ownerError = if (workMode) work.error else chat.error
    val scope = rememberCoroutineScope()
    val drafts by ports.drafts.drafts.collectAsStateWithLifecycle()
    val attachments by ports.drafts.attachments.collectAsStateWithLifecycle()
    val draft = drafts[identity?.sessionId].orEmpty()
    val imported = attachments[identity?.sessionId].orEmpty()
    var feedback by remember(identity) { mutableStateOf<String?>(null) }
    var editing by remember(identity) { mutableStateOf<UiMessageSnapshot?>(null) }
    var editText by remember(identity) { mutableStateOf("") }
    var questionText by remember(work.question?.pendingId) { mutableStateOf("") }
    var older by remember(identity) { mutableStateOf(emptyList<UiMessageSnapshot>()) }
    var cursor by remember(identity) { mutableStateOf<UiTranscriptCursor?>(null) }
    var hasOlder by remember(identity) { mutableStateOf(ports.history != null) }
    var loadingHistory by remember(identity) { mutableStateOf(false) }
    var importing by remember(identity) { mutableStateOf(false) }
    var pickerIdentity by remember(identity) { mutableStateOf<UiSessionIdentity?>(null) }
    val list = rememberLazyListState()
    val seen = hot.map { it.id }.toSet()
    val messages = older.filterNot { it.id in seen } + hot
    val ids = messages.map { it.id }.toSet()
    var anchorRestored by remember(identity) { mutableStateOf(false) }

    fun show(receipt: UiCommandReceipt) { feedback = describeV6ConversationReceipt(receipt) }

    fun loadOlder() {
        val target = identity ?: return
        val owner = ports.history ?: return
        if (loadingHistory || !hasOlder) return
        loadingHistory = true
        scope.launch {
            try {
                val page = withContext(Dispatchers.IO) { owner.messagePage(target.sessionId, cursor, 100) }
                val current = if (workMode) ports.work.snapshot().identity else ports.chat.snapshot().identity
                if (current == target) {
                    older = (page.messages + older).distinctBy { it.id }
                    cursor = page.nextCursor
                    hasOlder = page.nextCursor != null
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { feedback = failure.message ?: "历史读取失败" }
            finally { loadingHistory = false }
        }
    }

    LaunchedEffect(identity?.sessionId, hot.firstOrNull()?.id, anchorRestored) {
        val sid = identity?.sessionId ?: return@LaunchedEffect
        if (!anchorRestored && status != UiSceneStatus.LOADING && messages.isNotEmpty()) {
            val anchor = ports.reading.anchor(sid)
            val index = messages.indexOfFirst { it.id == anchor?.messageId }
            list.scrollToItem(
                if (index >= 0 && anchor?.followingTail == false) index + 1
                else messages.size, if (index >= 0 && anchor?.followingTail == false)
                    anchor.offsetPx.coerceAtLeast(0) else 0,
            )
            anchorRestored = true
        }
    }
    LaunchedEffect(identity?.sessionId, anchorRestored, messages) {
        val sid = identity?.sessionId ?: return@LaunchedEffect
        if (!anchorRestored) return@LaunchedEffect
        snapshotFlow {
            val msg = list.layoutInfo.visibleItemsInfo.firstOrNull { item ->
                (item.key as? String)?.removePrefix("message:") in ids &&
                    (item.key as? String)?.startsWith("message:") == true
            }
            UiReadingAnchor(
                (msg?.key as? String)?.removePrefix("message:"),
                (- (msg?.offset ?: 0)).coerceAtLeast(0),
                !list.canScrollForward,
            )
        }.collect { anchor ->
            if (anchor.messageId != null || anchor.followingTail)
                ports.reading.rememberAnchor(sid, anchor)
        }
    }

    val picker = rememberUiAttachmentPicker { uri ->
        val target = pickerIdentity
        pickerIdentity = null
        val importOwner = ports.attachments
        if (uri != null && target != null && importOwner != null) {
            importing = true
            scope.launch {
                try {
                    val result = importOwner.import(ImportUiAttachmentCommand(target, uri))
                    val active = (if (workMode) ports.work.snapshot().identity else ports.chat.snapshot().identity) == target
                    if (active) when (result) {
                        is UiAttachmentReceipt.Imported ->
                            ports.drafts.addAttachment(target.sessionId, result.attachment)
                        is UiAttachmentReceipt.Rejected ->
                            feedback = "附件导入未获准：${result.reason.name}"
                        is UiAttachmentReceipt.Failed ->
                            feedback = result.message ?: "附件读取失败"
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { feedback = failure.message ?: "附件导入失败" }
                finally { importing = false }
            }
        }
    }

    if (editing != null) AlertDialog(
        onDismissRequest = { editing = null },
        title = { Text("修改用户消息") },
        text = { SyInput(editText, { editText = it }, label = "修改内容", singleLine = false) },
        confirmButton = { TextButton(onClick = {
            val target = identity ?: return@TextButton
            val source = editing ?: return@TextButton
            val result = if (workMode)
                ports.work.submit(WorkConversationUiCommand.EditUserMessage(target, source.id, editText))
            else ports.chat.submit(ChatUiCommand.EditUserMessage(target, source.id, editText))
            show(result)
            if (result is UiCommandReceipt.MessageEdited &&
                result.result in setOf(UiMessageEditOutcome.SENT, UiMessageEditOutcome.COMMITTED_NOT_STARTED))
                editing = null
        }, enabled = identity != null && editText.isNotBlank()) { Text("保存并重新发送") } },
        dismissButton = { TextButton(onClick = { editing = null }) { Text("取消") } },
    )

    Column(Modifier.fillMaxSize().imePadding().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(if (workMode) "Work" else "聊天", style = MaterialTheme.typography.titleLarge)
            SyButton("返回", onBack, style = SyButtonStyle.GHOST)
        }
        if (identity == null || status == UiSceneStatus.LOADING) {
            SyNote("会话正在切换，等待 Owner 完成载入")
        } else {
            Text("会话 ${identity.sessionId.take(12)} · ${phase.name} · 等待队列 $queued",
                color = LocalSyColors.current.textSecondary,
                style = MaterialTheme.typography.labelMedium)
        }
        LazyColumn(
            state = list, modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (ports.history != null && hasOlder) item(key = "older") {
                SyButton(if (loadingHistory) "读取中" else "加载更早消息",
                    { loadOlder() }, enabled = !loadingHistory && identity != null,
                    style = SyButtonStyle.SECONDARY)
            }
            items(messages, key = { "message:${it.id}" }) { message ->
                SyGlassCard(Modifier.fillMaxWidth()) {
                    Text(when (message.role) {
                        UiMessageRole.USER -> "用户"
                        UiMessageRole.ASSISTANT -> "助手"
                        UiMessageRole.TOOL -> "工具"
                        UiMessageRole.SYSTEM -> "系统"
                    }, style = MaterialTheme.typography.labelMedium,
                        color = LocalSyColors.current.textSecondary)
                    Text(message.text)
                    if (message.role == UiMessageRole.USER && message.editableText != null &&
                        identity != null) SyButton("修改", {
                            editing = message; editText = message.editableText
                        }, style = SyButtonStyle.GHOST)
                    if (message.role == UiMessageRole.ASSISTANT && identity != null) {
                        SyButton("重新生成", {
                            show(if (workMode) ports.work.submit(
                                WorkConversationUiCommand.RegenerateReply(identity, message.id))
                            else ports.chat.submit(ChatUiCommand.RegenerateReply(identity, message.id)))
                        }, style = SyButtonStyle.SECONDARY)
                    }
                    val branch = message.branch
                    if (!workMode && branch != null && branch.count > 1 && identity != null) {
                        Text("版本 ${branch.index + 1}/${branch.count}",
                            style = MaterialTheme.typography.labelMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SyButton("上一版", {
                                show(ports.chat.submit(ChatUiCommand.SelectVariant(
                                    identity, message.id, branch.index - 1)))
                            }, enabled = branch.index > 0, style = SyButtonStyle.GHOST)
                            SyButton("下一版", {
                                show(ports.chat.submit(ChatUiCommand.SelectVariant(
                                    identity, message.id, branch.index + 1)))
                            }, enabled = branch.index + 1 < branch.count, style = SyButtonStyle.GHOST)
                        }
                    }
                }
            }
            if (messages.isEmpty() && status == UiSceneStatus.EMPTY)
                item(key = "empty") { SyNote("当前会话还没有消息") }
        }
        if (workMode && identity != null) {
            work.question?.let { question ->
                SyGlassCard(Modifier.fillMaxWidth()) {
                    Text(question.question, style = MaterialTheme.typography.titleSmall)
                    question.options.forEach { option ->
                        SyButton(option, { show(ports.work.submit(
                            WorkConversationUiCommand.AnswerQuestion(identity, question.pendingId, option))) })
                    }
                    SyInput(questionText, { questionText = it }, label = "回答问题")
                    SyButton("发送答案", { show(ports.work.submit(
                        WorkConversationUiCommand.AnswerQuestion(identity, question.pendingId, questionText))) },
                        enabled = questionText.isNotBlank())
                    SyButton("不回答", { show(ports.work.submit(
                        WorkConversationUiCommand.AnswerQuestion(identity, question.pendingId, null))) },
                        style = SyButtonStyle.GHOST)
                }
            }
            work.approval?.let { approval ->
                SyGlassCard(Modifier.fillMaxWidth()) {
                    Text("需要明确授权 · ${approval.toolName}",
                        style = MaterialTheme.typography.titleSmall)
                    Text(approval.summary)
                    Text(approval.arguments)
                    Text("风险级别：${approval.impact.name}")
                    SyButton("批准此次调用", {
                        show(ports.work.submit(WorkConversationUiCommand.AnswerApproval(
                            identity, approval.pendingId, true)))
                    })
                    SyButton("拒绝", {
                        show(ports.work.submit(WorkConversationUiCommand.AnswerApproval(
                            identity, approval.pendingId, false)))
                    }, style = SyButtonStyle.SECONDARY)
                }
            }
        }
        ownerError?.let { SyNote(it) }
        feedback?.let { Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
        imported.forEach { file ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(file.name, modifier = Modifier.weight(1f))
                SyButton("移除", {
                    identity?.let { ports.drafts.removeAttachment(it.sessionId, file.relativePath) }
                }, style = SyButtonStyle.GHOST)
            }
        }
        SyInput(draft, { value ->
            identity?.let { ports.drafts.updateDraft(it.sessionId, value) }
        }, label = "输入消息", enabled = identity != null, singleLine = false)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (ports.attachments != null) SyButton("添加附件", {
                pickerIdentity = identity
                picker()
            },
                enabled = identity != null && !importing, style = SyButtonStyle.SECONDARY)
            if (phase == UiExecutionPhase.RUNNING && identity != null) SyButton("停止", {
                show(if (workMode) ports.work.submit(WorkConversationUiCommand.Stop(identity))
                    else ports.chat.submit(ChatUiCommand.Stop(identity)))
            }, style = SyButtonStyle.SECONDARY)
            SyButton("发送", {
                val target = identity ?: return@SyButton
                val receipt = if (workMode)
                    ports.work.submit(WorkConversationUiCommand.SendMessage(target, draft, imported))
                else ports.chat.submit(ChatUiCommand.SendMessage(target, draft, imported))
                show(receipt)
                if (acceptedV6ConversationReceipt(receipt)) {
                    ports.drafts.updateDraft(target.sessionId, "")
                    ports.drafts.clearAttachments(target.sessionId)
                }
            }, enabled = identity != null && available && draft.isNotBlank() && !importing)
        }
        if (!available && rejection != null)
            Text("发送暂不可用：${rejection.name}", color = LocalSyColors.current.textSecondary)
    }
}

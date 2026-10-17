package com.labteto.dshmobile.uipack.zhaoxi.scenes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.labteto.dshmobile.uicontract.ChatUiCommand
import com.labteto.dshmobile.uicontract.ChatUiPort
import com.labteto.dshmobile.uicontract.UiCommandReceipt
import com.labteto.dshmobile.uicontract.UiCommandRejection
import com.labteto.dshmobile.uicontract.UiExecutionPhase
import com.labteto.dshmobile.uicontract.UiMessageRole
import com.labteto.dshmobile.uicontract.UiMessageSnapshot
import com.labteto.dshmobile.uicontract.UiSceneId
import com.labteto.dshmobile.uicontract.WorkConversationUiCommand
import com.labteto.dshmobile.uicontract.WorkConversationUiPort
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxApprovalCard
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxBanner
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxCap
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxCapBar
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxComposer
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxDialog
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxMessageRow
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxModeSwitch
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxQuestionCard
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxTimeSeparator
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxTone
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxTypingIndicator
import com.labteto.dshmobile.uipack.zhaoxi.components.rejectionHint
import com.labteto.dshmobile.uipack.zhaoxi.theme.ZhaoxiTheme
import com.labteto.dshmobile.uihost.UiSceneRenderScope

/**
 * 会话域场景（E8.14 实装）：conversation.chat / work / header / timeline / composer /
 * reply_status / message_actions / edit / reader / model_picker / branches。
 * 行为基准 = V6 会话场景：发送/编辑/变体/再生成/停止只经端口 submit，回执呈现不伪造。
 */

/** conversation.chat — 聊天会话主场景。 */
@Composable
fun ZhaoxiChatScene(chat: ChatUiPort, authorName: String, scope: UiSceneRenderScope) {
    val snap by chat.snapshots.collectAsState(initial = chat.snapshot())
    var draft by remember { mutableStateOf("") }
    var rejection by remember { mutableStateOf<UiCommandRejection?>(null) }
    var editingTarget by remember { mutableStateOf<UiMessageSnapshot?>(null) }
    Column {
        rejection?.let { r ->
            ZxBanner("未送达 · ${rejectionHint(r)} · 草稿已保留", ZxTone.Err)
            Spacer(Modifier.height(4.dp))
        }
        ZxStateContent(snap.status, snap.error, "还没有消息", "从下面发第一句开始", onRetry = {}) {
            snap.group?.let { g ->
                ZxBanner("群聊 · ${g.memberNames.joinToString("、")}", ZxTone.Info)
                Spacer(Modifier.height(6.dp))
            }
            snap.messages.forEach { m ->
                ZxMessageRow(m, authorName,
                    onLongPress = { editingTarget = it },
                    onBranchSelect = { target ->
                        snap.identity?.let { id ->
                            chat.submit(ChatUiCommand.SelectVariant(id, m.id, target))
                        }
                    })
            }
            if (snap.phase == UiExecutionPhase.RUNNING) ZxTypingIndicator()
            if (snap.queuedInputCount > 0)
                ZxBanner("⇄ 已排队 ${snap.queuedInputCount} 条 · 队列满时将拒收", ZxTone.Warn)
        }
        editingTarget?.let { m ->
            ZxDialog(
                title = if (m.role == UiMessageRole.USER) "修改并重发" else "消息操作",
                body = m.text.take(200),
                confirmText = if (m.role == UiMessageRole.USER) "重发" else "重新生成",
                onConfirm = {
                    snap.identity?.let { id ->
                        if (m.role == UiMessageRole.USER)
                            chat.submit(ChatUiCommand.EditUserMessage(id, m.id, m.editableText ?: m.text))
                        else chat.submit(ChatUiCommand.RegenerateReply(id, m.id))
                    }
                    editingTarget = null
                },
                onDismiss = { editingTarget = null },
            )
        }
        ZxComposer(
            draft = draft, onDraftChange = { draft = it },
            send = snap.send,
            running = snap.phase == UiExecutionPhase.RUNNING,
            onStop = { snap.identity?.let { id -> chat.submit(ChatUiCommand.Stop(id)) } },
            onSend = {
                val id = snap.identity
                if (id == null) {
                    rejection = UiCommandRejection.UNCONFIGURED  // 未激活：保留草稿
                } else when (val r = chat.submit(ChatUiCommand.SendText(id, draft))) {
                    UiCommandReceipt.Accepted, UiCommandReceipt.Queued -> { draft = ""; rejection = null }
                    is UiCommandReceipt.Rejected -> rejection = r.reason  // 拒收：保留草稿
                    else -> rejection = UiCommandRejection.UNSUPPORTED
                }
            },
            placeholder = "问点什么",
        )
    }
}

/** conversation.work — 工作会话主场景（审批卡/提问卡槽位 + 计划入口）。 */
@Composable
fun ZhaoxiWorkScene(work: WorkConversationUiPort, scope: UiSceneRenderScope) {
    val snap by work.snapshots.collectAsState(initial = work.snapshot())
    var draft by remember { mutableStateOf("") }
    var rejection by remember { mutableStateOf<UiCommandRejection?>(null) }
    var questionAnswer by remember { mutableStateOf("") }
    Column {
        rejection?.let { r ->
            ZxBanner("未送达 · ${rejectionHint(r)} · 草稿已保留", ZxTone.Err)
            Spacer(Modifier.height(4.dp))
        }
        ZxStateContent(snap.status, snap.error, "还没有任务", "描述一个目标，工作模式先出方案再执行", onRetry = {}) {
            snap.messages.forEach { m ->
                // 工作会话契约无 SelectVariant：分支切换器只读展示，不提交任何命令
                ZxMessageRow(m, "工作", onLongPress = {}, onBranchSelect = {})
            }
            snap.approval?.let { ap ->
                Spacer(Modifier.height(6.dp))
                ZxApprovalCard(ap, expired = snap.phase == UiExecutionPhase.IDLE && ap.pendingId.isBlank()) { approved ->
                    snap.identity?.let { id ->
                        work.submit(WorkConversationUiCommand.AnswerApproval(id, ap.pendingId, approved))
                    }
                }
            }
            snap.question?.let { q ->
                Spacer(Modifier.height(6.dp))
                ZxQuestionCard(q, questionAnswer, { questionAnswer = it }) { answer ->
                    val id = snap.identity
                    if (id != null) {
                        when (val r = work.submit(WorkConversationUiCommand.AnswerQuestion(id, q.pendingId, answer))) {
                            UiCommandReceipt.Accepted, UiCommandReceipt.Queued -> questionAnswer = ""
                            is UiCommandReceipt.Rejected -> rejection = r.reason
                            else -> rejection = UiCommandRejection.UNSUPPORTED
                        }
                    }
                }
            }
            if (snap.phase == UiExecutionPhase.RUNNING) ZxTypingIndicator()
            if (snap.queuedInputCount > 0)
                ZxBanner("⇄ 已排队 ${snap.queuedInputCount} 条 · 队列满时将拒收", ZxTone.Warn)
        }
        ZxComposer(
            draft = draft, onDraftChange = { draft = it },
            send = snap.send,
            running = snap.phase == UiExecutionPhase.RUNNING,
            onStop = { snap.identity?.let { id -> work.submit(WorkConversationUiCommand.Stop(id)) } },
            onSend = {
                val id = snap.identity
                if (id == null) {
                    rejection = UiCommandRejection.UNCONFIGURED
                } else when (val r = work.submit(WorkConversationUiCommand.SendText(id, draft))) {
                    UiCommandReceipt.Accepted, UiCommandReceipt.Queued -> { draft = ""; rejection = null }
                    is UiCommandReceipt.Rejected -> rejection = r.reason
                    else -> rejection = UiCommandRejection.UNSUPPORTED
                }
            },
            placeholder = "描述任务目标",
        )
    }
}

/** conversation.header — 顶栏槽位（模式分段 + 模型胶囊 + 会话操作）。 */
@Composable
fun ZhaoxiConversationHeaderScene(
    title: String,
    modelLabel: String,
    workMode: Boolean,
    onModeChange: (Boolean) -> Unit,
    scope: UiSceneRenderScope,
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), fontSize = 15.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, color = ZhaoxiTheme.colors.ink)
        ZxModeSwitch(workMode, onModeChange)
    }
}

/** conversation.reply_status — 运行/排队/steer 状态条。 */
@Composable
fun ZhaoxiReplyStatusScene(phase: UiExecutionPhase, queuedInputCount: Int) {
    when {
        queuedInputCount > 0 -> ZxBanner("⇄ 已排队 $queuedInputCount 条 · 队列满时将拒收", ZxTone.Warn)
        phase == UiExecutionPhase.RUNNING ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ZxTypingIndicator()
                Text("运行中 · 可 steer", fontSize = 11.sp, color = ZhaoxiTheme.colors.warn)
            }
        phase == UiExecutionPhase.WAITING_FOR_USER -> ZxBanner("需要你处理 · 审批与提问待决定", ZxTone.Warn)
        else -> {}
    }
}

/** conversation.reader — 沉浸阅读。 */
@Composable
fun ZhaoxiReaderScene(title: String, text: String, scope: UiSceneRenderScope) {
    ZxPage(title, onBack = { scope.onBack() }) {
        Spacer(Modifier.height(8.dp))
        Text(text, fontSize = 16.sp, lineHeight = 28.sp, color = ZhaoxiTheme.colors.ink)
        com.labteto.dshmobile.uipack.zhaoxi.components.ZxFooterNote("长按可选中复制")
    }
}

/** conversation.model_picker — 模型选择层。 */
@Composable
fun ZhaoxiModelPickerScene(
    models: List<Triple<String, String, Boolean>>,
    onSelect: (String) -> Unit,
    onOpenModelSettings: () -> Unit,
    scope: UiSceneRenderScope,
) {
    ZxPage("选择模型", onBack = { scope.onBack() }) {
        if (models.isEmpty())
            com.labteto.dshmobile.uipack.zhaoxi.components.ZxEmpty(
                "模型清单未注入", "到「设置 · 模型」配置供应商与密钥")
        models.forEach { (id, sub, current) ->
            ZxRow(id, sub, mono = true, onClick = { onSelect(id) }) {
                if (current) Text("✓", color = ZhaoxiTheme.colors.accent)
            }
        }
        ZxNavRow("模型设置与 API Key…", onClick = onOpenModelSettings)
    }
}

/** conversation.composer 独立槽位（被 conversation.chat/work 内嵌复用）。 */
@Composable
fun ZhaoxiComposerSlotScene(
    send: com.labteto.dshmobile.uicontract.UiActionAvailability,
    running: Boolean,
    placeholder: String,
    /** 返回 true 才清空草稿（回执 Accepted/Queued）；false 保留草稿并提示。 */
    onSendText: (String) -> Boolean,
    onStop: () -> Unit,
) {
    var draft by remember { mutableStateOf("") }
    var rejected by remember { mutableStateOf(false) }
    Column {
        if (rejected) {
            ZxBanner("未送达 · 草稿已保留", ZxTone.Err)
            Spacer(Modifier.height(4.dp))
        }
        ZxCapBar(
            listOf(ZxCap("reason", "推理", false), ZxCap("temp", "温度", false)),
            onOpen = {},
        )
        ZxComposer(draft, { draft = it }, send, {
            if (onSendText(draft)) { draft = ""; rejected = false } else rejected = true
        }, placeholder, running = running, onStop = onStop)
    }
}

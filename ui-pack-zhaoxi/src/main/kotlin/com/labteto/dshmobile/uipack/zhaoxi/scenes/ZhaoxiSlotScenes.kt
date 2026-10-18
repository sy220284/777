package com.labteto.dshmobile.uipack.zhaoxi.scenes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.labteto.dshmobile.uicontract.ChatUiPort
import com.labteto.dshmobile.uicontract.ChatUiCommand
import com.labteto.dshmobile.uicontract.DiaryUiPort
import com.labteto.dshmobile.uicontract.UiCommandReceipt
import com.labteto.dshmobile.uicontract.UiCommandRejection
import com.labteto.dshmobile.uicontract.UiMessageSnapshot
import com.labteto.dshmobile.uicontract.ConversationReadingUiPort
import com.labteto.dshmobile.uicontract.UiMessageHistoryPort
import com.labteto.dshmobile.uicontract.UiDocumentUiPort
import com.labteto.dshmobile.uicontract.UiSceneId
import com.labteto.dshmobile.uicontract.MediaUiPort
import com.labteto.dshmobile.uicontract.UiExecutionPhase
import com.labteto.dshmobile.uicontract.WorkConversationUiPort
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxApprovalCard
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxBadge
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxBadgeTone
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxBranchSwitcher
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxButton
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxButtonVariant
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxChip
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxEmpty
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxField
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxFooterNote
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxQuestionCard
import com.labteto.dshmobile.uipack.zhaoxi.theme.ZhaoxiTheme
import com.labteto.dshmobile.uihost.UiSceneRenderScope

/**
 * 槽位与次级场景（E8.14 实装）：
 * 时间线（只读）、消息操作、编辑、分支、审批/提问独立场景、媒体结果、人物档案、日记。
 */

/** conversation.timeline: compatibility route delegates to the SAME turn-keyed reply surface. */
@Composable
fun ZhaoxiTimelineScene(
    chat: ChatUiPort,
    reading: ConversationReadingUiPort,
    history: UiMessageHistoryPort? = null,
    documents: UiDocumentUiPort? = null,
    knownScenes: Set<UiSceneId> = emptySet(),
    media: MediaUiPort? = null,
) {
    val snap by chat.snapshots.collectAsState(initial = chat.snapshot())
    var selectedMessage by remember { mutableStateOf<UiMessageSnapshot?>(null) }
    androidx.compose.foundation.layout.Column(Modifier.fillMaxSize()) {
        selectedMessage?.let { message ->
            androidx.compose.foundation.text.selection.SelectionContainer {
                com.labteto.dshmobile.uipack.zhaoxi.components.ZxRichReply(message.text)
            }
        }
        if (snap.messages.isEmpty() && snap.phase != UiExecutionPhase.RUNNING)
            ZxEmpty("还没有消息", "从下方输入框开始")
        ConversationMessages(snap.identity, snap.messages, reading, history,
            Modifier.weight(1f), onMessage = { selectedMessage = it },
            onBranch = { m, index -> snap.identity?.let {
                chat.submit(ChatUiCommand.SelectVariant(it, m.id, index))
            } }, preview = snap.livePreview,
            running = snap.phase == UiExecutionPhase.RUNNING,
            documents = documents, knownScenes = knownScenes, media = media)
    }
}

/** conversation.message_actions — 长按消息操作层。 */
@Composable
fun ZhaoxiMessageActionsScene(
    message: UiMessageSnapshot,
    scope: UiSceneRenderScope,
    onEdit: () -> Unit = {},
    onRegenerate: () -> Unit = {},
) {
    ZxPage("消息操作", onBack = { scope.onBack() }) {
        Spacer(Modifier.height(8.dp))
        Text(message.text.take(200), fontSize = 13.sp, color = ZhaoxiTheme.colors.ink2)
        Spacer(Modifier.height(10.dp))
        if (message.editableText != null) ZxNavRow("修改并重发", sub = "编辑后作为新分支提交") { onEdit() }
        ZxNavRow("重新生成", sub = "从这条重试回复") { onRegenerate() }
        ZxNavRow("阅读文本") { scope.onNavigate(com.labteto.dshmobile.uicontract.UiSceneId("conversation.reader")) }
        val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
        ZxNavRow("复制文本", sub = "") {
            clipboard.setText(androidx.compose.ui.text.AnnotatedString(message.text))
        }
        ZxFooterNote("操作经 ChatUiCommand · 乐观更新由 Owner 决定")
    }
}

/** conversation.edit — 修改并重发。 */
@Composable
fun ZhaoxiEditScene(
    message: UiMessageSnapshot,
    chat: ChatUiPort,
    scope: UiSceneRenderScope,
    work: WorkConversationUiPort? = null,
) {
    var text by remember { mutableStateOf(message.editableText ?: message.text) }
    var rejection by remember { mutableStateOf<UiCommandRejection?>(null) }
    ZxPage("修改并重发", onBack = { scope.onBack() }) {
        Spacer(Modifier.height(8.dp))
        rejection?.let { r ->
            com.labteto.dshmobile.uipack.zhaoxi.components.ZxBanner(
                "重发被拒 · ${com.labteto.dshmobile.uipack.zhaoxi.components.rejectionHint(r)} · 内容已保留",
                com.labteto.dshmobile.uipack.zhaoxi.components.ZxTone.Err)
            Spacer(Modifier.height(6.dp))
        }
        ZxField(text, { text = it }, Modifier.fillMaxWidth().height(120.dp))
        Spacer(Modifier.height(10.dp))
        ZxButton("重发", {
            val id = work?.snapshot()?.identity ?: chat.snapshot().identity
            if (id == null) rejection = UiCommandRejection.UNCONFIGURED
            else when (val r = if (work != null) work.submit(com.labteto.dshmobile.uicontract.WorkConversationUiCommand.EditUserMessage(id, message.id, text)) else chat.submit(ChatUiCommand.EditUserMessage(id, message.id, text))) {
                UiCommandReceipt.Accepted, UiCommandReceipt.Queued,
                is UiCommandReceipt.MessageEdited -> scope.onBack()
                is UiCommandReceipt.Rejected -> rejection = r.reason
                else -> rejection = UiCommandRejection.UNSUPPORTED
            }
        }, Modifier.fillMaxWidth(), enabled = text.isNotBlank())
        ZxFooterNote("重发产生新分支 · 旧分支保留可切换")
    }
}

/** chat.branches — 分支切换。 */
@Composable
fun ZhaoxiBranchesScene(
    message: UiMessageSnapshot,
    chat: ChatUiPort,
    scope: UiSceneRenderScope,
) {
    val branch = message.branch
    ZxPage("分支", onBack = { scope.onBack() }) {
        Spacer(Modifier.height(24.dp))
        if (branch == null) ZxEmpty("没有分支", "修改重发或重新生成后产生分支")
        else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            ZxBranchSwitcher(branch.index, branch.count) { i ->
                // 身份缺失时不提交；回执由宿主快照回流呈现
                chat.snapshot().identity?.let { id ->
                    chat.submit(ChatUiCommand.SelectVariant(id, message.id, i))
                }
            }
        }
    }
}

/** work.approval — 审批独立场景。 */
@Composable
fun ZhaoxiApprovalScene(work: WorkConversationUiPort, scope: UiSceneRenderScope) {
    val snap by work.snapshots.collectAsState(initial = work.snapshot())
    var receipt by remember { mutableStateOf<UiCommandReceipt?>(null) }
    ZxPage("审批", onBack = { scope.onBack() }) {
        Spacer(Modifier.height(8.dp))
        CommandFeedback(receipt)
        val approval = snap.approval
        if (approval == null) ZxEmpty("没有待审批", "工具调用需要批准时会出现在这里")
        else ZxApprovalCard(approval, expired = false) { approved ->
            snap.identity?.let { id ->
                val result = work.submit(com.labteto.dshmobile.uicontract.WorkConversationUiCommand.AnswerApproval(
                    id, approval.pendingId, approved))
                receipt = result
                if (result.admitted()) scope.onBack()
            }
        }
    }
}

/** work.question — 提问独立场景。 */
@Composable
fun ZhaoxiQuestionScene(work: WorkConversationUiPort, scope: UiSceneRenderScope) {
    val snap by work.snapshots.collectAsState(initial = work.snapshot())
    var answer by remember { mutableStateOf("") }
    ZxPage("提问", onBack = { scope.onBack() }) {
        Spacer(Modifier.height(8.dp))
        val question = snap.question
        if (question == null) ZxEmpty("没有待回答", "Agent 提问时会出现在这里")
        else ZxQuestionCard(question, answer, { answer = it }) { a ->
            snap.identity?.let { id ->
                when (work.submit(com.labteto.dshmobile.uicontract.WorkConversationUiCommand.AnswerQuestion(
                    id, question.pendingId, a))) {
                    UiCommandReceipt.Accepted, UiCommandReceipt.Queued -> scope.onBack()
                    else -> {}  // 拒收：停留在本页，答案不丢
                }
            }
        }
    }
}

/** chat.diary / route.diary — 日记（DiaryUiPort 只读 + 勘误）。 */
@Composable
fun ZhaoxiDiaryScene(diary: DiaryUiPort, galleryId: String, scope: UiSceneRenderScope) {
    val entries by produceState<List<com.labteto.dshmobile.uicontract.UiDiaryEntry>>(initialValue = emptyList(), galleryId, diary) {
        value = runCatching { diary.entries(galleryId) }.getOrDefault(emptyList())
    }
    ZxPage("日记", onBack = { scope.onBack() }) {
        Spacer(Modifier.height(8.dp))
        if (entries.isEmpty()) ZxEmpty("还没有日记", "日记由相处事件凝结 · 私密日记不可见")
        entries.filter { it.active }.forEach { e ->
            ZxRow(e.event.take(40), "${e.personaName} · ${e.feeling.take(24)}")
            if (e.innerThought.isNotBlank())
                Text(e.innerThought.take(120), fontSize = 11.sp,
                    color = ZhaoxiTheme.colors.ink3, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
        }
        ZxFooterNote("勘误经 DiaryUiPort.correct · 停用经 deactivate")
    }
}

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
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxSection
import com.labteto.dshmobile.uipack.zhaoxi.theme.ZhaoxiTheme
import com.labteto.dshmobile.uihost.UiSceneRenderScope
import kotlinx.coroutines.launch

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
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    androidx.compose.foundation.layout.Column(Modifier.fillMaxSize()) {
        if (snap.messages.isEmpty() && snap.phase != UiExecutionPhase.RUNNING)
            ZxEmpty("还没有消息", "从下方输入框开始")
        ConversationMessages(snap.identity, snap.messages, reading, history,
            Modifier.weight(1f), onMessage = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(it.text)) },
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
    onRegenerate: () -> UiCommandReceipt = { UiCommandReceipt.NotAdmitted },
) {
    var regenerateReceipt by remember(message.id) { mutableStateOf<UiCommandReceipt?>(null) }
    ZxPage("消息操作", onBack = { scope.onBack() }) {
        CommandFeedback(regenerateReceipt)
        Spacer(Modifier.height(8.dp))
        Text(message.text.take(200), fontSize = 13.sp, color = ZhaoxiTheme.colors.ink2)
        Spacer(Modifier.height(10.dp))
        if (message.editableText != null) ZxNavRow("修改并重发", sub = "编辑后作为新分支提交") { onEdit() }
        ZxNavRow("重新生成", sub = "从这条重试回复") {
            val result = onRegenerate()
            when (result) {
                UiCommandReceipt.Accepted, UiCommandReceipt.Queued -> scope.onBack()
                else -> regenerateReceipt = result
            }
        }
        ZxNavRow("阅读文本") { scope.onNavigate(com.labteto.dshmobile.uicontract.UiSceneId("conversation.reader")) }
        val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
        ZxNavRow("复制文本", sub = "") {
            clipboard.setText(androidx.compose.ui.text.AnnotatedString(message.text))
        }

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

/** chat.diary / route.diary — 公开日记阅读、按原版本勘误与停用。私密反思不渲染。 */
@Composable
fun ZhaoxiDiaryScene(diary: DiaryUiPort, galleryId: String, scope: UiSceneRenderScope) {
    var revision by remember { mutableStateOf(0) }
    val entries by produceState<List<com.labteto.dshmobile.uicontract.UiDiaryEntry>>(
        initialValue = emptyList(), galleryId, diary, revision,
    ) {
        value = diary.entries(galleryId)
    }
    var filter by remember { mutableStateOf("有效") }
    var selectedId by remember(galleryId) { mutableStateOf<String?>(null) }
    var editing by remember(galleryId) { mutableStateOf(false) }
    var eventDraft by remember { mutableStateOf("") }
    var feelingDraft by remember { mutableStateOf("") }
    var thoughtDraft by remember { mutableStateOf("") }
    var meaningDraft by remember { mutableStateOf("") }
    var echoDraft by remember { mutableStateOf("") }
    var pendingDisable by remember { mutableStateOf<com.labteto.dshmobile.uicontract.UiDiaryEntry?>(null) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val jobs = androidx.compose.runtime.rememberCoroutineScope()
    fun describe(result: com.labteto.dshmobile.uicontract.UiDiaryReceipt): String = when (result) {
        com.labteto.dshmobile.uicontract.UiDiaryReceipt.Applied -> "操作已保存"
        com.labteto.dshmobile.uicontract.UiDiaryReceipt.Stale -> "记录已经更新，请重新读取后再操作"
        is com.labteto.dshmobile.uicontract.UiDiaryReceipt.Failed -> result.message ?: "操作失败"
    }
    ZxPage("日记", onBack = { scope.onBack() }) {
        Spacer(Modifier.height(8.dp))
        ZxSection("公开日记")
        ZxFooterNote("这里只展示允许阅读的日记内容；人物的私密反思不会公开。")
        feedback?.let { com.labteto.dshmobile.uipack.zhaoxi.components.ZxBanner(
            it, com.labteto.dshmobile.uipack.zhaoxi.components.ZxTone.Info) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ZxChip("有效", filter == "有效") { filter = "有效"; editing = false }
            ZxChip("已停用", filter == "已停用") { filter = "已停用"; editing = false }
        }
        val visible = entries.filter { if (filter == "有效") it.active else !it.active }
        if (visible.isEmpty()) {
            ZxEmpty(if (filter == "有效") "还没有公开日记" else "暂无已停用日记",
                "人物的相处经历会在符合条件时形成日记")
        }
        visible.forEach { entry ->
            ZxRow(entry.event.take(58), "${entry.personaName} · ${entry.feeling.take(36)}",
                onClick = {
                    selectedId = entry.id
                    editing = false
                })
        }
        val selected = visible.firstOrNull { it.id == selectedId }
            ?: visible.firstOrNull()
        if (selected != null) {
            ZxSection(if (selected.active) "日记内容" else "已停用记录")
            Text(selected.event, fontSize = 15.sp, lineHeight = 24.sp,
                color = ZhaoxiTheme.colors.ink, modifier = Modifier.fillMaxWidth())
            if (selected.feeling.isNotBlank()) ZxValueRow("当时的感受", value = selected.feeling)
            if (selected.innerThought.isNotBlank()) {
                ZxSection("心绪")
                Text(selected.innerThought, fontSize = 14.sp, lineHeight = 22.sp,
                    color = ZhaoxiTheme.colors.ink2)
            }
            if (selected.relationshipMeaning.isNotBlank()) {
                ZxSection("关系意义")
                Text(selected.relationshipMeaning, fontSize = 14.sp, color = ZhaoxiTheme.colors.ink2)
            }
            if (selected.unresolvedEcho.isNotBlank()) {
                ZxSection("未解的余韵")
                Text(selected.unresolvedEcho, fontSize = 14.sp, color = ZhaoxiTheme.colors.ink2)
            }
            ZxFooterNote("来源：${selected.sourceMode} · 经历 ${selected.experienceId.take(16)}（只读引用）")
            if (selected.active && !editing) {
                ZxButton("勘误内容", {
                    eventDraft = selected.event
                    feelingDraft = selected.feeling
                    thoughtDraft = selected.innerThought
                    meaningDraft = selected.relationshipMeaning
                    echoDraft = selected.unresolvedEcho
                    editing = true
                })
                ZxButton("停用日记", { pendingDisable = selected },
                    variant = ZxButtonVariant.Danger)
            }
            if (selected.active && editing) {
                ZxSection("内容勘误")
                ZxFooterNote("修改保留原始经历身份，保存时会校验日记版本，防止覆盖较新内容。")
                ZxField(eventDraft, { eventDraft = it }, Modifier.fillMaxWidth(), placeholder = "事件")
                ZxField(feelingDraft, { feelingDraft = it }, Modifier.fillMaxWidth(), placeholder = "感受")
                ZxField(thoughtDraft, { thoughtDraft = it }, Modifier.fillMaxWidth(), placeholder = "心绪")
                ZxField(meaningDraft, { meaningDraft = it }, Modifier.fillMaxWidth(), placeholder = "关系意义")
                ZxField(echoDraft, { echoDraft = it }, Modifier.fillMaxWidth(), placeholder = "未解的余韵")
                ZxButton("保存勘误", {
                    if (!busy) jobs.launch {
                        busy = true
                        try {
                            val result = diary.correct(com.labteto.dshmobile.uicontract.UiDiaryCorrection(
                                galleryId, selected.id, selected.updatedAt,
                                eventDraft, feelingDraft, thoughtDraft, meaningDraft, echoDraft,
                                selected.privateReflection))
                            feedback = describe(result)
                            if (result == com.labteto.dshmobile.uicontract.UiDiaryReceipt.Applied) {
                                revision++
                                editing = false
                            }
                        } finally { busy = false }
                    }
                }, enabled = !busy && eventDraft.isNotBlank(), loading = busy)
                ZxChip("取消编辑", false) { editing = false }
            }
        }
        pendingDisable?.let { target ->
            com.labteto.dshmobile.uipack.zhaoxi.components.ZxDialog(
                "停用这篇日记？",
                "停用后不再用于有效日记展示，已有来源与版本记录仍由原 Owner 管理。",
                "确认停用",
                {
                    pendingDisable = null
                    if (!busy) jobs.launch {
                        busy = true
                        try {
                            val result = diary.deactivate(galleryId, target.id, target.updatedAt)
                            feedback = describe(result)
                            if (result == com.labteto.dshmobile.uicontract.UiDiaryReceipt.Applied) {
                                revision++
                                selectedId = null
                                editing = false
                            }
                        } finally { busy = false }
                    }
                },
                { pendingDisable = null },
            )
        }
    }
}

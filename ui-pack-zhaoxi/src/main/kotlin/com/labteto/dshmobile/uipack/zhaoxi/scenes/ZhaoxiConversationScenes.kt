package com.labteto.dshmobile.uipack.zhaoxi.scenes

import androidx.compose.foundation.text.selection.SelectionContainer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.labteto.dshmobile.uicontract.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
import androidx.compose.runtime.snapshotFlow
import com.labteto.dshmobile.uipack.zhaoxi.sendZhaoxiDraft
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
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
import com.labteto.dshmobile.uipack.zhaoxi.scenes.ZxDocumentCard
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxApprovalCard
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxBanner
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxCap
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxCapBar
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxComposer
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxButton
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxButtonVariant
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxChip
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxFooterNote
import com.labteto.dshmobile.uihost.rememberUiAttachmentPickerFor
import com.labteto.dshmobile.uihost.rememberUiAttachmentMultiPickerFor
import com.labteto.dshmobile.uihost.rememberUiCameraCaptureFor
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxDialog
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxMessageRow
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxModeSwitch
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxQuestionCard
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxTimeSeparator
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxTone
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxTypingIndicator
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxIcon
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
fun ZhaoxiChatScene(chat: ChatUiPort, drafts: ConversationDraftUiPort, reading: ConversationReadingUiPort, history: UiMessageHistoryPort?, authorName: String, scope: UiSceneRenderScope, onMessage: (UiSessionIdentity, UiMessageSnapshot) -> Unit, menu: @Composable () -> Unit,
    documents: UiDocumentUiPort? = null, knownScenes: Set<UiSceneId> = emptySet(),
    media: MediaUiPort? = null, attachments: AttachmentUiPort? = null,
    reasoning: ConversationReasoningUiPort? = null, settings: SettingsUiPort? = null) {
    val snap by chat.snapshots.collectAsState(initial = chat.snapshot())
    val storedDrafts by drafts.drafts.collectAsState()
    val sessionId = snap.identity?.sessionId.orEmpty()
    val draft = storedDrafts[sessionId].orEmpty()
    fun writeDraft(text: String) { if (sessionId.isNotBlank()) drafts.updateDraft(sessionId, text) }
    var receipt by remember(sessionId) { mutableStateOf<UiCommandReceipt?>(null) }
    var showAddMenu by remember(sessionId) { mutableStateOf(false) }
    var inputFocused by remember(sessionId) { mutableStateOf(false) }
    var reasoningExpanded by remember(sessionId) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().fillMaxSize().imePadding()) {
        ZxConversationRoutes(scope, menu)
        CommandFeedback(receipt)
        snap.error?.let { ZxBanner(it, ZxTone.Err) }
        if (snap.status == UiSceneStatus.LOADING) com.labteto.dshmobile.uipack.zhaoxi.components.ZxProgressBar(null)
        Column(Modifier.weight(1f)) {
            snap.group?.let { g ->
                ZxBanner("群聊 · ${g.memberNames.joinToString("、")}", ZxTone.Info)
                Spacer(Modifier.height(6.dp))
            }
            ConversationMessages(snap.identity, snap.messages, reading, history, Modifier.weight(1f),
                onMessage = { m -> snap.identity?.let { onMessage(it, m) } },
                onBranch = { m, index -> snap.identity?.let { receipt = chat.submit(ChatUiCommand.SelectVariant(it, m.id, index)) } },
                preview = snap.livePreview, running = snap.phase == UiExecutionPhase.RUNNING,
                documents = documents, knownScenes = knownScenes, media = media,
                showEmpty = snap.status != UiSceneStatus.LOADING && snap.error == null,
                missingSession = snap.identity == null)
            if (snap.queuedInputCount > 0)
                ZxBanner("⇄ 已排队 ${snap.queuedInputCount} 条 · 队列满时将拒收", ZxTone.Warn)
        }
        ZhaoxiReasoningStrip(sessionId, false, reasoningExpanded || inputFocused || draft.contains("\n"),
            reasoning, settings, scope, knownScenes, onCollapse = { reasoningExpanded = false })
        ZhaoxiComposerAddMenu(showAddMenu, snap.identity, { chat.snapshot().identity },
            attachments, drafts, scope, knownScenes, onClose = { showAddMenu = false })
        ZxComposer(
            draft = draft, onDraftChange = ::writeDraft,
            send = snap.send,
            running = snap.phase == UiExecutionPhase.RUNNING,
            onStop = { snap.identity?.let { id -> receipt = chat.submit(ChatUiCommand.Stop(id)) } },
            onSend = {
                val id = snap.identity
                receipt = if (id == null) UiCommandReceipt.Rejected(UiCommandRejection.UNCONFIGURED)
                else sendZhaoxiDraft(drafts, id) { text, files -> chat.submit(ChatUiCommand.SendMessage(id, text, files)) }
            },
            placeholder = "问点什么",
            onAddClick = { showAddMenu = !showAddMenu },
            onFocusedChange = { inputFocused = it; if (it) reasoningExpanded = true },
            onConnectModel = { scope.onNavigate(UiSceneId("settings.models")) },
        )
    }
}

/** conversation.work — 工作会话主场景（审批卡/提问卡槽位 + 计划入口）。 */
@Composable
fun ZhaoxiWorkScene(work: WorkConversationUiPort, drafts: ConversationDraftUiPort, reading: ConversationReadingUiPort, history: UiMessageHistoryPort?, scope: UiSceneRenderScope, onMessage: (UiSessionIdentity, UiMessageSnapshot) -> Unit, menu: @Composable () -> Unit,
    documents: UiDocumentUiPort? = null, knownScenes: Set<UiSceneId> = emptySet(),
    media: MediaUiPort? = null, attachments: AttachmentUiPort? = null) {
    val snap by work.snapshots.collectAsState(initial = work.snapshot())
    val storedDrafts by drafts.drafts.collectAsState()
    val sessionId = snap.identity?.sessionId.orEmpty()
    val draft = storedDrafts[sessionId].orEmpty()
    fun writeDraft(text: String) { if (sessionId.isNotBlank()) drafts.updateDraft(sessionId, text) }
    var receipt by remember(sessionId) { mutableStateOf<UiCommandReceipt?>(null) }
    var questionAnswer by remember { mutableStateOf("") }
    var showAddMenu by remember(sessionId) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().fillMaxSize().imePadding()) {
        ZxConversationRoutes(scope, menu)
        CommandFeedback(receipt)
        snap.error?.let { ZxBanner(it, ZxTone.Err) }
        if (snap.status == UiSceneStatus.LOADING) com.labteto.dshmobile.uipack.zhaoxi.components.ZxProgressBar(null)
        Column(Modifier.weight(1f)) {
            ConversationMessages(snap.identity, snap.messages, reading, history, Modifier.weight(1f),
                onMessage = { m -> snap.identity?.let { onMessage(it, m) } },
                preview = snap.livePreview, running = snap.phase == UiExecutionPhase.RUNNING,
                documents = documents, knownScenes = knownScenes, pendingApprovalId = snap.approval?.pendingId,
                media = media, workMode = true,
                showEmpty = snap.status != UiSceneStatus.LOADING && snap.error == null,
                missingSession = snap.identity == null)
            snap.approval?.let { ap ->
                Spacer(Modifier.height(6.dp))
                ZxApprovalCard(ap, expired = snap.phase == UiExecutionPhase.IDLE && ap.pendingId.isBlank()) { approved ->
                    snap.identity?.let { id ->
                        receipt = work.submit(WorkConversationUiCommand.AnswerApproval(id, ap.pendingId, approved))
                    }
                }
            }
            snap.question?.let { q ->
                Spacer(Modifier.height(6.dp))
                ZxQuestionCard(q, questionAnswer, { questionAnswer = it }) { answer ->
                    val id = snap.identity
                    if (id != null) {
                        val result = work.submit(WorkConversationUiCommand.AnswerQuestion(id, q.pendingId, answer))
                        receipt = result
                        if (result.admitted()) questionAnswer = ""
                    }
                }
            }
            if (snap.queuedInputCount > 0)
                ZxBanner("⇄ 已排队 ${snap.queuedInputCount} 条 · 队列满时将拒收", ZxTone.Warn)
        }
        ZhaoxiReasoningStrip(sessionId, true, true,
            reasoning, settings, scope, knownScenes)
        ZhaoxiComposerAddMenu(showAddMenu, snap.identity, { work.snapshot().identity },
            attachments, drafts, scope, knownScenes, onClose = { showAddMenu = false })
        ZxComposer(
            draft = draft, onDraftChange = ::writeDraft,
            send = snap.send,
            running = snap.phase == UiExecutionPhase.RUNNING,
            onStop = { snap.identity?.let { id -> receipt = work.submit(WorkConversationUiCommand.Stop(id)) } },
            onSend = {
                val id = snap.identity
                receipt = if (id == null) UiCommandReceipt.Rejected(UiCommandRejection.UNCONFIGURED)
                else sendZhaoxiDraft(drafts, id) { text, files -> work.submit(WorkConversationUiCommand.SendMessage(id, text, files)) }
            },
            placeholder = "描述任务目标",
            onAddClick = { showAddMenu = !showAddMenu },
            onConnectModel = { scope.onNavigate(UiSceneId("settings.models")) },
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
        // RUNNING is shown only inside the one reply surface. No second typing row.
        phase == UiExecutionPhase.WAITING_FOR_USER -> ZxBanner("需要你处理 · 审批与提问待决定", ZxTone.Warn)
        else -> {}
    }
}

/** conversation.reader — 沉浸阅读。 */
@Composable
fun ZhaoxiReaderScene(title: String, text: String, scope: UiSceneRenderScope) {
    ZxPage(title, onBack = { scope.onBack() }) {
        Spacer(Modifier.height(8.dp))
        SelectionContainer {
            com.labteto.dshmobile.uipack.zhaoxi.components.ZxRichReply(text)
        }
    }
}

/** conversation.model_picker — 使用 Owner 的真实选择状态，不以“已提交”冒充“已切换”。 */
@Composable
fun ZhaoxiModelPickerScene(
    models: List<Triple<String, String, Boolean>>,
    onSelect: (String) -> SettingsUiReceipt,
    onOpenModelSettings: () -> Unit,
    scope: UiSceneRenderScope,
) {
    var pendingModelId by remember { mutableStateOf<String?>(null) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }

    // SettingsUiReceipt.Submitted only means the request was queued by the Model Owner.
    // Leave this screen only when the reactive model snapshot confirms the new selection.
    LaunchedEffect(models, pendingModelId) {
        if (pendingModelId != null && models.any { it.first == pendingModelId && it.third }) {
            pendingModelId = null
            scope.onBack()
        }
    }
    ZxPage("选择模型", onBack = { scope.onBack() }) {
        if (models.isEmpty()) {
            com.labteto.dshmobile.uipack.zhaoxi.components.ZxEmpty(
                "还没有配置可用模型", "先在模型设置添加或启用模型",
                actionLabel = "前往模型设置", onAction = onOpenModelSettings)
        }
        feedback?.let { ZxBanner(it, if (failed) ZxTone.Err else ZxTone.Warn) }
        models.forEach { (id, displayName, current) ->
            ZxRow(
                displayName.ifBlank { "未命名模型" },
                when {
                    current -> "当前使用"
                    pendingModelId == id -> "等待模型状态更新"
                    else -> "点击以切换"
                },
                onClick = {
                    if (current) {
                        scope.onBack()
                    } else if (pendingModelId == null) {
                        val result = zxModelSubmitFeedback(onSelect(id))
                        pendingModelId = if (result.waitForSelection) id else null
                        failed = result.error
                        feedback = result.message
                    }
                },
            ) {
                if (current) Text("✓", color = ZhaoxiTheme.colors.accentInk)
                else if (pendingModelId == id) Text("处理中", color = ZhaoxiTheme.colors.ink2)
            }
        }
        ZxNavRow("模型设置与 API Key", onClick = onOpenModelSettings)
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
    draft: String,
    onDraftChange: (String) -> Unit,
) {
    var rejected by remember { mutableStateOf(false) }
    Column {
        if (rejected) {
            ZxBanner("未送达 · 草稿已保留", ZxTone.Err)
            Spacer(Modifier.height(4.dp))
        }
        // Capability tuning remains in model settings; no inactive duplicate chips.
        ZxComposer(draft, onDraftChange, send, {
            if (onSendText(draft)) { onDraftChange(""); rejected = false } else rejected = true
        }, placeholder, running = running, onStop = onStop)
    }
}

/** A calm, thumb-safe conversation app bar; the content/action ports stay in the Host. */
@Composable
private fun ZxHeaderAction(label: String, symbol: String, active: Boolean = false, onClick: () -> Unit) {
    val colors = ZhaoxiTheme.colors
    Box(
        Modifier.size(48.dp).clip(CircleShape)
            .background(if (active) colors.bg2 else colors.bg)
            .clickable(onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        when (symbol) {
            "‹" -> ZxIcon("back")
            "···", "×" -> ZxIcon("dots")
            else -> Text(symbol, fontSize = 12.sp, color = colors.ink,
                fontWeight = FontWeight.Medium)
        }
    }
}

/** No fake prompts or disabled suggestion chips; the existing composer is the sole primary CTA. */
@Composable
private fun ZxConversationEmpty(workMode: Boolean, missingSession: Boolean, modifier: Modifier = Modifier) {
    val colors = ZhaoxiTheme.colors
    Column(modifier.padding(horizontal = 20.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(52.dp).clip(CircleShape).background(colors.bg2),
            contentAlignment = Alignment.Center) {
            Text("朝", fontSize = 23.sp, fontWeight = FontWeight.Medium, color = colors.accent)
        }
        Spacer(Modifier.height(20.dp))
        Text(if (missingSession) "还没有打开会话" else if (workMode) "准备开始一项工作？" else "今天想聊些什么？",
            fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold,
            color = colors.ink)
        Spacer(Modifier.height(8.dp))
        Text(if (missingSession) "返回会话列表，选择已有对话或创建新会话。"
            else if (workMode) "描述目标，过程与结果会在同一条回复中逐步展开。"
            else "写下问题、分享想法，或者从一个小目标开始。",
            fontSize = 14.sp, lineHeight = 22.sp, color = colors.ink2,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
private fun ZxConversationRoutes(scope: UiSceneRenderScope, menu: @Composable () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZxHeaderAction("返回会话列表", "‹") { scope.onBack() }
            Text("朝夕", Modifier.weight(1f).padding(start = 6.dp), fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold, color = ZhaoxiTheme.colors.ink)
            ZxHeaderAction("选择模型", "模型") {
                scope.onNavigate(UiSceneId("conversation.model_picker"))
            }
            ZxHeaderAction(if (expanded) "关闭更多操作" else "更多操作",
                if (expanded) "×" else "···", active = expanded) { expanded = !expanded }
        }
        if (expanded) {
            Box(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
                    .clip(RoundedCornerShape(ZhaoxiTheme.shapes.card))
                    .background(ZhaoxiTheme.colors.bg2).padding(8.dp),
            ) {
                Column(Modifier.heightIn(max = 320.dp).verticalScroll(
                    androidx.compose.foundation.rememberScrollState()
                )) { menu() }
            }
        }
    }
}

/** Internal Work event projections are retained for audit, never pinned into the reply stream. */
internal fun UiDocument.isInlineWorkTaskCard(): Boolean =
    source.workMode && documentId == "work-live:${source.messageId}"

@Composable
internal fun ConversationMessages(identity: UiSessionIdentity?, messages: List<UiMessageSnapshot>,
    reading: ConversationReadingUiPort, history: UiMessageHistoryPort?, modifier: Modifier,
    onMessage: (UiMessageSnapshot) -> Unit, onBranch: (UiMessageSnapshot, Int) -> Unit = { _, _ -> },
    preview: UiStreamingReplyPreview? = null, running: Boolean = false,
    documents: UiDocumentUiPort? = null, knownScenes: Set<UiSceneId> = emptySet(),
    pendingApprovalId: String? = null, media: MediaUiPort? = null,
    showEmpty: Boolean = true, workMode: Boolean = false,
    missingSession: Boolean = false) {
    val sessionId = identity?.sessionId.orEmpty()
    var older by remember(sessionId) { mutableStateOf(emptyList<UiMessageSnapshot>()) }
    var cursor by remember(sessionId) { mutableStateOf<UiTranscriptCursor?>(null) }
    var loaded by remember(sessionId) { mutableStateOf(false) }
    var loading by remember(sessionId) { mutableStateOf(false) }
    var error by remember(sessionId) { mutableStateOf<String?>(null) }
    val jobs = androidx.compose.runtime.rememberCoroutineScope()
    val all = remember(older, messages) { (older + messages).distinctBy { it.id } }
    // A 100ms model preview changes only the live text, not historic message grouping.
    val timeline = remember(all, running) { projectZxReplyTimeline(all, running) }
    val latestReplyKey = timeline.lastOrNull { it is ZxTimelineEntry.Reply }?.key
    val documentsState = if (documents != null) documents.snapshots.collectAsState(initial = documents.snapshot())
        else null
    // Tool events stay durable in the Owner ledger. The timeline shows only a small,
    // user-expandable status below the corresponding reply, never raw tool commands.
    val currentDocuments = documentsState?.value?.takeIf { it.sessionId == sessionId }
        ?.documents.orEmpty()
    // Historical document reads have no privileges; newer active snapshots always win by ID.
    val olderIds = older.map { it.id }.toSet()
    val historical by produceState<Map<String, List<UiDocumentSnapshot>>>(emptyMap(), sessionId,
        olderIds, documents, identity) {
        value = if (documents != null && identity != null && olderIds.isNotEmpty())
            documents.documentsForMessages(identity, olderIds)
                .groupBy { it.document.source.messageId }
        else emptyMap()
    }
    val allCardsBySource = remember(historical, currentDocuments) {
        (historical.values.flatten() + currentDocuments)
            .associateBy { it.document.documentId }.values.groupBy { it.document.source.messageId }
    }
    val workByTurn = allCardsBySource.mapValues { (_, items) ->
        items.firstOrNull { it.document.isInlineWorkTaskCard() }
    }
    fun submitDocumentAction(
        card: UiDocumentSnapshot, actionId: String, fields: Map<String, String>,
    ): UiDocumentReceipt {
        if (documents == null || identity == null || card.actions[actionId]?.available != true)
            return UiDocumentReceipt.Rejected(UiCommandRejection.UNSUPPORTED)
        return documents.submit(identity, UiDocumentActionRequest(
            documentId = card.document.documentId, revision = card.document.revision,
            source = card.document.source, actionId = actionId, fields = fields))
    }
    // A cumulative model snapshot must not repeat already committed intermediate messages.
    val activeReply = timeline.lastOrNull { it is ZxTimelineEntry.Reply } as? ZxTimelineEntry.Reply
    val streamText = uncommittedReplyPreview(preview?.text, activeReply?.stages.orEmpty())
    // A reader position belongs to its session; keep it out of a newly opened chat/Work timeline.
    val state = remember(sessionId) { androidx.compose.foundation.lazy.LazyListState() }
    var restored by remember(sessionId) { mutableStateOf(false) }
    var followTail by remember(sessionId) { mutableStateOf(true) }
    val readerDragging by state.interactionSource.collectIsDraggedAsState()
    val latestStageSignature = activeReply?.stages?.map { it.id to it.text.hashCode() }
    suspend fun moveToLiveTail() {
        val last = (state.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)
        // Avoid resetting an already-visible growing reply to the top on every token.
        var tail = state.layoutInfo.visibleItemsInfo.firstOrNull { it.index == last }
        if (tail == null) {
            state.scrollToItem(last)
            androidx.compose.runtime.withFrameNanos { }
            tail = state.layoutInfo.visibleItemsInfo.firstOrNull { it.index == last }
        }
        tail?.let { item ->
            val overflow = (item.offset + item.size - state.layoutInfo.viewportEndOffset)
                .coerceAtLeast(0)
            if (overflow > 0) state.scrollBy(overflow.toFloat())
        }
    }
    LaunchedEffect(sessionId, all.firstOrNull()?.id) {
        if (!restored && all.isNotEmpty()) {
            val anchor = reading.anchor(sessionId)
            followTail = anchor?.followingTail ?: true
            val restoredMessageId = anchor?.messageId
            val index = timeline.indexOfFirst {
                restoredMessageId != null && it.containsMessage(restoredMessageId)
            }
            state.scrollToItem(if (index >= 0) index + 1 else timeline.size, if (index >= 0) anchor?.offsetPx ?: 0 else 0)
            restored = true
        }
    }
    LaunchedEffect(state, sessionId, all) {
        snapshotFlow { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }
            .distinctUntilChanged().collect { (index, offset) ->
                if (restored && sessionId.isNotBlank()) reading.rememberAnchor(sessionId,
                    UiReadingAnchor(timeline.getOrNull(index - 1)?.let { entry ->
                        when (entry) {
                            is ZxTimelineEntry.Message -> entry.message.id
                            is ZxTimelineEntry.Reply -> entry.stages.firstOrNull()?.id ?: entry.anchorId
                        }
                    }, offset, followTail))
            }
    }
    // Loading and anchor restoration are shared by the automatic top-edge path and
    // the explicit retry button. Never create a second history cursor.
    suspend fun loadOlderPage() {
        if (history == null || sessionId.isBlank() || loading || (loaded && cursor == null)) return
        loading = true
        try {
            val page = history.messagePage(sessionId, cursor, 60)
            val prior = timeline.getOrNull((state.firstVisibleItemIndex - 1).coerceAtLeast(0))
            val previousId = when (prior) {
                is ZxTimelineEntry.Message -> prior.message.id
                is ZxTimelineEntry.Reply -> prior.stages.firstOrNull()?.id ?: prior.anchorId
                else -> null
            }
            older = (page.messages + older).distinctBy { it.id }
            cursor = page.nextCursor
            loaded = true
            error = null
            val index = projectZxReplyTimeline((older + messages).distinctBy { it.id }, running)
                .indexOfFirst { previousId != null && it.containsMessage(previousId) }
            if (index >= 0) state.scrollToItem(index + 1)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message ?: "历史加载失败"
        } finally {
            loading = false
        }
    }
    // Trigger only after the reader has moved away and returned toward the top.
    // This prevents short conversations from silently loading the entire history.
    val nearHistoryTop by remember(state) {
        derivedStateOf { state.firstVisibleItemIndex <= 2 }
    }
    var leftHistoryTop by remember(sessionId) { mutableStateOf(false) }
    LaunchedEffect(sessionId, restored, nearHistoryTop) {
        if (restored && !nearHistoryTop) leftHistoryTop = true
        else if (restored && nearHistoryTop && leftHistoryTop &&
            history != null && error == null && !loading && (!loaded || cursor != null)) {
            leftHistoryTop = false
            loadOlderPage()
        }
    }
    // Distinguish the reader's drag gesture from our programmatic scroll operations.
    LaunchedEffect(state, sessionId, readerDragging) {
        if (readerDragging) snapshotFlow { !state.canScrollForward }
            .distinctUntilChanged().collect { atTail -> followTail = atTail }
    }
    LaunchedEffect(timeline.size, latestStageSignature, streamText, running, readerDragging,
        currentDocuments.map { it.document.documentId to it.document.revision }) {
        if (restored && followTail && !readerDragging) {
            androidx.compose.runtime.withFrameNanos { }
            moveToLiveTail()
        }
    }
    Column(modifier) {
        LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp), state = state) {
        item(key = "history") {
            error?.let { ZxBanner(it, ZxTone.Err) }
            if (history != null && all.isNotEmpty() && (!loaded || cursor != null))
                TextButton(onClick = {
                    jobs.launch { loadOlderPage() }
                }, enabled = !loading) {
                    Text(if (loading) "加载中…" else "↑ 查看更早消息",
                        fontSize = 12.sp, color = ZhaoxiTheme.colors.ink2)
                }
        }
        if (showEmpty && all.isEmpty() && !running) item(key = "empty") {
            ZxConversationEmpty(workMode, missingSession, Modifier.fillParentMaxSize())
        }
        items(timeline, key = { it.key }) { entry ->
            when (entry) {
                is ZxTimelineEntry.Message -> {
                    ZxMessageRow(entry.message,
                        if (entry.message.role == UiMessageRole.USER) "我" else "助手",
                        onLongPress = { onMessage(it) }, onBranchSelect = { onBranch(entry.message, it) })
                }
                is ZxTimelineEntry.Reply -> {
                    val isCurrent = running && entry.key == latestReplyKey
                    Column {
                        // Stage and final messages stay in this one keyed reply container.
                        entry.stages.forEachIndexed { index, stage ->
                            ZxMessageRow(
                                if (index == 0) stage else stage.copy(progressive = true),
                                "助手", onLongPress = { onMessage(stage) },
                                onBranchSelect = { onBranch(stage, it) })
                            allCardsBySource[stage.id].orEmpty()
                                .filterNot { it.document.isInlineWorkTaskCard() }
                                .forEach { card ->
                                    androidx.compose.runtime.key(card.document.documentId) {
                                        ZxDocumentCard(card.document, card.bindings, knownScenes, pendingApprovalId,
                                            onAction = { actionId, fields ->
                                                submitDocumentAction(card, actionId, fields)
                                            }, media = media)
                                    }
                                }
                        }
                        if (isCurrent) {
                            val currentText = streamText
                            if (currentText != null) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    ZxTypingIndicator()
                                    Text("正在输出", fontSize = 11.sp, color = ZhaoxiTheme.colors.ink2)
                                }
                                com.labteto.dshmobile.uipack.zhaoxi.components.ZxRichReply(currentText)
                            } else if (entry.stages.isEmpty()) ZxTypingIndicator()
                        }
                        // The single compact, collapsible Work status shares the reply slot.
                        entry.anchorId?.let { anchor -> workByTurn[anchor] }?.let { card ->
                            ZxDocumentCard(card.document, card.bindings, knownScenes, pendingApprovalId,
                                onAction = { actionId, fields -> submitDocumentAction(card, actionId, fields) },
                                media = media)
                        }
                        entry.anchorId?.let { allCardsBySource[it] }.orEmpty()
                            .filterNot { it.document.isInlineWorkTaskCard() }
                            .forEach { card ->
                                ZxDocumentCard(card.document, card.bindings, knownScenes, pendingApprovalId,
                                    onAction = { actionId, fields -> submitDocumentAction(card, actionId, fields) },
                                    media = media)
                            }
                    }
                }
            }
        }
        }
        if (!followTail && all.isNotEmpty()) {
            com.labteto.dshmobile.uipack.zhaoxi.components.ZxChip(
                "↓ 查看最新回复", false, onClick = {
                    followTail = true
                    jobs.launch {
                        moveToLiveTail()
                    }
                })
        }
    }
}

/** A single composer panel for four real actions; attachment bytes remain Owner-managed. */
@Composable
private fun ZhaoxiComposerAddMenu(
    expanded: Boolean,
    identity: UiSessionIdentity?,
    currentIdentity: () -> UiSessionIdentity?,
    attachments: AttachmentUiPort?,
    drafts: ConversationDraftUiPort,
    scope: UiSceneRenderScope,
    knownScenes: Set<UiSceneId>,
    onClose: () -> Unit,
) {
    val jobs = androidx.compose.runtime.rememberCoroutineScope()
    val sessionId = identity?.sessionId
    var busy by remember(sessionId) { mutableStateOf(false) }
    var feedback by remember(sessionId) { mutableStateOf<String?>(null) }
    fun importSelected(target: UiSessionIdentity, uris: List<String>, cleanup: () -> Unit = {}) {
        if (target != currentIdentity()) {
            feedback = "会话已切换，请在当前会话重新选择附件"
            cleanup()
            return
        }
        if (attachments == null || busy) {
            feedback = "附件暂不可用或仍在导入，请稍后重试"
            cleanup()
            return
        }
        busy = true
        jobs.launch {
            try {
                var imported = 0
                for (uri in uris.take(20)) {
                    if (target != currentIdentity()) {
                        feedback = "会话已切换，剩余附件未导入"
                        break
                    }
                    when (val result = attachments.import(ImportUiAttachmentCommand(target, uri))) {
                        is UiAttachmentReceipt.Imported -> {
                            drafts.addAttachment(target.sessionId, result.attachment)
                            imported++
                        }
                        else -> feedback = result.zxAttachmentFeedback()
                    }
                }
                if (imported > 0 && feedback == null) feedback = "已加入 ${imported} 项附件，可在会话菜单查看或移除"
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                feedback = failure.message ?: "附件导入失败，请重新选择"
            } finally {
                busy = false
                cleanup()
            }
        }
    }
    val filePicker = rememberUiAttachmentPickerFor<UiSessionIdentity>(
        mimeTypes = arrayOf("*/*"), onRejected = { feedback = it },
    ) { target, uri -> if (uri != null) importSelected(target, listOf(uri)) }
    val photoPicker = rememberUiAttachmentMultiPickerFor<UiSessionIdentity>(
        mimeTypes = arrayOf("image/*"), onRejected = { feedback = it },
    ) { target, uris -> if (uris.isNotEmpty()) importSelected(target, uris) }
    val cameraPicker = rememberUiCameraCaptureFor<UiSessionIdentity>(
        onRejected = { feedback = it },
    ) { target, uri, cleanup -> importSelected(target, listOf(uri), cleanup) }
    if (expanded) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ZxButton("拍照", { identity?.let(cameraPicker); onClose() },
                enabled = identity != null && attachments != null && !busy,
                variant = ZxButtonVariant.Outline)
            ZxButton("图片", { identity?.let(photoPicker); onClose() },
                enabled = identity != null && attachments != null && !busy,
                variant = ZxButtonVariant.Outline)
            ZxButton("文件", { identity?.let(filePicker); onClose() },
                enabled = identity != null && attachments != null && !busy,
                variant = ZxButtonVariant.Outline)
            ZxButton("创作", {
                onClose()
                scope.onNavigate(UiSceneId("media.creation"))
            }, enabled = UiSceneId("media.creation") in knownScenes,
                variant = ZxButtonVariant.Outline)
        }
    }
    if (busy) ZxFooterNote("附件导入中…")
    feedback?.let { ZxFooterNote(it) }
}

/** Session-specific modes are projected and persisted by the existing model Owner. */
@Composable
private fun ZhaoxiReasoningStrip(
    sessionId: String, workMode: Boolean, expanded: Boolean,
    reasoning: ConversationReasoningUiPort?, settings: SettingsUiPort?,
    scope: UiSceneRenderScope, knownScenes: Set<UiSceneId>,
    onCollapse: () -> Unit = {},
) {
    if (!expanded || sessionId.isBlank() || reasoning == null || settings == null) return
    val settingsSnapshot by settings.state.collectAsState(initial = settings.initialState)
    val profile = settingsSnapshot.modelSelection.activeProfile
    var revision by remember(sessionId) { mutableStateOf(0) }
    var hint by remember(sessionId) { mutableStateOf<String?>(null) }
    val options = remember(sessionId, workMode, profile, revision) {
        reasoning.options(sessionId, workMode, profile)
    }
    if (options.available.isEmpty()) return
    val titles = mapOf(
        UiConversationReasoningMode.DEFAULT to "默认",
        UiConversationReasoningMode.FAST to "快速",
        UiConversationReasoningMode.LOW to "轻度",
        UiConversationReasoningMode.DEEP to "深度",
        UiConversationReasoningMode.MAX to "最大",
    )
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
        .padding(horizontal = 12.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (!workMode) ZxButton("收起", onCollapse, variant = ZxButtonVariant.Outline)
        options.available.forEach { choice ->
            ZxChip(titles[choice] ?: choice.name, choice == options.selected) {
                if (reasoning.select(sessionId, workMode, profile, choice)) {
                    revision++
                    hint = null
                } else hint = "模型或会话已变化，未保存此档位"
            }
        }
        if (workMode && UiSceneId("work.plan_review") in knownScenes)
            ZxButton("计划", { scope.onNavigate(UiSceneId("work.plan_review")) },
                variant = ZxButtonVariant.Outline)
    }
    hint?.let { ZxFooterNote(it) }
}

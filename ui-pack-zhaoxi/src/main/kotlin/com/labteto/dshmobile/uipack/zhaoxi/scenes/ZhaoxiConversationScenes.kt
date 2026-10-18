package com.labteto.dshmobile.uipack.zhaoxi.scenes

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
import com.labteto.dshmobile.uicontract.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
fun ZhaoxiChatScene(chat: ChatUiPort, drafts: ConversationDraftUiPort, reading: ConversationReadingUiPort, history: UiMessageHistoryPort?, authorName: String, scope: UiSceneRenderScope, onMessage: (UiSessionIdentity, UiMessageSnapshot) -> Unit, menu: @Composable () -> Unit,
    documents: UiDocumentUiPort? = null, knownScenes: Set<UiSceneId> = emptySet()) {
    val snap by chat.snapshots.collectAsState(initial = chat.snapshot())
    val storedDrafts by drafts.drafts.collectAsState()
    val sessionId = snap.identity?.sessionId.orEmpty()
    val draft = storedDrafts[sessionId].orEmpty()
    fun writeDraft(text: String) { if (sessionId.isNotBlank()) drafts.updateDraft(sessionId, text) }
    var receipt by remember(sessionId) { mutableStateOf<UiCommandReceipt?>(null) }
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
                documents = documents, knownScenes = knownScenes)
            if (snap.queuedInputCount > 0)
                ZxBanner("⇄ 已排队 ${snap.queuedInputCount} 条 · 队列满时将拒收", ZxTone.Warn)
        }
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
        )
    }
}

/** conversation.work — 工作会话主场景（审批卡/提问卡槽位 + 计划入口）。 */
@Composable
fun ZhaoxiWorkScene(work: WorkConversationUiPort, drafts: ConversationDraftUiPort, reading: ConversationReadingUiPort, history: UiMessageHistoryPort?, scope: UiSceneRenderScope, onMessage: (UiSessionIdentity, UiMessageSnapshot) -> Unit, menu: @Composable () -> Unit,
    documents: UiDocumentUiPort? = null, knownScenes: Set<UiSceneId> = emptySet()) {
    val snap by work.snapshots.collectAsState(initial = work.snapshot())
    val storedDrafts by drafts.drafts.collectAsState()
    val sessionId = snap.identity?.sessionId.orEmpty()
    val draft = storedDrafts[sessionId].orEmpty()
    fun writeDraft(text: String) { if (sessionId.isNotBlank()) drafts.updateDraft(sessionId, text) }
    var receipt by remember(sessionId) { mutableStateOf<UiCommandReceipt?>(null) }
    var questionAnswer by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().fillMaxSize().imePadding()) {
        ZxConversationRoutes(scope, menu)
        CommandFeedback(receipt)
        snap.error?.let { ZxBanner(it, ZxTone.Err) }
        if (snap.status == UiSceneStatus.LOADING) com.labteto.dshmobile.uipack.zhaoxi.components.ZxProgressBar(null)
        Column(Modifier.weight(1f)) {
            ConversationMessages(snap.identity, snap.messages, reading, history, Modifier.weight(1f),
                onMessage = { m -> snap.identity?.let { onMessage(it, m) } },
                preview = snap.livePreview, running = snap.phase == UiExecutionPhase.RUNNING,
                documents = documents, knownScenes = knownScenes, pendingApprovalId = snap.approval?.pendingId)
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
    draft: String,
    onDraftChange: (String) -> Unit,
) {
    var rejected by remember { mutableStateOf(false) }
    Column {
        if (rejected) {
            ZxBanner("未送达 · 草稿已保留", ZxTone.Err)
            Spacer(Modifier.height(4.dp))
        }
        ZxCapBar(
            listOf(ZxCap("reason", "推理", false), ZxCap("temp", "温度", false)),
            onOpen = { /* capability controls are exposed from model settings */ },
        )
        ZxComposer(draft, onDraftChange, send, {
            if (onSendText(draft)) { onDraftChange(""); rejected = false } else rejected = true
        }, placeholder, running = running, onStop = onStop)
    }
}

@Composable
private fun ZxConversationRoutes(scope: UiSceneRenderScope, menu: @Composable () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        com.labteto.dshmobile.uipack.zhaoxi.components.ZxChip("返回", false) { scope.onBack() }
        com.labteto.dshmobile.uipack.zhaoxi.components.ZxChip("模型", false) { scope.onNavigate(UiSceneId("conversation.model_picker")) }
        com.labteto.dshmobile.uipack.zhaoxi.components.ZxChip("更多", false) { expanded = !expanded }
    }
    if (expanded) Column(Modifier.heightIn(max = 320.dp).verticalScroll(androidx.compose.foundation.rememberScrollState())) { menu() }
}

@Composable
private fun ConversationMessages(identity: UiSessionIdentity?, messages: List<UiMessageSnapshot>,
    reading: ConversationReadingUiPort, history: UiMessageHistoryPort?, modifier: Modifier,
    onMessage: (UiMessageSnapshot) -> Unit, onBranch: (UiMessageSnapshot, Int) -> Unit = { _, _ -> },
    preview: UiStreamingReplyPreview? = null, running: Boolean = false,
    documents: UiDocumentUiPort? = null, knownScenes: Set<UiSceneId> = emptySet(),
    pendingApprovalId: String? = null) {
    val sessionId = identity?.sessionId.orEmpty()
    var older by remember(sessionId) { mutableStateOf(emptyList<UiMessageSnapshot>()) }
    var cursor by remember(sessionId) { mutableStateOf<UiTranscriptCursor?>(null) }
    var loaded by remember(sessionId) { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val jobs = androidx.compose.runtime.rememberCoroutineScope()
    val all = (older + messages).distinctBy { it.id }
    val documentsState = if (documents != null) documents.snapshots.collectAsState(initial = documents.snapshot())
        else null
    val currentDocuments = documentsState?.value?.takeIf { it.sessionId == sessionId }
        ?.documents.orEmpty()
    // Historical document reads have no privileges; newer active snapshots always win by ID.
    val olderIds = older.map { it.id }.toSet()
    val historical by produceState<Map<String, List<UiDocumentSnapshot>>>(emptyMap(), sessionId,
        olderIds, documents, identity, documentsState?.value?.documents) {
        value = if (documents != null && identity != null && olderIds.isNotEmpty())
            documents.documentsForMessages(identity, olderIds).groupBy { it.document.source.messageId }
        else emptyMap()
    }
    val activeByMessage = currentDocuments.groupBy { it.document.source.messageId }
    val streamText = preview?.text?.takeIf { it.isNotBlank() &&
        all.lastOrNull { message -> message.role == UiMessageRole.ASSISTANT }?.text != it }
    val state = rememberLazyListState()
    var restored by remember(sessionId) { mutableStateOf(false) }
    LaunchedEffect(sessionId, all.firstOrNull()?.id) {
        if (!restored && all.isNotEmpty()) {
            val anchor = reading.anchor(sessionId)
            val index = all.indexOfFirst { it.id == anchor?.messageId }
            state.scrollToItem(if (index >= 0) index + 1 else all.size, if (index >= 0) anchor?.offsetPx ?: 0 else 0)
            restored = true
        }
    }
    LaunchedEffect(state, sessionId, all) {
        snapshotFlow { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }
            .distinctUntilChanged().collect { (index, offset) ->
                if (restored && sessionId.isNotBlank()) reading.rememberAnchor(sessionId,
                    UiReadingAnchor(all.getOrNull(index - 1)?.id, offset, !state.canScrollForward))
            }
    }
    LazyColumn(modifier, state = state) {
        item(key = "history") {
            error?.let { ZxBanner(it, ZxTone.Err) }
            if (history != null && (!loaded || cursor != null))
                com.labteto.dshmobile.uipack.zhaoxi.components.ZxButton(if (loading) "加载中…" else "加载更早消息", {
                    if (sessionId.isNotBlank()) jobs.launch {
                        loading = true
                        try {
                            val page = history.messagePage(sessionId, cursor, 60)
                            val previousId = all.getOrNull(state.firstVisibleItemIndex - 1)?.id
                            older = (page.messages + older).distinctBy { it.id }
                            cursor = page.nextCursor; loaded = true; error = null
                            val index = (older + messages).distinctBy { it.id }.indexOfFirst { it.id == previousId }
                            if (index >= 0) state.scrollToItem(index + 1)
                        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                        catch (failure: Exception) { error = failure.message ?: "历史加载失败" }
                        finally { loading = false }
                    }
                }, enabled = !loading)
        }
        items(all, key = { it.id }) { m ->
            Column {
                ZxMessageRow(m, if (m.role == UiMessageRole.USER) "我" else "助手",
                    onLongPress = { onMessage(m) }, onBranchSelect = { onBranch(m, it) })
                val byId = (historical[m.id].orEmpty() + activeByMessage[m.id].orEmpty())
                    .associateBy { it.document.documentId }.values
                byId.forEach { card ->
                    androidx.compose.runtime.key(card.document.documentId) {
                        ZxDocumentCard(card.document, card.bindings, knownScenes, pendingApprovalId,
                            onAction = { actionId, fields ->
                                if (documents == null || identity == null ||
                                    card.actions[actionId]?.enabled != true) {
                                    UiDocumentReceipt.Rejected(UiCommandRejection.UNSUPPORTED)
                                } else documents.submit(identity, UiDocumentActionRequest(
                                    documentId = card.document.documentId,
                                    revision = card.document.revision, source = card.document.source,
                                    actionId = actionId, fields = fields))
                            })
                    }
                }
            }
        }
        if (running) item(key = "live-model-preview") {
            if (streamText != null) {
                Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ZxTypingIndicator()
                        Text("正在输出", fontSize = 11.sp, color = ZhaoxiTheme.colors.ink2)
                    }
                    Text(streamText, fontSize = 15.sp, lineHeight = 23.sp,
                        color = ZhaoxiTheme.colors.ink)
                }
            } else ZxTypingIndicator()
        }
    }
}

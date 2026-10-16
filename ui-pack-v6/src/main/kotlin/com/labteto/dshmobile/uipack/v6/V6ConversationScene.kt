package com.labteto.dshmobile.uipack.v6

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
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
import com.labteto.dshmobile.uicontract.UiCommandRejection
import com.labteto.dshmobile.uicontract.UiExecutionPhase
import com.labteto.dshmobile.uicontract.UiMessageEditOutcome
import com.labteto.dshmobile.uicontract.UiMessageRole
import com.labteto.dshmobile.uicontract.UiMessageSnapshot
import com.labteto.dshmobile.uicontract.UiReadingAnchor
import com.labteto.dshmobile.uicontract.UiSceneStatus
import com.labteto.dshmobile.uicontract.UiSessionIdentity
import com.labteto.dshmobile.uicontract.UiMessageHistoryPort
import com.labteto.dshmobile.uicontract.UiDocumentUiPort
import com.labteto.dshmobile.uicontract.UiDocumentFormUiPort
import com.labteto.dshmobile.uicontract.MediaUiPort
import com.labteto.dshmobile.uicontract.WorkEvidenceUiPort
import com.labteto.dshmobile.uicontract.PersonaGalleryUiPort
import com.labteto.dshmobile.uicontract.PersonaSelectionUiPort
import com.labteto.dshmobile.uicontract.PersonaStoryUiPort
import com.labteto.dshmobile.uicontract.PersonaGalleryDeletionUiPort
import com.labteto.dshmobile.uicontract.PersonaAuthoringUiPort
import com.labteto.dshmobile.uicontract.WorkReadUiPort
import com.labteto.dshmobile.uicontract.WorkPolicyUiPort
import com.labteto.dshmobile.uicontract.WorkTeamUiPort
import com.labteto.dshmobile.uicontract.ReplySuggestionsUiPort
import com.labteto.dshmobile.uicontract.ChatGroupUiPort
import com.labteto.dshmobile.uicontract.UiTranscriptCursor
import com.labteto.dshmobile.uicontract.WorkConversationUiCommand
import com.labteto.dshmobile.uicontract.WorkConversationUiPort
import com.labteto.dshmobile.uihost.UiSceneNavigationResult
import com.labteto.dshmobile.uicontract.UiSceneId
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
    val documents: UiDocumentUiPort? = null,
    val forms: UiDocumentFormUiPort? = null,
    val media: MediaUiPort? = null,
    val evidence: WorkEvidenceUiPort? = null,
    val gallery: PersonaGalleryUiPort? = null,
    val selection: PersonaSelectionUiPort? = null,
    val groups: ChatGroupUiPort? = null,
    val stories: PersonaStoryUiPort? = null,
    val galleryDeletion: PersonaGalleryDeletionUiPort? = null,
    val personaAuthoring: PersonaAuthoringUiPort? = null,
    val workReads: WorkReadUiPort? = null,
    val workPolicy: WorkPolicyUiPort? = null,
    val workTeam: WorkTeamUiPort? = null,
    val replySuggestions: ReplySuggestionsUiPort? = null,
    val workMode: () -> Boolean,
    val workModeUpdates: Flow<Boolean>? = null,
)

/** The four slots are distinct executable scenes, not aliases for the whole page. */
enum class V6ConversationPart { FULL, HEADER, TIMELINE, COMPOSER, REPLY_STATUS, WORK_APPROVAL, WORK_QUESTION }

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

/** An attachment is a real message payload; accepted delivery is not completion. */
internal fun submitV6ConversationDraft(
    ports: V6ConversationPorts,
    workMode: Boolean,
    identity: UiSessionIdentity,
    text: String,
    files: List<com.labteto.dshmobile.local.attachment.LocalImportedAttachment>,
): UiCommandReceipt {
    if (text.isBlank() && files.isEmpty())
        return UiCommandReceipt.Rejected(UiCommandRejection.EMPTY_INPUT)
    return if (workMode) ports.work.submit(WorkConversationUiCommand.SendMessage(identity, text, files))
    else ports.chat.submit(ChatUiCommand.SendMessage(identity, text, files))
}

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
    part: V6ConversationPart = V6ConversationPart.FULL,
    onNavigate: ((UiSceneId) -> UiSceneNavigationResult)? = null,
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
    val docOwner = ports.documents
    val formOwner = ports.forms
    val docSnapshot = docOwner?.let { owner ->
        owner.snapshots.collectAsStateWithLifecycle(
            initialValue = remember(owner) { owner.snapshot() }).value
    }
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
    var approvalConfirmation by remember(identity) { mutableStateOf<String?>(null) }
    // Never reuse list position across a different Owner activation.
    val list = remember(identity) { LazyListState() }
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
            catch (failure: Exception) {
                val active = (if (workMode) ports.work.snapshot().identity else ports.chat.snapshot().identity) == target
                if (active) feedback = failure.message ?: "历史读取失败"
            }
            finally { loadingHistory = false }
        }
    }

    LaunchedEffect(identity, messages.lastOrNull()?.id, status, anchorRestored, part) {
        if (part != V6ConversationPart.FULL && part != V6ConversationPart.TIMELINE)
            return@LaunchedEffect
        val target = identity ?: return@LaunchedEffect
        if (anchorRestored || status == UiSceneStatus.LOADING || messages.isEmpty()) return@LaunchedEffect
        val anchor = ports.reading.anchor(target.sessionId)
        var restored = messages
        var restoreCursor = cursor
        var more = hasOlder
        // A search result can be far older than the hot message window. Follow only the
        // Session owner's opaque history cursors; never guess a timeline position or issue
        // another Chat/Work command. Bound the scan to avoid an unresponsive UI on long logs.
        if (anchor?.followingTail == false && anchor.messageId != null &&
            restored.none { it.id == anchor.messageId } && ports.history != null) {
            loadingHistory = true
            try {
                val lookup = locateV6HistoryAnchor(
                    owner = ports.history, sessionId = target.sessionId,
                    initial = restored, initialCursor = restoreCursor, initiallyHasOlder = more,
                    targetMessageId = anchor.messageId, maxPages = 25,
                )
                restored = lookup.messages
                restoreCursor = lookup.nextCursor
                more = lookup.hasOlder
                if (restored != messages) {
                    older = restored.filterNot { it.id in seen }
                    cursor = restoreCursor
                    hasOlder = more
                }
                if (restored.none { it.id == anchor.messageId })
                    feedback = "目标消息超出单次历史定位范围或已失效，请使用更精确的检索"
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { feedback = failure.message ?: "无法读取目标历史消息" }
            finally { loadingHistory = false }
        }
        if (restored != messages) androidx.compose.runtime.withFrameNanos { }
        val index = restored.indexOfFirst { it.id == anchor?.messageId }
        val historyHeader = if (ports.history != null && more) 1 else 0
        list.scrollToItem(
            if (index >= 0 && anchor?.followingTail == false) index + historyHeader
            else restored.lastIndex + historyHeader,
            if (index >= 0 && anchor?.followingTail == false)
                anchor.offsetPx.coerceAtLeast(0) else 0,
        )
        anchorRestored = true
    }
    LaunchedEffect(identity?.sessionId, anchorRestored, messages, part) {
        if (part != V6ConversationPart.FULL && part != V6ConversationPart.TIMELINE)
            return@LaunchedEffect
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
                catch (failure: Exception) {
                    val active = (if (workMode) ports.work.snapshot().identity else ports.chat.snapshot().identity) == target
                    if (active) feedback = failure.message ?: "附件导入失败"
                }
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

    var navigationExpanded by remember(identity) { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().imePadding().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (part == V6ConversationPart.FULL || part == V6ConversationPart.HEADER) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(if (workMode) "Work" else "聊天", style = MaterialTheme.typography.titleLarge)
            SyButton("返回", onBack, style = SyButtonStyle.GHOST)
        }
        SyButton(if (navigationExpanded) "收起功能入口" else "功能与页面", {
            navigationExpanded = !navigationExpanded
        }, style = SyButtonStyle.GHOST)
        if (navigationExpanded && onNavigate != null) {
            Column(Modifier.fillMaxWidth().heightIn(max = 260.dp)
                .verticalScroll(rememberScrollState())) {
            val destinations = listOf(
                "shell.new_session" to "新建会话",
                "shell.search" to "搜索历史",
                "route.settings" to "设置",
                "route.tasks" to "自动任务",
                "route.tools" to "工具与插件",
                "route.workspace" to "本地工作区",
                "media.creation" to "媒体创作",
                "media.result" to "媒体成果",
                "route.diary" to "人物日记",
                "chat.persona_editor" to "编辑人物",
                "chat.character_tuning" to "当前人物微调",
                "chat.world_book" to "人物世界书",
                "chat.reply_suggestions" to "回复建议",
                "conversation.message_actions" to "消息操作",
                "conversation.reader" to "完整阅读",
                "settings.memory_management" to "记忆管理",
            )
            destinations.forEach { (id, label) ->
                SyButton(label, onClick = {
                    val result = onNavigate(UiSceneId(id))
                    if (result !is UiSceneNavigationResult.Changed)
                        feedback = "无法打开：" + label
                }, style = SyButtonStyle.SECONDARY)
            }
            }
        }
        if (navigationExpanded && workMode && identity != null &&
            (part == V6ConversationPart.FULL || part == V6ConversationPart.HEADER) &&
            onNavigate != null) {
            if (ports.workReads != null && ports.workPolicy != null)
                SyButton("Work 计划审阅", {
                    val receipt = onNavigate(UiSceneId("work.plan_review"))
                    if (receipt !is UiSceneNavigationResult.Changed)
                        feedback = "当前无法打开 Work 计划"
                }, style = SyButtonStyle.SECONDARY)
            if (ports.workTeam != null)
                SyButton("Work 团队成员", {
                    val receipt = onNavigate(UiSceneId("work.team"))
                    if (receipt !is UiSceneNavigationResult.Changed)
                        feedback = "当前无法打开 Work 团队"
                }, style = SyButtonStyle.SECONDARY)
        }
        if (navigationExpanded && !workMode && identity != null &&
            (part == V6ConversationPart.FULL || part == V6ConversationPart.HEADER) &&
            onNavigate != null) {
            if (ports.gallery != null && ports.selection != null)
                SyButton("人物档案与故事", {
                    val result = onNavigate(UiSceneId("route.persona_gallery"))
                    if (result !is UiSceneNavigationResult.Changed)
                        feedback = "人物档案馆暂不可达"
                }, style = SyButtonStyle.SECONDARY)
            if (ports.gallery != null && ports.selection != null)
                SyButton("选择人物", onClick = {
                    val result = onNavigate(UiSceneId("chat.persona_picker"))
                    if (result !is UiSceneNavigationResult.Changed)
                        feedback = "人物选择界面暂不可达"
                }, style = SyButtonStyle.SECONDARY)
            if (ports.groups != null) {
                SyButton("群聊成员", onClick = {
                    val result = onNavigate(UiSceneId("chat.group_members"))
                    if (result !is UiSceneNavigationResult.Changed)
                        feedback = "群聊成员界面暂不可达"
                }, style = SyButtonStyle.SECONDARY)
                SyButton("群聊公告", onClick = {
                    val result = onNavigate(UiSceneId("chat.group_announcement"))
                    if (result !is UiSceneNavigationResult.Changed)
                        feedback = "群聊公告界面暂不可达"
                }, style = SyButtonStyle.GHOST)
            }
        }
        if (navigationExpanded && docOwner != null && formOwner != null &&
            (part == V6ConversationPart.FULL || part == V6ConversationPart.HEADER) &&
            onNavigate != null) {
            SyButton("查看会话结构化文档", onClick = {
                val result = onNavigate(UiSceneId("conversation.ui_document"))
                if (result !is UiSceneNavigationResult.Changed)
                    feedback = "文档界面暂不可达，请保留当前消息"
            }, style = SyButtonStyle.SECONDARY)
        }
        if (identity == null || status == UiSceneStatus.LOADING) {
            SyNote("会话正在切换，等待 Owner 完成载入")
        } else {
            Text("会话 ${identity.sessionId.take(12)} · ${phase.name} · 等待队列 $queued",
                color = LocalSyColors.current.textSecondary,
                style = MaterialTheme.typography.labelMedium)
        }
        }
        if (part == V6ConversationPart.FULL || part == V6ConversationPart.TIMELINE) LazyColumn(
            state = list,
            modifier = (if (part == V6ConversationPart.FULL) Modifier.weight(1f)
                else Modifier.fillMaxSize()).fillMaxWidth(),
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
                    val documentState = docSnapshot
                    if (docOwner != null && formOwner != null && identity != null &&
                        documentState != null && documentState.sessionId == identity.sessionId) {
                        val related = documentState.documents.filter {
                            it.document.source.sessionId == identity.sessionId &&
                                it.document.source.messageId == message.id
                        }
                        related.forEach { document ->
                            androidx.compose.runtime.key(document.document.documentId,
                                document.document.revision) {
                                V6DocumentCard(document, identity, docOwner, formOwner, ports.media,
                                    identityIsCurrent = {
                                        (if (workMode) ports.work.snapshot().identity
                                        else ports.chat.snapshot().identity) == identity
                                    })
                            }
                        }
                    }
                    if (docOwner != null && formOwner != null && onNavigate != null &&
                        identity != null && message.id == messages.lastOrNull()?.id &&
                        phase == UiExecutionPhase.IDLE &&
                        part == V6ConversationPart.FULL) {
                        SyButton("从这条消息生成结构化内容", onClick = {
                            val result = onNavigate(UiSceneId("conversation.ui_document_generate"))
                            if (result !is UiSceneNavigationResult.Changed)
                                feedback = "结构化文档生成界面暂不可达"
                        }, style = SyButtonStyle.SECONDARY)
                    }
                    val actionReady = identity != null && phase == UiExecutionPhase.IDLE &&
                        status != UiSceneStatus.LOADING
                    if (message.role == UiMessageRole.USER && message.editableText != null &&
                        actionReady) SyButton("修改", {
                            editing = message; editText = message.editableText.orEmpty()
                        }, style = SyButtonStyle.GHOST)
                    if (message.role == UiMessageRole.ASSISTANT && !message.proactive &&
                        message.id == messages.lastOrNull()?.id && actionReady) {
                        SyButton("重新生成", {
                            show(if (workMode) ports.work.submit(
                                WorkConversationUiCommand.RegenerateReply(identity, message.id))
                            else ports.chat.submit(ChatUiCommand.RegenerateReply(identity, message.id)))
                        }, style = SyButtonStyle.SECONDARY)
                    }
                    val branch = message.branch
                    if (!workMode && branch != null && branch.count > 1 && actionReady) {
                        Text("版本 ${branch.index + 1}/${branch.count}",
                            style = MaterialTheme.typography.labelMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SyButton("上一版", {
                                show(ports.chat.submit(ChatUiCommand.SelectVariant(
                                    identity, message.id, branch.index - 1)))
                            }, enabled = branch.index > 0 && actionReady, style = SyButtonStyle.GHOST)
                            SyButton("下一版", {
                                show(ports.chat.submit(ChatUiCommand.SelectVariant(
                                    identity, message.id, branch.index + 1)))
                            }, enabled = branch.index + 1 < branch.count && actionReady, style = SyButtonStyle.GHOST)
                        }
                    }
                }
            }
            if (messages.isEmpty() && status == UiSceneStatus.EMPTY)
                item(key = "empty") { SyNote("当前会话还没有消息") }
        }
        if (workMode && identity != null &&
            (part == V6ConversationPart.FULL || part == V6ConversationPart.WORK_QUESTION ||
                part == V6ConversationPart.WORK_APPROVAL)) {
            if (part == V6ConversationPart.FULL || part == V6ConversationPart.WORK_QUESTION)
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
            if (part == V6ConversationPart.FULL || part == V6ConversationPart.WORK_APPROVAL)
            work.approval?.let { approval ->
                SyGlassCard(Modifier.fillMaxWidth()) {
                    Text("需要明确授权 · ${approval.toolName}",
                        style = MaterialTheme.typography.titleSmall)
                    Text(approval.summary)
                    Text(approval.arguments)
                    Text("风险级别：${approval.impact.name}")
                    SyButton("查看并批准", { approvalConfirmation = approval.pendingId })
                    if (approvalConfirmation == approval.pendingId) AlertDialog(
                        onDismissRequest = { approvalConfirmation = null },
                        title = { Text("确认仅批准当前工具调用") },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(approval.toolName)
                                Text(approval.summary)
                                Text(approval.arguments)
                                Text("影响等级：" + approval.impact.name)
                            }
                        },
                        confirmButton = { TextButton(onClick = {
                            approvalConfirmation = null
                            show(ports.work.submit(WorkConversationUiCommand.AnswerApproval(
                                identity, approval.pendingId, true)))
                        }) { Text("仅批准此次调用") } },
                        dismissButton = { TextButton(onClick = {
                            approvalConfirmation = null
                        }) { Text("取消") } },
                    )
                    SyButton("拒绝", {
                        show(ports.work.submit(WorkConversationUiCommand.AnswerApproval(
                            identity, approval.pendingId, false)))
                    }, style = SyButtonStyle.SECONDARY)
                }
            }
        }
        if (part == V6ConversationPart.FULL && workMode && identity != null)
            ports.evidence?.let { evidence ->
                V6WorkEvidencePanel(evidence, identity)
            }
        if (part == V6ConversationPart.FULL || part == V6ConversationPart.REPLY_STATUS) {
            ownerError?.let { SyNote(it) }
            feedback?.let { Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
            Text(when (phase) {
                UiExecutionPhase.IDLE -> if (status == UiSceneStatus.LOADING) "正在加载" else "等待输入"
                UiExecutionPhase.RUNNING -> "正在生成回复"
                UiExecutionPhase.WAITING_FOR_USER -> "正在等待用户操作"
            }, style = MaterialTheme.typography.labelMedium)
            if (queued > 0) Text("队列中：" + queued)
        }
        if (part == V6ConversationPart.FULL || part == V6ConversationPart.COMPOSER) {
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
                if ((if (workMode) ports.work.snapshot().identity else ports.chat.snapshot().identity) != target)
                    return@SyButton
                val receipt = submitV6ConversationDraft(ports, workMode, target, draft, imported)
                show(receipt)
                if (acceptedV6ConversationReceipt(receipt) &&
                    (if (workMode) ports.work.snapshot().identity else ports.chat.snapshot().identity) == target) {
                    ports.drafts.updateDraft(target.sessionId, "")
                    ports.drafts.clearAttachments(target.sessionId)
                }
            }, enabled = identity != null && available && (draft.isNotBlank() || imported.isNotEmpty()) && !importing)
        }
        if (!available && rejection != null)
            Text("发送暂不可用：${rejection.name}", color = LocalSyColors.current.textSecondary)
        }
    }
}

/**
 * Opaque paging only: never guess message offsets from timestamps, IDs or search snippets.
 * The bounded result is intentionally incomplete on very long histories; callers must
 * disclose that instead of claiming to have located a missing target.
 */
internal data class V6HistoryAnchorLookup(
    val messages: List<UiMessageSnapshot>,
    val nextCursor: UiTranscriptCursor?,
    val hasOlder: Boolean,
    val found: Boolean,
)

internal suspend fun locateV6HistoryAnchor(
    owner: UiMessageHistoryPort,
    sessionId: String,
    initial: List<UiMessageSnapshot>,
    initialCursor: UiTranscriptCursor?,
    initiallyHasOlder: Boolean,
    targetMessageId: String,
    maxPages: Int,
): V6HistoryAnchorLookup {
    require(maxPages in 1..100) { "历史定位分页预算无效" }
    var messages = initial
    var cursor = initialCursor
    var hasOlder = initiallyHasOlder
    for (index in 0 until maxPages) {
        if (!hasOlder || messages.any { it.id == targetMessageId }) break
        val page = withContext(Dispatchers.IO) { owner.messagePage(sessionId, cursor, 100) }
        val next = (page.messages + messages).distinctBy { it.id }
        if (next.size == messages.size && page.nextCursor == cursor) {
            hasOlder = false // Defensive stop for a broken or repeating owner continuation.
            break
        }
        messages = next
        cursor = page.nextCursor
        hasOlder = cursor != null
    }
    return V6HistoryAnchorLookup(messages, cursor, hasOlder,
        messages.any { it.id == targetMessageId })
}

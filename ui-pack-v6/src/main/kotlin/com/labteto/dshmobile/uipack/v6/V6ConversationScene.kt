package com.labteto.dshmobile.uipack.v6

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import com.labteto.dshmobile.uicontract.SettingsUiPort
import com.labteto.dshmobile.uicontract.SessionUiPort
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.runtime.SideEffect
import com.shenyan.designsystem.components.SyEntrance
import com.shenyan.designsystem.components.SyAnimatedReveal
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
import com.labteto.dshmobile.uicontract.InspectUiAttachmentCommand
import com.labteto.dshmobile.uicontract.UiAttachmentInspectionReceipt
import com.labteto.dshmobile.uicontract.UiAttachmentReceipt
import com.labteto.dshmobile.uicontract.UiCommandReceipt
import com.labteto.dshmobile.uicontract.UiCommandRejection
import com.labteto.dshmobile.uicontract.UiExecutionPhase
import com.labteto.dshmobile.uicontract.UiMessageEditOutcome
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
    val files: com.labteto.dshmobile.uicontract.FilesUiPort? = null,
    val workMode: () -> Boolean,
    val workModeUpdates: Flow<Boolean>? = null,
)

/** The four slots are distinct executable scenes, not aliases for the whole page. */
enum class V6ConversationPart { FULL, HEADER, TIMELINE, COMPOSER, REPLY_STATUS, WORK_APPROVAL, WORK_QUESTION }

internal fun describeV6ConversationReceipt(receipt: UiCommandReceipt): String = when (receipt) {
    UiCommandReceipt.Accepted -> "请求已受理，尚不代表执行完成"
    UiCommandReceipt.Queued -> "消息已排队，处理完当前消息后继续"
    is UiCommandReceipt.Rejected -> v6RejectionLabel(receipt.reason)
    is UiCommandReceipt.Failed -> receipt.message
    UiCommandReceipt.NotAdmitted -> "当前操作未获准执行"
    is UiCommandReceipt.MessageEdited -> v6MessageEditLabel(receipt.result)
    UiCommandReceipt.OutcomeUnknown -> "结果未确认，查看消息状态后再决定是否重试"
}

internal fun acceptedV6ConversationReceipt(receipt: UiCommandReceipt): Boolean =
    receipt == UiCommandReceipt.Accepted || receipt == UiCommandReceipt.Queued

/** Keep the independent V6 composer aligned with the legacy 20-image admission rule. */
internal enum class V6AttachmentDecision { ACCEPT, DUPLICATE, IMAGE_LIMIT }

internal fun v6AttachmentDecision(
    existing: List<com.labteto.dshmobile.local.attachment.LocalImportedAttachment>,
    candidate: com.labteto.dshmobile.local.attachment.LocalImportedAttachment,
    maxImages: Int = 20,
): V6AttachmentDecision {
    require(maxImages > 0)
    if (existing.any { it.relativePath == candidate.relativePath ||
            (candidate.attachmentId != null && it.attachmentId == candidate.attachmentId) })
        return V6AttachmentDecision.DUPLICATE
    if (candidate.mediaType.startsWith("image/") &&
        existing.count { it.mediaType.startsWith("image/") } >= maxImages)
        return V6AttachmentDecision.IMAGE_LIMIT
    return V6AttachmentDecision.ACCEPT
}

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
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun V6ConversationScene(
    ports: V6ConversationPorts,
    workMode: Boolean,
    onBack: () -> Unit,
    part: V6ConversationPart = V6ConversationPart.FULL,
    onNavigate: ((UiSceneId) -> UiSceneNavigationResult)? = null,
    modelSettings: SettingsUiPort? = null,
    sessions: SessionUiPort? = null,
    availableScenes: Set<UiSceneId>? = null,
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
    var inspectionPath by remember(identity) { mutableStateOf<String?>(null) }
    var inspectedAttachment by remember(identity) {
        mutableStateOf<com.labteto.dshmobile.uicontract.UiAttachmentInspection?>(null)
    }
    val docOwner = ports.documents
    val formOwner = ports.forms
    val docSnapshot = docOwner?.let { owner ->
        owner.snapshots.collectAsStateWithLifecycle(
            initialValue = remember(owner) { owner.snapshot() }).value
    }
    var feedback by remember(identity) { mutableStateOf<String?>(null) }
    var readingFile by remember(identity) { mutableStateOf<String?>(null) }
    var filePreview by remember(identity, readingFile) {
        mutableStateOf<com.labteto.dshmobile.local.tools.LocalWorkspaceFilePreview?>(null)
    }
    var fileError by remember(identity, readingFile) { mutableStateOf<String?>(null) }
    LaunchedEffect(identity, readingFile, ports.files) {
        val path = readingFile ?: return@LaunchedEffect
        val files = ports.files ?: return@LaunchedEffect
        try {
            filePreview = withContext(Dispatchers.IO) { files.previewWorkspaceFile(path) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { fileError = failure.message ?: "暂时无法预览文件" }
    }
    readingFile?.let { path ->
        AlertDialog(onDismissRequest = { readingFile = null },
            title = { Text(path.substringAfterLast('/')) },
            text = {
                Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val preview = filePreview
                    when {
                        fileError != null -> Text(fileError.orEmpty())
                        preview == null -> Text("正在读取文件")
                        else -> {
                            androidx.compose.foundation.text.selection.SelectionContainer {
                                Text(preview.text ?: "此文件暂不支持文本预览，可到工作文件中打开")
                            }
                            if (preview.truncated) SyNote("文件较长，当前显示部分内容")
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { readingFile = null }) { Text("关闭") } },
        )
    }

    var editing by remember(identity) { mutableStateOf<UiMessageSnapshot?>(null) }
    var editText by remember(identity) { mutableStateOf("") }
    var editError by remember(identity) { mutableStateOf<String?>(null) }
    var attachmentsOpen by remember(identity) { mutableStateOf(false) }
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
    var initialMessages by remember(identity) { mutableStateOf<Set<String>?>(null) }
    if (initialMessages == null && status != UiSceneStatus.LOADING) {
        SideEffect { initialMessages = seen }
    }
    val messages = older.filterNot { it.id in seen } + hot
    val ids = messages.map { it.id }.toSet()
    var anchorRestored by remember(identity) { mutableStateOf(false) }
    // Persist the requested message identity only. Owner cursors are opaque and must be
    // reconstructed after process death from the restored Session reading anchor.
    var pendingAnchorId by rememberSaveable(identity?.sessionId) { mutableStateOf<String?>(null) }
    var anchorScrollInProgress by remember(identity) { mutableStateOf(false) }

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
        if (anchorRestored || status == UiSceneStatus.LOADING) return@LaunchedEffect
        val anchor = ports.reading.anchor(target.sessionId)
        val targetMessageId = anchor?.messageId
        // If a process-restored pending request belongs to an old/changed target, drop it.
        // The Session's saved anchor is the only valid recovery identity.
        if (pendingAnchorId != null &&
            recoverableV6PendingAnchor(pendingAnchorId, anchor) == null) {
            pendingAnchorId = null
        }
        val historyOwner = ports.history
        if (messages.isEmpty() && (targetMessageId == null || historyOwner == null)) return@LaunchedEffect
        var restored = messages
        var restoreCursor = cursor
        var more = hasOlder
        // A search result can be far older than the hot message window. Follow only the
        // Session owner's opaque history cursors; never guess a timeline position or issue
        // another Chat/Work command. Bound the scan to avoid an unresponsive UI on long logs.
        if (anchor?.followingTail == false && targetMessageId != null &&
            restored.none { it.id == targetMessageId } && historyOwner != null) {
            loadingHistory = true
            try {
                val lookup = locateV6HistoryAnchor(
                    owner = historyOwner, sessionId = target.sessionId,
                    initial = restored, initialCursor = restoreCursor, initiallyHasOlder = more,
                    targetMessageId = targetMessageId, maxPages = 25,
                )
                restored = lookup.messages
                restoreCursor = lookup.nextCursor
                more = lookup.hasOlder
                if (restored != messages) older = restored.filterNot { it.id in seen }
                // Cursors can advance even when a page is empty or duplicate-only.
                cursor = restoreCursor
                hasOlder = more
                if (restored.none { it.id == targetMessageId }) {
                    if (more) {
                        pendingAnchorId = targetMessageId
                        feedback = "目标消息超过首批历史定位预算，可选择继续定位"
                    } else {
                        feedback = "未找到原消息，可能已被删除或版本已变化"
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { feedback = failure.message ?: "无法读取目标历史消息" }
            finally { loadingHistory = false }
        }
        if (restored.isEmpty()) {
            anchorRestored = true
            return@LaunchedEffect
        }
        if (restored != messages) androidx.compose.runtime.withFrameNanos { }
        val index = restored.indexOfFirst { it.id == targetMessageId }
        val historyHeader = if (ports.history != null && more) 1 else 0
        if (targetMessageId != null && anchor?.followingTail == false && index < 0) {
            // Do not jump to tail and silently turn a failed search into a false match.
            anchorRestored = true
            return@LaunchedEffect
        }
        list.scrollToItem(
            if (index >= 0 && anchor?.followingTail == false) index + historyHeader
            else restored.lastIndex + historyHeader,
            if (index >= 0 && anchor?.followingTail == false)
                anchor.offsetPx.coerceAtLeast(0) else 0,
        )
        anchorRestored = true
    }
    LaunchedEffect(identity?.sessionId, anchorRestored, messages, part,
        pendingAnchorId, anchorScrollInProgress) {
        if (part != V6ConversationPart.FULL && part != V6ConversationPart.TIMELINE)
            return@LaunchedEffect
        val sid = identity?.sessionId ?: return@LaunchedEffect
        if (!anchorRestored || pendingAnchorId != null || anchorScrollInProgress) return@LaunchedEffect
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

    fun continueAnchorSearch() {
        val target = identity ?: return
        val messageId = pendingAnchorId ?: return
        val owner = ports.history ?: return
        if (loadingHistory || !hasOlder) return
        loadingHistory = true
        scope.launch {
            try {
                val loaded = locateV6HistoryAnchor(owner, target.sessionId, messages,
                    cursor, hasOlder, messageId, 25)
                val active = if (workMode) ports.work.snapshot().identity else ports.chat.snapshot().identity
                if (active != target || pendingAnchorId != messageId) return@launch
                val nextMessages = loaded.messages
                if (nextMessages != messages) older = nextMessages.filterNot { it.id in seen }
                cursor = loaded.nextCursor
                hasOlder = loaded.hasOlder
                if (loaded.found) {
                    // Suppress the visible-position observer while removing the temporary
                    // "continue" row, then scroll using the final list's actual header count.
                    anchorScrollInProgress = true
                    try {
                        pendingAnchorId = null
                        androidx.compose.runtime.withFrameNanos { }
                        val offset = if (loaded.hasOlder) 1 else 0
                        list.scrollToItem(nextMessages.indexOfFirst { it.id == messageId } + offset)
                        feedback = null
                    } finally { anchorScrollInProgress = false }
                } else if (!loaded.hasOlder) {
                    pendingAnchorId = null
                    feedback = "已检索可用历史，目标消息不存在或已失效"
                } else {
                    feedback = "仍未定位到目标；可继续读取下一批历史"
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { feedback = failure.message ?: "继续定位失败" }
            finally { loadingHistory = false }
        }
    }

    if (inspectedAttachment != null) AlertDialog(
        onDismissRequest = { inspectedAttachment = null },
        title = { Text("附件状态与预览") },
        text = {
            Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                inspectedAttachment?.let { info ->
                    Text(info.displayName)
                    Text(info.mediaType + " · " + info.bytes + " 字节")
                    SyNote(if (info.digestVerified) "当前文件完整性已校验"
                        else "附件完整性尚未确认，请重新检查")
                    val preview = info.preview
                    if (preview != null) Text(preview)
                    else SyNote("此类文件提供元数据与状态；二进制内容暂无直接播放器")
                    if (info.truncated) SyNote("文本仅显示前16 KiB")
                }
            }
        },
        confirmButton = { TextButton(onClick = { inspectedAttachment = null }) { Text("关闭") } },
    )

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
                        is UiAttachmentReceipt.Imported -> {
                            when (v6AttachmentDecision(ports.drafts.attachments.value[target.sessionId].orEmpty(),
                                result.attachment)) {
                                V6AttachmentDecision.ACCEPT ->
                                    ports.drafts.addAttachment(target.sessionId, result.attachment)
                                V6AttachmentDecision.DUPLICATE ->
                                    feedback = "附件已经添加，无需重复导入"
                                V6AttachmentDecision.IMAGE_LIMIT ->
                                    feedback = "当前会话单次最多添加20张图片"
                            }
                        }
                        is UiAttachmentReceipt.Rejected ->
                            feedback = "附件未导入：${v6RejectionLabel(result.reason)}"
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
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SyInput(editText, { editText = it; editError = null }, label = "修改内容", singleLine = false, maxLines = 8)
                editError?.let { Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
            }
        },
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
            else editError = describeV6ConversationReceipt(result)
        }, enabled = identity != null && editText.isNotBlank()) { Text("保存并重新发送") } },
        dismissButton = { TextButton(onClick = { editing = null }) { Text("取消") } },
    )

    @Composable
    fun WorkInteractionContent() {
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
                    Text("风险级别：${v6ApprovalLabel(approval.impact)}")
                    SyButton("查看并批准", { approvalConfirmation = approval.pendingId })
                    if (approvalConfirmation == approval.pendingId) AlertDialog(
                        onDismissRequest = { approvalConfirmation = null },
                        title = { Text("确认仅批准当前工具调用") },
                        text = {
                            Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(approval.toolName)
                                Text(approval.summary)
                                Text(approval.arguments)
                                Text("影响等级：" + v6ApprovalLabel(approval.impact))
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
    }

    var messageActionId by remember(identity) { mutableStateOf<String?>(null) }
    val contextMessage = messages.firstOrNull { it.id == messageActionId }
    if (messageActionId != null) {
        V6MessageContextSheet(contextMessage,
            canEdit = identity != null && phase == UiExecutionPhase.IDLE && status != UiSceneStatus.LOADING,
            canRegenerate = identity != null && phase == UiExecutionPhase.IDLE &&
                status != UiSceneStatus.LOADING && contextMessage?.id == messages.lastOrNull()?.id,
            onDismiss = { messageActionId = null },
            onEdit = { message ->
                editing = message; editText = message.editableText.orEmpty(); editError = null
                messageActionId = null
            },
            onRegenerate = { message ->
                val target = identity
                if (target != null && (if (workMode) ports.work.snapshot().identity else ports.chat.snapshot().identity) == target) {
                    show(if (workMode) ports.work.submit(WorkConversationUiCommand.RegenerateReply(target, message.id))
                        else ports.chat.submit(ChatUiCommand.RegenerateReply(target, message.id)))
                }
                messageActionId = null
            })
    }
    BoxWithConstraints(Modifier.fillMaxSize().imePadding().padding(12.dp)) {
    val isCompactViewport = maxHeight < 480.dp
    val composerLines = when { maxHeight < 360.dp -> 1; isCompactViewport -> 2; else -> 5 }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (part == V6ConversationPart.FULL || part == V6ConversationPart.HEADER) {
        if (onNavigate != null) V6ConversationEntries(ports, modelSettings, sessions, availableScenes,
            workMode, identity, chat.group != null, isCompactViewport, onBack, onNavigate)
        else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(if (workMode) "工作" else "聊天", style = MaterialTheme.typography.titleLarge)
            SyButton("返回", onBack, style = SyButtonStyle.GHOST)
        }
        if (identity == null || status == UiSceneStatus.LOADING) {
            SyNote("正在加载会话，请稍候")
        } else {
            V6ExecutionFeedback(phase, workMode, queued, ownerError != null)
        }
        }
        if (part == V6ConversationPart.FULL || part == V6ConversationPart.TIMELINE) LazyColumn(
            state = list,
            modifier = (if (part == V6ConversationPart.FULL) Modifier.weight(1f)
                else Modifier.fillMaxSize()).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (pendingAnchorId != null && hasOlder) item(key = "anchor-search") {
                SyButton("继续定位原消息", { continueAnchorSearch() },
                    enabled = !loadingHistory && identity != null,
                    style = SyButtonStyle.SECONDARY)
                SyNote("还在查找原消息，你可以继续加载更早的记录")
            }
            if (ports.history != null && hasOlder) item(key = "older") {
                SyButton(if (loadingHistory) "读取中" else "加载更早消息",
                    { loadOlder() }, enabled = !loadingHistory && identity != null,
                    style = SyButtonStyle.SECONDARY)
            }
            items(messages, key = { "message:${it.id}" }) { message ->
                SyEntrance("message:${message.id}", enabled = initialMessages?.let {
                    message.id !in it && message.id in seen
                } == true) {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.fillMaxWidth().combinedClickable(
                            onClick = {}, onLongClickLabel = "消息操作",
                            onLongClick = { messageActionId = message.id })) {
                            V6MessageBody(message, workMode,
                                onOpenFile = if (ports.files != null && identity != null) {
                                    { path -> readingFile = path }
                                } else null)
                        }
                        SyButton("操作", { messageActionId = message.id }, style = SyButtonStyle.GHOST, compact = true)
                        val documentState = docSnapshot
                        if (docOwner != null && formOwner != null && identity != null &&
                            documentState != null && documentState.sessionId == identity.sessionId) {
                            val related = documentState.documents.filter {
                                it.document.source.sessionId == identity.sessionId &&
                                    it.document.source.messageId == message.id
                            }
                            related.forEach { document ->
                                androidx.compose.runtime.key(document.document.documentId) {
                                    SyEntrance("document:${document.document.documentId}") {
                                        androidx.compose.runtime.key(document.document.revision) {
                                            V6DocumentCard(document, identity, docOwner, formOwner, ports.media,
                                                identityIsCurrent = {
                                                    (if (workMode) ports.work.snapshot().identity
                                                    else ports.chat.snapshot().identity) == identity
                                                })
                                        }
                                    }
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
            }
            if (messages.isEmpty() && status == UiSceneStatus.EMPTY)
                item(key = "empty") { SyNote("当前会话还没有消息") }
            if (part == V6ConversationPart.FULL && workMode && identity != null) {
                item(key = "work-interaction") { WorkInteractionContent() }
                ports.evidence?.let { evidence ->
                    item(key = "work-evidence") { V6WorkEvidencePanel(evidence, identity) }
                }
            }
        }
        if (part == V6ConversationPart.WORK_QUESTION || part == V6ConversationPart.WORK_APPROVAL) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                WorkInteractionContent()
            }
        }
        if (part == V6ConversationPart.FULL || part == V6ConversationPart.REPLY_STATUS) {
            ownerError?.let { SyNote(it) }
            feedback?.let { notice ->
                SyEntrance("feedback:$notice") { Text(notice, Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
            }
            if (part == V6ConversationPart.REPLY_STATUS) {
                V6ExecutionFeedback(phase, workMode, queued, ownerError != null)
            }
        }
        if (part == V6ConversationPart.FULL || part == V6ConversationPart.COMPOSER) {
        if (attachmentsOpen) com.shenyan.designsystem.components.SyModalSheet(
            onDismiss = { attachmentsOpen = false }, title = "待发送附件（${imported.size}）") {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                feedback?.let { Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                if (ports.attachments != null) SyButton("添加附件", {
                    pickerIdentity = identity
                    picker()
                }, enabled = identity != null && !importing, style = SyButtonStyle.SECONDARY)
        imported.forEach { file ->
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Column(Modifier.fillMaxWidth()) {
                    Text(file.name)
                    val dimensions = if (file.width != null && file.height != null)
                        " · ${file.width}×${file.height}" else ""
                    Text("${file.mediaType} · ${file.bytes} 字节$dimensions",
                        style = MaterialTheme.typography.labelSmall)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (ports.attachments != null) SyButton(
                    if (inspectionPath == file.relativePath) "检查中" else "检查/预览", {
                        val target = identity ?: return@SyButton
                        val inspector = ports.attachments ?: return@SyButton
                        if (inspectionPath != null) return@SyButton
                        inspectionPath = file.relativePath
                        scope.launch {
                            try {
                                val result = inspector.inspect(InspectUiAttachmentCommand(target, file))
                                val current = (if (workMode) ports.work.snapshot().identity
                                    else ports.chat.snapshot().identity) == target
                                if (current) when (result) {
                                    is UiAttachmentInspectionReceipt.Ready -> inspectedAttachment = result.value
                                    is UiAttachmentInspectionReceipt.Unavailable -> feedback = result.message
                                    is UiAttachmentInspectionReceipt.Rejected ->
                                        feedback = "附件暂时无法查看：" + v6RejectionLabel(result.reason)
                                }
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (failure: Exception) { feedback = failure.message ?: "附件检查失败" }
                            finally { inspectionPath = null }
                        }
                    }, enabled = inspectionPath == null, style = SyButtonStyle.SECONDARY)
                SyButton("移除", {
                    identity?.let { ports.drafts.removeAttachment(it.sessionId, file.relativePath) }
                }, style = SyButtonStyle.GHOST)
                }
            }
        }
            }
        }
        SyInput(draft, { value ->
            identity?.let { ports.drafts.updateDraft(it.sessionId, value) }
        }, label = "输入消息", enabled = identity != null, singleLine = false, maxLines = composerLines)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
                if (!workMode && ports.replySuggestions != null && onNavigate != null &&
                    (availableScenes == null || UiSceneId("chat.reply_suggestions") in availableScenes)) {
                    SyButton("回复建议", {
                        if (onNavigate(UiSceneId("chat.reply_suggestions")) !is UiSceneNavigationResult.Changed)
                            feedback = "回复建议暂时无法打开，请稍后重试"
                    }, style = SyButtonStyle.GHOST, compact = true)
                }
                SyAnimatedReveal(imported.isNotEmpty()) {
                    SyButton("附件（${imported.size}）", {
                        feedback = null
                        attachmentsOpen = true
                    }, style = SyButtonStyle.GHOST, compact = true)
                }
                SyAnimatedReveal(imported.isEmpty() && ports.attachments != null) {
                    SyButton("添加附件", {
                        pickerIdentity = identity
                        picker()
                    }, enabled = identity != null && !importing, style = SyButtonStyle.SECONDARY, compact = true)
                }
            }
            SyAnimatedReveal(phase == UiExecutionPhase.RUNNING && identity != null) {
                if (identity != null) SyButton("停止", {
                    show(if (workMode) ports.work.submit(WorkConversationUiCommand.Stop(identity))
                        else ports.chat.submit(ChatUiCommand.Stop(identity)))
                }, enabled = phase == UiExecutionPhase.RUNNING, style = SyButtonStyle.SECONDARY)
            }
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
            Text(v6RejectionLabel(rejection), color = LocalSyColors.current.textSecondary)
        }
    }
    }
}

/**
 * Opaque paging only: never guess message offsets from timestamps, IDs or search snippets.
 * The bounded result is intentionally incomplete on very long histories; callers must
 * disclose that instead of claiming to have located a missing target.
 */
/** Never trust a saved per-session continuation cursor or a stale target after process death. */
internal fun recoverableV6PendingAnchor(savedMessageId: String?, anchor: UiReadingAnchor?): String? =
    savedMessageId?.takeIf { id ->
        id.isNotBlank() && id.length <= 256 &&
            anchor?.followingTail == false && anchor.messageId == id
    }

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

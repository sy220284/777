package com.labteto.dshmobile.uipack.next

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labteto.dshmobile.uicontract.ChatUiCommand
import com.labteto.dshmobile.uicontract.ConversationDraftUiPort
import com.labteto.dshmobile.uicontract.ChatUiPort
import com.labteto.dshmobile.uicontract.UiAccessibility
import com.labteto.dshmobile.uicontract.UiCapabilityId
import com.labteto.dshmobile.uicontract.UiCommandReceipt
import com.labteto.dshmobile.uicontract.UiCommandRejection
import com.labteto.dshmobile.uicontract.UiExecutionPhase
import com.labteto.dshmobile.uicontract.UiMessageRole
import com.labteto.dshmobile.uicontract.UiMessageSnapshot
import com.labteto.dshmobile.uicontract.UiPackManifest
import com.labteto.dshmobile.uicontract.UiSceneId
import com.labteto.dshmobile.uicontract.UiSceneStatus
import com.labteto.dshmobile.uicontract.UiWindowClass
import com.labteto.dshmobile.uicontract.WorkConversationUiCommand
import com.labteto.dshmobile.uicontract.WorkConversationUiPort
import com.labteto.dshmobile.uihost.ConversationUiPack

/** Deliberately plain, flat layout. Only contract + Host SDK + its own Material components. */
object NextConversationUiPack : ConversationUiPack {
    override val manifest = UiPackManifest(
        id = "next-conversation-slice", version = 1, minContractVersion = 1, maxContractVersion = 1,
        scenes = setOf("conversation.chat", "conversation.work", "conversation.composer", "work.approval",
            "conversation.timeline", "conversation.edit", "work.question", "conversation.ui_document", "conversation.ui_document_generate")
            .map(::UiSceneId).toSet(),
        requiredCapabilities = setOf("chat.send_text", "work.send_text", "work.answer_approval",
            "ui_document.generate", "ui_document.generate_local", "ui_document.prepare_input", "attachment.import",
            "chat.edit", "chat.regenerate", "chat.select_variant", "chat.stop",
            "work.edit", "work.regenerate", "work.stop", "work.answer_question")
            .map(::UiCapabilityId).toSet(),
        windowClasses = UiWindowClass.entries.toSet(),
        accessibility = UiAccessibility.entries.toSet(),
        supportsDark = true,
    )

    @Composable
    override fun Render(
        documents: com.labteto.dshmobile.uicontract.UiDocumentUiPort?,
        forms: com.labteto.dshmobile.uicontract.UiDocumentFormUiPort?,
        reading: com.labteto.dshmobile.uicontract.ConversationReadingUiPort,
        drafts: ConversationDraftUiPort,
        chat: ChatUiPort,
        work: WorkConversationUiPort,
        workMode: Boolean,
        onClose: () -> Unit,
        attachments: com.labteto.dshmobile.uicontract.AttachmentUiPort?,
    ) {
        val initialChat = remember(chat) { chat.snapshot() }
        val initialWork = remember(work) { work.snapshot() }
        val chatSnapshot by chat.snapshots.collectAsStateWithLifecycle(initialValue = initialChat)
        val workSnapshot by work.snapshots.collectAsStateWithLifecycle(initialValue = initialWork)
        val documentSnapshot = documents?.snapshots?.collectAsStateWithLifecycle(
            initialValue = remember(documents) { documents.snapshot() },
        )?.value
        val identity = if (workMode) workSnapshot.identity else chatSnapshot.identity
        val phase = if (workMode) workSnapshot.phase else chatSnapshot.phase
        val messages = if (workMode) workSnapshot.messages else chatSnapshot.messages
        val send = if (workMode) workSnapshot.send else chatSnapshot.send
        val queued = if (workMode) workSnapshot.queuedInputCount else chatSnapshot.queuedInputCount
        val error = if (workMode) workSnapshot.error else chatSnapshot.error
        val status = if (workMode) workSnapshot.status else chatSnapshot.status
        val approval = workSnapshot.approval.takeIf { workMode }
        val question = workSnapshot.question.takeIf { workMode }
        var questionAnswer by remember(question?.pendingId) { mutableStateOf("") }
        val listState = remember(identity?.sessionId) { LazyListState() }
        var readingAnchorMissing by remember(identity?.sessionId) { mutableStateOf(false) }
        var readingReady by remember(identity?.sessionId) { mutableStateOf(false) }
        LaunchedEffect(identity?.sessionId, status, messages.isNotEmpty()) {
            val sessionId = identity?.sessionId ?: return@LaunchedEffect
            if (readingReady || status == UiSceneStatus.LOADING) return@LaunchedEffect
            val anchor = reading.anchor(sessionId)
            val index = messages.indexOfFirst { it.id == anchor?.messageId }
            if (anchor != null && !anchor.followingTail && index >= 0) {
                listState.scrollToItem(index, anchor.offsetPx)
            } else if (messages.isNotEmpty()) {
                readingAnchorMissing = anchor != null && !anchor.followingTail && index < 0
                listState.scrollToItem(messages.lastIndex)
            }
            readingReady = true
        }
        LaunchedEffect(identity?.sessionId, readingReady, messages.lastOrNull()?.id) {
            val sessionId = identity?.sessionId ?: return@LaunchedEffect
            if (readingReady && reading.anchor(sessionId)?.followingTail == true && messages.isNotEmpty()) {
                listState.scrollToItem(messages.lastIndex)
            }
        }
        LaunchedEffect(identity?.sessionId, readingReady, messages) {
            val sessionId = identity?.sessionId ?: return@LaunchedEffect
            if (!readingReady) return@LaunchedEffect
            snapshotFlow {
                val first = listState.layoutInfo.visibleItemsInfo.firstOrNull()
                com.labteto.dshmobile.uicontract.UiReadingAnchor(
                    messages.getOrNull(first?.index ?: -1)?.id,
                    listState.firstVisibleItemScrollOffset,
                    !listState.canScrollForward,
                )
            }.collect { anchor ->
                if (anchor.messageId != null || anchor.followingTail) reading.rememberAnchor(sessionId, anchor)
            }
        }
        val draftValues by drafts.drafts.collectAsStateWithLifecycle()
        val draft = draftValues[identity?.sessionId].orEmpty()
        var receipt by rememberSaveable(identity?.sessionId) { mutableStateOf<String?>(null) }
        val accepted = stringResource(R.string.next_accepted)
        val enqueued = stringResource(R.string.next_enqueued)
        val rejected = stringResource(R.string.next_rejected)
        val unknown = stringResource(R.string.next_unknown)
        val rejectionLabels = UiCommandRejection.entries.associateWith { reason ->
            stringResource(when (reason) {
                UiCommandRejection.EMPTY_INPUT -> R.string.next_reject_empty
                UiCommandRejection.LOADING -> R.string.next_reject_loading
                UiCommandRejection.UNCONFIGURED -> R.string.next_reject_config
                UiCommandRejection.STALE_IDENTITY -> R.string.next_stale
                UiCommandRejection.SESSION_TRANSITION -> R.string.next_reject_transition
                UiCommandRejection.QUEUE_FULL -> R.string.next_reject_queue_full
                UiCommandRejection.QUEUE_UNAVAILABLE -> R.string.next_reject_queue_unavailable
                UiCommandRejection.QUESTION_EXPIRED -> R.string.next_reject_question
                UiCommandRejection.APPROVAL_EXPIRED -> R.string.next_reject_approval
                UiCommandRejection.UNSUPPORTED -> R.string.next_reject_unsupported
            })
        }
        val importScope = rememberCoroutineScope()
        val attachmentDrafts by drafts.attachments.collectAsStateWithLifecycle()
        val selectedAttachments = attachmentDrafts[identity?.sessionId].orEmpty()
        var pickerIdentity by remember { mutableStateOf<com.labteto.dshmobile.uicontract.UiSessionIdentity?>(null) }
        var importing by remember { mutableStateOf(false) }
        val importFailed = stringResource(R.string.next_attachment_failed)
        val pickAttachment = com.labteto.dshmobile.uihost.rememberUiAttachmentPicker { uri ->
            val target = pickerIdentity
            val port = attachments
            if (uri == null || target == null || port == null) { importing = false; pickerIdentity = null }
            else importScope.launch {
                try {
                    when (val result = port.import(com.labteto.dshmobile.uicontract.ImportUiAttachmentCommand(target, uri))) {
                        is com.labteto.dshmobile.uicontract.UiAttachmentReceipt.Imported -> {
                            drafts.addAttachment(target.sessionId, result.attachment)
                        }
                        else -> receipt = importFailed
                    }
                } finally { importing = false; pickerIdentity = null }
            }
        }
        val editCommitted = stringResource(R.string.next_edit_committed)
        val editUnavailable = stringResource(R.string.next_edit_unavailable)
        var editing by remember(identity) { mutableStateOf<UiMessageSnapshot?>(null) }
        var replacement by remember(identity) { mutableStateOf("") }
        fun show(result: UiCommandReceipt) {
            receipt = when (result) {
                UiCommandReceipt.Accepted -> accepted
                UiCommandReceipt.Queued -> enqueued
                is UiCommandReceipt.Rejected -> rejected.format(rejectionLabels.getValue(result.reason))
                is UiCommandReceipt.Failed -> result.message
                UiCommandReceipt.NotAdmitted -> editUnavailable
                is UiCommandReceipt.MessageEdited -> when (result.result) {
                    com.labteto.dshmobile.local.session.LocalUserMessageEditResult.SENT -> accepted
                    com.labteto.dshmobile.local.session.LocalUserMessageEditResult.COMMITTED_NOT_STARTED -> editCommitted
                    else -> editUnavailable
                }
                UiCommandReceipt.OutcomeUnknown -> unknown
            }
        }
        if (editing != null) AlertDialog(onDismissRequest = { editing = null },
            title = { Text(stringResource(R.string.next_edit)) },
            text = { OutlinedTextField(replacement, { replacement = it }, label = { Text(stringResource(R.string.next_input)) }) },
            confirmButton = { TextButton(onClick = {
                val target = identity ?: return@TextButton
                val message = editing ?: return@TextButton
                val result = if (workMode) work.submit(WorkConversationUiCommand.EditUserMessage(target, message.id, replacement))
                    else chat.submit(ChatUiCommand.EditUserMessage(target, message.id, replacement))
                show(result)
                if (result is UiCommandReceipt.MessageEdited && (result.result == com.labteto.dshmobile.local.session.LocalUserMessageEditResult.SENT ||
                    result.result == com.labteto.dshmobile.local.session.LocalUserMessageEditResult.COMMITTED_NOT_STARTED)) editing = null
            }, enabled = identity != null && replacement.isNotBlank()) { Text(stringResource(R.string.next_edit_send)) } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text(stringResource(R.string.next_cancel)) } })
        MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.safeDrawingPadding().imePadding().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.next_title), style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.semantics { heading() })
                    Text(stringResource(R.string.next_scope), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = onClose) { Text(stringResource(R.string.next_close)) }
                    Text(stringResource(when (phase) {
                        UiExecutionPhase.IDLE -> R.string.next_idle
                        UiExecutionPhase.RUNNING -> R.string.next_running
                        UiExecutionPhase.WAITING_FOR_USER -> R.string.next_waiting
                    }))
                    if (phase != UiExecutionPhase.IDLE) TextButton(onClick = {
                        identity?.let { target -> show(if (workMode) work.submit(WorkConversationUiCommand.Stop(target))
                            else chat.submit(ChatUiCommand.Stop(target))) }
                    }, enabled = identity != null) { Text(stringResource(R.string.next_stop)) }
                    if (queued > 0) Text(stringResource(R.string.next_queued, queued))
                    if (status == UiSceneStatus.LOADING) Text(stringResource(R.string.next_loading))
                    if (status == UiSceneStatus.EMPTY) Text(stringResource(R.string.next_empty))
                    if (documentSnapshot?.error != null) Text(stringResource(R.string.next_document_history_unavailable))
                    if (readingAnchorMissing) Text(stringResource(R.string.next_reading_anchor_unavailable))
                    LazyColumn(Modifier.weight(1f), state = listState, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(messages, key = UiMessageSnapshot::id) { message ->
                            Column {
                                Text(stringResource(when (message.role) {
                                    UiMessageRole.USER -> R.string.next_role_user
                                    UiMessageRole.ASSISTANT -> R.string.next_role_assistant
                                    UiMessageRole.SYSTEM -> R.string.next_role_system
                                    UiMessageRole.TOOL -> R.string.next_role_tool
                                }), style = MaterialTheme.typography.labelMedium)
                                Text(message.text)
                                val actionsAvailable = identity != null && phase == UiExecutionPhase.IDLE && queued == 0 && send.available
                                if (message.editableText != null) TextButton(onClick = {
                                    editing = message; replacement = message.editableText.orEmpty()
                                }, enabled = actionsAvailable) { Text(stringResource(R.string.next_edit)) }
                                if (message.role == UiMessageRole.ASSISTANT && !message.proactive && message.id == messages.lastOrNull()?.id) {
                                    TextButton(onClick = {
                                        identity?.let { target -> show(if (workMode)
                                            work.submit(WorkConversationUiCommand.RegenerateReply(target, message.id))
                                            else chat.submit(ChatUiCommand.RegenerateReply(target, message.id))) }
                                    }, enabled = actionsAvailable) { Text(stringResource(R.string.next_regenerate)) }
                                }
                                message.branch?.let { branch ->
                                    Text(stringResource(R.string.next_variant, branch.index + 1, branch.count))
                                    TextButton(onClick = { identity?.let { show(chat.submit(ChatUiCommand.SelectVariant(it, message.id, branch.index - 1))) } },
                                        enabled = actionsAvailable && branch.index > 0) { Text(stringResource(R.string.next_previous_variant)) }
                                    TextButton(onClick = { identity?.let { show(chat.submit(ChatUiCommand.SelectVariant(it, message.id, branch.index + 1))) } },
                                        enabled = actionsAvailable && branch.index + 1 < branch.count) { Text(stringResource(R.string.next_next_variant)) }
                                }
                                if (documents != null && message.id == messages.lastOrNull()?.id && status != UiSceneStatus.LOADING) {
                                    NextDocumentGenerateButton(identity, workMode, message.id, message.text, documents, allowAiDesign = phase == UiExecutionPhase.IDLE)
                                }
                                if (documents != null && forms != null) {
                                    documentSnapshot?.documents?.filter { it.document.source.messageId == message.id }?.forEach {
                                        NextUiDocument(it, identity, documents, forms)
                                    }
                                }
                                HorizontalDivider()
                            }
                        }
                        if (question != null) item(key = "question:${question.pendingId}") {
                            Text(question.question, style = MaterialTheme.typography.titleMedium)
                            question.options.forEach { option -> TextButton(onClick = {
                                identity?.let { show(work.submit(WorkConversationUiCommand.AnswerQuestion(it, question.pendingId, option))) }
                            }, enabled = identity != null) { Text(option) } }
                            OutlinedTextField(questionAnswer, { questionAnswer = it }, label = { Text(stringResource(R.string.next_answer)) })
                            Button(onClick = { identity?.let { show(work.submit(WorkConversationUiCommand.AnswerQuestion(it, question.pendingId, questionAnswer))) } },
                                enabled = identity != null && questionAnswer.isNotBlank()) { Text(stringResource(R.string.next_submit_answer)) }
                            TextButton(onClick = { identity?.let { show(work.submit(WorkConversationUiCommand.AnswerQuestion(it, question.pendingId, null))) } },
                                enabled = identity != null) { Text(stringResource(R.string.next_cancel_question)) }
                        }
                        if (approval != null) item(key = "approval:${approval.pendingId}") {
                            Text(approval.toolName, style = MaterialTheme.typography.titleMedium)
                            Text(approval.summary)
                            Text(approval.arguments, style = MaterialTheme.typography.bodySmall)
                            // Vertical actions stay reachable when system font size is large.
                            Column {
                                Button(onClick = {
                                    identity?.let { show(work.submit(WorkConversationUiCommand.AnswerApproval(it, approval.pendingId, true))) }
                                }) { Text(stringResource(R.string.next_approve)) }
                                TextButton(onClick = {
                                    identity?.let { show(work.submit(WorkConversationUiCommand.AnswerApproval(it, approval.pendingId, false))) }
                                }) { Text(stringResource(R.string.next_deny)) }
                            }
                        }
                    }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (!send.available) send.reason?.let { Text(rejectionLabels.getValue(it)) }
                    receipt?.let { Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                    if (attachments != null) TextButton(onClick = {
                        pickerIdentity = identity; importing = true; pickAttachment()
                    }, enabled = identity != null && !importing) { Text(stringResource(R.string.next_attach)) }
                    selectedAttachments.forEach { attachment ->
                        Text("${attachment.name} (${attachment.bytes} B)")
                        TextButton(onClick = { identity?.let { drafts.removeAttachment(it.sessionId, attachment.relativePath) } }) {
                            Text(stringResource(R.string.next_remove_attachment))
                        }
                    }
                    OutlinedTextField(value = draft, onValueChange = { text -> identity?.let { drafts.updateDraft(it.sessionId, text) } }, modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.next_input)) }, maxLines = 5)
                    Button(enabled = send.available && (draft.isNotBlank() || selectedAttachments.isNotEmpty()) && identity != null, onClick = {
                        val target = identity ?: return@Button
                        val result = if (workMode) work.submit(WorkConversationUiCommand.SendMessage(target, draft, selectedAttachments))
                            else chat.submit(ChatUiCommand.SendMessage(target, draft, selectedAttachments))
                        show(result)
                        if (result == UiCommandReceipt.Accepted || result == UiCommandReceipt.Queued) { drafts.updateDraft(target.sessionId, ""); drafts.clearAttachments(target.sessionId) }
                    }) { Text(stringResource(if (phase == UiExecutionPhase.IDLE) R.string.next_send else R.string.next_queue)) }
                }
            }
        }
    }
}

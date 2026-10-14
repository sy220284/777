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
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
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
            "conversation.timeline", "conversation.ui_document", "conversation.ui_document_generate")
            .map(::UiSceneId).toSet(),
        requiredCapabilities = setOf("chat.send_text", "work.send_text", "work.answer_approval",
            "ui_document.generate", "ui_document.prepare_input")
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
                UiCommandRejection.APPROVAL_EXPIRED -> R.string.next_reject_approval
                UiCommandRejection.UNSUPPORTED -> R.string.next_reject_unsupported
            })
        }
        fun show(result: UiCommandReceipt) {
            receipt = when (result) {
                UiCommandReceipt.Accepted -> accepted
                UiCommandReceipt.Queued -> enqueued
                is UiCommandReceipt.Rejected -> rejected.format(rejectionLabels.getValue(result.reason))
                is UiCommandReceipt.Failed -> result.message
                UiCommandReceipt.OutcomeUnknown -> unknown
            }
        }
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
                                if (documents != null && message.id == messages.lastOrNull()?.id && phase == UiExecutionPhase.IDLE) {
                                    NextDocumentGenerateButton(identity, workMode, message.id, message.text, documents)
                                }
                                if (documents != null && forms != null) {
                                    documentSnapshot?.documents?.filter { it.document.source.messageId == message.id }?.forEach {
                                        NextUiDocument(it, identity, documents, forms)
                                    }
                                }
                                HorizontalDivider()
                            }
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
                    OutlinedTextField(value = draft, onValueChange = { text -> identity?.let { drafts.updateDraft(it.sessionId, text) } }, modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.next_input)) }, maxLines = 5)
                    Button(enabled = send.available && draft.isNotBlank() && identity != null, onClick = {
                        val target = identity ?: return@Button
                        val result = if (workMode) work.submit(WorkConversationUiCommand.SendText(target, draft))
                            else chat.submit(ChatUiCommand.SendText(target, draft))
                        show(result)
                        if (result == UiCommandReceipt.Accepted || result == UiCommandReceipt.Queued) drafts.updateDraft(target.sessionId, "")
                    }) { Text(stringResource(if (phase == UiExecutionPhase.IDLE) R.string.next_send else R.string.next_queue)) }
                }
            }
        }
    }
}

package com.labteto.dshmobile.uipack.next

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
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
            "conversation.timeline", "conversation.edit", "work.question", "conversation.ui_document", "conversation.ui_document_generate",
            "chat.group_members", "work.team", "work.requirement_evidence")
            .map(::UiSceneId).toSet(),
        requiredCapabilities = setOf("chat.send_text", "work.send_text", "work.answer_approval",
            "ui_document.generate", "ui_document.generate_local", "ui_document.prepare_input", "attachment.import",
            "chat.edit", "chat.regenerate", "chat.select_variant", "chat.stop", "session.transcript_read",
            "work.edit", "work.regenerate", "work.stop", "work.answer_question",
            "chat.group_members", "work.team_control", "shell.session_preferences", "work.evidence_reference")
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
        sessions: com.labteto.dshmobile.uicontract.SessionUiPort?,
        transcript: com.labteto.dshmobile.uicontract.TranscriptUiPort?,
        policy: com.labteto.dshmobile.uicontract.WorkPolicyUiPort?,
        workReads: com.labteto.dshmobile.uicontract.WorkReadUiPort?,
        files: com.labteto.dshmobile.uicontract.FilesUiPort?,
        evidence: com.labteto.dshmobile.uicontract.WorkEvidenceUiPort?,
        shell: com.labteto.dshmobile.uicontract.ConversationShellUiPort?,
        team: com.labteto.dshmobile.uicontract.WorkTeamUiPort?,
        groups: com.labteto.dshmobile.uicontract.ChatGroupUiPort?,
        gallery: com.labteto.dshmobile.uicontract.PersonaGalleryUiPort?,
        personas: com.labteto.dshmobile.uicontract.PersonaSelectionUiPort?,
        personaTransfer: com.labteto.dshmobile.uicontract.PersonaTransferUiPort?,
        stories: com.labteto.dshmobile.uicontract.PersonaStoryUiPort?,
        galleryDeletion: com.labteto.dshmobile.uicontract.PersonaGalleryDeletionUiPort?,
        gallerySave: com.labteto.dshmobile.uicontract.PersonaGallerySaveUiPort?,
        portraits: com.labteto.dshmobile.uicontract.PersonaPortraitUiPort?,
    ) {
        val policySnapshot = policy?.snapshots?.collectAsStateWithLifecycle(initialValue = policy.snapshot())?.value
        val sessionSnapshot = sessions?.snapshots?.collectAsStateWithLifecycle(initialValue = sessions.snapshot())?.value
        val pinnedSessions = shell?.pinnedSessionIds?.collectAsStateWithLifecycle()?.value.orEmpty()
        val titleOverrides = shell?.sessionTitleOverrides?.collectAsStateWithLifecycle()?.value.orEmpty()
        var renamingSession by remember { mutableStateOf<String?>(null) }
        var sessionTitle by remember { mutableStateOf("") }
        var deletingSession by remember { mutableStateOf<String?>(null) }
        var deleting by remember { mutableStateOf(false) }
        var deletionError by remember { mutableStateOf<String?>(null) }
        var sessionChooser by remember { mutableStateOf(false) }
        val initialChat = remember(chat) { chat.snapshot() }
        val initialWork = remember(work) { work.snapshot() }
        val chatSnapshot by chat.snapshots.collectAsStateWithLifecycle(initialValue = initialChat)
        val workSnapshot by work.snapshots.collectAsStateWithLifecycle(initialValue = initialWork)
        val documentSnapshot = documents?.snapshots?.collectAsStateWithLifecycle(
            initialValue = remember(documents) { documents.snapshot() },
        )?.value
        val identity = if (workMode) workSnapshot.identity else chatSnapshot.identity
        val phase = if (workMode) workSnapshot.phase else chatSnapshot.phase
        val hotMessages = if (workMode) workSnapshot.messages else chatSnapshot.messages
        var olderMessages by remember(identity) { mutableStateOf(emptyList<UiMessageSnapshot>()) }
        var historyCursor by remember(identity) { mutableStateOf<com.labteto.dshmobile.uicontract.UiTranscriptCursor?>(null) }
        var historyAvailable by remember(identity) { mutableStateOf(true) }
        var historyLoading by remember(identity) { mutableStateOf(false) }
        val hotMessageIds = remember(hotMessages) { hotMessages.map { it.id }.toSet() }
        val messages = remember(olderMessages, hotMessages) { olderMessages.filterNot { it.id in hotMessageIds } + hotMessages }
        val messageIds = remember(messages) { messages.map { it.id }.toSet() }
        val historyHeaderCount = if (transcript != null && historyAvailable) 1 else 0
        val send = if (workMode) workSnapshot.send else chatSnapshot.send
        val queued = if (workMode) workSnapshot.queuedInputCount else chatSnapshot.queuedInputCount
        val error = if (workMode) workSnapshot.error else chatSnapshot.error
        val status = if (workMode) workSnapshot.status else chatSnapshot.status
        val approval = workSnapshot.approval.takeIf { workMode }
        val question = workSnapshot.question.takeIf { workMode }
        var policyConsent by remember(identity) { mutableStateOf<com.labteto.dshmobile.uicontract.WorkPolicyUiCommand?>(null) }
        var continuation by remember(identity) { mutableStateOf<com.labteto.dshmobile.uicontract.SessionUiCommand.Create?>(null) }
        var continuationSummary by remember(identity) { mutableStateOf("") }
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
                listState.scrollToItem(index + historyHeaderCount, anchor.offsetPx)
            } else if (messages.isNotEmpty()) {
                readingAnchorMissing = anchor != null && !anchor.followingTail && index < 0
                listState.scrollToItem(messages.lastIndex + historyHeaderCount)
            }
            readingReady = true
        }
        LaunchedEffect(identity?.sessionId, readingReady, messages.lastOrNull()?.id, approval?.pendingId, question?.pendingId) {
            val sessionId = identity?.sessionId ?: return@LaunchedEffect
            if (readingReady && reading.anchor(sessionId)?.followingTail == true && messages.isNotEmpty()) {
                listState.scrollToItem(messages.lastIndex + historyHeaderCount + (if (approval != null) 1 else 0) + (if (question != null) 1 else 0))
            }
        }
        LaunchedEffect(identity?.sessionId, readingReady, messages) {
            val sessionId = identity?.sessionId ?: return@LaunchedEffect
            if (!readingReady) return@LaunchedEffect
            snapshotFlow {
                val first = listState.layoutInfo.visibleItemsInfo.firstOrNull {
                    val key = it.key as? String
                    key != null && key.startsWith("message:") && key.removePrefix("message:") in messageIds
                }
                com.labteto.dshmobile.uicontract.UiReadingAnchor(
                    (first?.key as? String)?.removePrefix("message:"),
                    first?.offset?.let { (-it).coerceAtLeast(0) } ?: 0,
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
        var workFactsOpen by remember(identity) { mutableStateOf(false) }
        var workFactsGeneration by remember(identity) { mutableStateOf(0L) }
        var toolActivities by remember(identity) { mutableStateOf(emptyList<com.labteto.dshmobile.local.presentation.LocalToolActivityUiItem>()) }
        var workFactsLoading by remember(identity) { mutableStateOf(false) }
        var workArtifacts by remember(identity) { mutableStateOf(emptyList<com.labteto.dshmobile.local.presentation.LocalArtifactUiItem>()) }
        var workHistory by remember(identity) { mutableStateOf<com.labteto.dshmobile.local.presentation.LocalWorkHistoryPageUi?>(null) }
        fun loadWorkFacts(older: Boolean) {
            val target = identity ?: return
            val owner = workReads ?: return
            if (workFactsLoading) return
            val cursor = if (older) workHistory?.older ?: return else null
            val generation = ++workFactsGeneration
            workFactsLoading = true
            importScope.launch {
                try {
                    val facts = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        Triple(owner.artifactsForUi(target.sessionId), owner.historyPageForUi(target.sessionId, cursor),
                            owner.toolActivitiesForUi(target.sessionId))
                    }
                    if (work.snapshot().identity != target || generation != workFactsGeneration) return@launch
                    toolActivities = facts.third
                    workArtifacts = facts.first
                    // Each page replaces the previous bounded page; do not retain the entire log in UI state.
                    workHistory = facts.second
                    workFactsOpen = true
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (failure: Exception) { if (work.snapshot().identity == target && generation == workFactsGeneration) receipt = failure.message }
                finally { if (generation == workFactsGeneration) workFactsLoading = false }
            }
        }
        fun closeWorkFacts() { workFactsGeneration++; workFactsOpen = false; workFactsLoading = false }
        val importFailed = stringResource(R.string.next_attachment_failed)
        val fileReadFailed = stringResource(R.string.next_file_read_failed)
        var fileChooser by remember(identity) { mutableStateOf(false) }
        var fileRows by remember(identity) { mutableStateOf(emptyList<com.labteto.dshmobile.local.tools.LocalWorkspaceFile>()) }
        var conversationFileRows by remember(identity) { mutableStateOf<com.labteto.dshmobile.local.session.LocalConversationFiles?>(null) }
        var filePreview by remember(identity) { mutableStateOf<com.labteto.dshmobile.local.tools.LocalWorkspaceFilePreview?>(null) }
        var fileError by remember(identity) { mutableStateOf<String?>(null) }
        var fileLoading by remember(identity) { mutableStateOf(false) }
        var fileRequestGeneration by remember(identity) { mutableStateOf(0L) }
        var fileTitle by remember(identity) { mutableStateOf(R.string.next_workspace_files) }
        fun fileRequestCurrent(target: com.labteto.dshmobile.uicontract.UiSessionIdentity?, generation: Long): Boolean =
            generation == fileRequestGeneration && (if (workMode) work.snapshot().identity else chat.snapshot().identity) == target
        fun closeFiles() {
            fileRequestGeneration++
            fileChooser = false
            fileLoading = false
            filePreview = null
            fileError = null
        }
        fun previewFile(path: String, standalone: Boolean = false) {
            val owner = files ?: return
            if (fileLoading) return
            val target = identity
            val generation = ++fileRequestGeneration
            fileLoading = true
            fileError = null
            filePreview = null
            if (standalone) {
                fileTitle = R.string.next_attachment_preview
                fileRows = emptyList()
                conversationFileRows = null
                fileChooser = true
            }
            importScope.launch {
                try {
                    val preview = owner.previewWorkspaceFile(path)
                    if (fileRequestCurrent(target, generation)) filePreview = preview
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (failure: Exception) { if (fileRequestCurrent(target, generation)) fileError = failure.message ?: fileReadFailed }
                finally { if (fileRequestCurrent(target, generation)) fileLoading = false }
            }
        }
        fun loadFiles(conversation: Boolean) {
            val owner = files ?: return
            if (fileLoading || conversation && identity == null) return
            val target = identity
            val generation = ++fileRequestGeneration
            fileLoading = true
            importScope.launch {
                try {
                    val sessionFiles = if (conversation) owner.conversationFiles(requireNotNull(target).sessionId) else null
                    val workspaceFiles = if (conversation) emptyList() else owner.workspaceFiles()
                    if (!fileRequestCurrent(target, generation)) return@launch
                    conversationFileRows = sessionFiles
                    fileRows = workspaceFiles
                    filePreview = null
                    fileError = null
                    fileTitle = if (conversation) R.string.next_conversation_files else R.string.next_workspace_files
                    fileChooser = true
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (failure: Exception) { if (fileRequestCurrent(target, generation)) receipt = failure.message ?: fileReadFailed }
                finally { if (fileRequestCurrent(target, generation)) fileLoading = false }
            }
        }
        val attachmentDrafts by drafts.attachments.collectAsStateWithLifecycle()
        val selectedAttachments = attachmentDrafts[identity?.sessionId].orEmpty()
        var pickerIdentity by remember { mutableStateOf<com.labteto.dshmobile.uicontract.UiSessionIdentity?>(null) }
        var importing by remember { mutableStateOf(false) }
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
        MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
        if (workFactsOpen) AlertDialog(onDismissRequest = { closeWorkFacts() },
            title = { Text(stringResource(R.string.next_work_facts)) },
            text = { LazyColumn(Modifier.heightIn(max = 360.dp)) {
                if (workHistory?.invalidated == true) item { Text(stringResource(R.string.next_work_history_expired)) }
                items(workArtifacts, key = { "${it.asOfSequence}:${it.category}:${it.reference}" }) { artifact ->
                    Text(artifact.reference)
                    Text("${artifact.category} · #${artifact.asOfSequence}")
                    if (artifact.currentlyAvailable == false) Text(stringResource(R.string.next_artifact_unavailable))
                    if (artifact.versionAtCreation != null && artifact.versionNow != null && artifact.versionAtCreation != artifact.versionNow)
                        Text(stringResource(R.string.next_artifact_changed))
                }
                if (toolActivities.isNotEmpty()) item { Text(stringResource(R.string.next_tool_activities)) }
                items(toolActivities, key = { "tool:${it.executionId}:${it.callId}:${it.declaredSequence}" }) { activity ->
                    Text(activity.name)
                    Text(stringResource(when (activity.phase) {
                        com.labteto.dshmobile.local.presentation.LocalToolUiPhase.DECLARED -> R.string.next_tool_declared
                        com.labteto.dshmobile.local.presentation.LocalToolUiPhase.RUNNING -> R.string.next_tool_running
                        com.labteto.dshmobile.local.presentation.LocalToolUiPhase.COMPLETED -> R.string.next_tool_completed
                        com.labteto.dshmobile.local.presentation.LocalToolUiPhase.FAILED -> R.string.next_tool_failed
                        com.labteto.dshmobile.local.presentation.LocalToolUiPhase.OUTCOME_UNKNOWN -> R.string.next_tool_unknown
                        com.labteto.dshmobile.local.presentation.LocalToolUiPhase.CANCELLED -> R.string.next_tool_cancelled
                    }))
                    activity.errorCode?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    activity.argumentsPreview?.let { Text(it) }
                    activity.resultPreview?.let { Text(it) }
                }
                items(workHistory?.records ?: emptyList(), key = { it.sequence }) { record ->
                    Text("#${record.sequence} ${record.name}")
                    Text(record.content)
                }
                if (workHistory?.older != null) item { TextButton(onClick = { loadWorkFacts(true) }, enabled = !workFactsLoading) {
                    Text(stringResource(R.string.next_load_older))
                } }
            } }, confirmButton = { TextButton(onClick = { closeWorkFacts() }) { Text(stringResource(R.string.next_close)) } })
        if (fileChooser && files != null) AlertDialog(onDismissRequest = { closeFiles() },
            title = { Text(stringResource(fileTitle)) },
            text = { LazyColumn(Modifier.heightIn(max = 360.dp)) {
                item { filePreview?.let { preview ->
                    Text(preview.file.path)
                    Text("${preview.file.bytes} B")
                    Text(preview.text ?: stringResource(R.string.next_binary_file))
                    if (preview.truncated) Text(stringResource(R.string.next_partial_preview))
                } }
                val sessionFiles = conversationFileRows
                if (sessionFiles != null) {
                    if (sessionFiles.isEmpty) item { Text(stringResource(R.string.next_no_conversation_files)) }
                    if (sessionFiles.artifacts.isNotEmpty()) item { Text(stringResource(R.string.next_file_artifacts)) }
                    items(sessionFiles.artifacts, key = { "artifact:${it.path}" }) { file ->
                        TextButton(onClick = { previewFile(file.path) }, enabled = !fileLoading) { Text(file.path) }
                    }
                    if (sessionFiles.involved.isNotEmpty()) item { Text(stringResource(R.string.next_file_involved)) }
                    items(sessionFiles.involved, key = { "involved:${it.path}" }) { file ->
                        TextButton(onClick = { previewFile(file.path) }, enabled = !fileLoading) { Text(file.path) }
                    }
                }
                items(fileRows, key = { "workspace:${it.path}" }) { file ->
                    TextButton(onClick = { previewFile(file.path) }, enabled = !fileLoading) { Text(file.path) }
                }
                fileError?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
                if (fileLoading) item { Text(stringResource(R.string.next_loading_files)) }
            } }, confirmButton = { TextButton(onClick = { closeFiles() }) { Text(stringResource(R.string.next_close)) } })
        if (policyConsent != null && policy != null) AlertDialog(onDismissRequest = { policyConsent = null },
            title = { Text(stringResource(R.string.next_policy_confirm)) },
            text = { Text(stringResource(when (policyConsent) {
                is com.labteto.dshmobile.uicontract.WorkPolicyUiCommand.ApproveWithAutoMode -> R.string.next_auto_policy_effect
                is com.labteto.dshmobile.uicontract.WorkPolicyUiCommand.SetApprovalMode -> R.string.next_default_policy_effect
                else -> R.string.next_device_policy_effect
            })) },
            confirmButton = { TextButton(onClick = { policyConsent?.let { show(policy.submit(it)) }; policyConsent = null }) {
                Text(stringResource(if (policyConsent is com.labteto.dshmobile.uicontract.WorkPolicyUiCommand.SetApprovalMode)
                    R.string.next_apply_policy else R.string.next_approve))
            } }, dismissButton = { TextButton(onClick = { policyConsent = null }) { Text(stringResource(R.string.next_cancel)) } })
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
        if (continuation != null && sessions != null) AlertDialog(onDismissRequest = { continuation = null },
            title = { Text(stringResource(R.string.next_continue_session)) },
            text = { OutlinedTextField(continuationSummary, { continuationSummary = it },
                label = { Text(stringResource(R.string.next_handoff_summary)) }, minLines = 3, maxLines = 8) },
            confirmButton = { TextButton(onClick = {
                val command = continuation ?: return@TextButton
                val result = sessions.submit(command.copy(handoffSummary = continuationSummary))
                show(result)
                if (result == UiCommandReceipt.Accepted || result == UiCommandReceipt.Queued) continuation = null
            }, enabled = continuationSummary.isNotBlank()) { Text(stringResource(R.string.next_create_continuation)) } },
            dismissButton = { TextButton(onClick = { continuation = null }) { Text(stringResource(R.string.next_cancel)) } })
        if (renamingSession != null && shell != null) AlertDialog(onDismissRequest = { renamingSession = null },
            title = { Text(stringResource(R.string.next_rename_session)) },
            text = { OutlinedTextField(sessionTitle, { sessionTitle = it }, label = { Text(stringResource(R.string.next_session_title)) }) },
            confirmButton = { TextButton(onClick = {
                val target = renamingSession ?: return@TextButton
                val result = shell.submit(com.labteto.dshmobile.uicontract.ConversationShellUiCommand.Rename(target, sessionTitle))
                show(result)
                if (result == UiCommandReceipt.Accepted) renamingSession = null
            }, enabled = sessionTitle.isNotBlank()) { Text(stringResource(R.string.next_save_session_title)) } },
            dismissButton = { TextButton(onClick = { renamingSession = null }) { Text(stringResource(R.string.next_cancel)) } })
        if (deletingSession != null && sessions != null) AlertDialog(onDismissRequest = { if (!deleting) deletingSession = null },
            title = { Text(stringResource(R.string.next_delete_session)) },
            text = { Column {
                Text(stringResource(R.string.next_delete_session_effect))
                deletionError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            } },
            confirmButton = { TextButton(onClick = {
                val target = deletingSession ?: return@TextButton
                deleting = true
                deletionError = null
                importScope.launch {
                    try {
                        when (val result = sessions.delete(setOf(target))) {
                            is com.labteto.dshmobile.uicontract.UiSessionDeletionReceipt.Deleted -> {
                                if (target in result.sessionIds) deletingSession = null
                                else deletionError = rejected
                            }
                            is com.labteto.dshmobile.uicontract.UiSessionDeletionReceipt.Failed -> deletionError = result.message ?: unknown
                        }
                    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (failure: Exception) { deletionError = failure.message ?: unknown }
                    finally { deleting = false }
                }
            }, enabled = !deleting) { Text(stringResource(R.string.next_delete_session)) } },
            dismissButton = { TextButton(onClick = { deletingSession = null }, enabled = !deleting) { Text(stringResource(R.string.next_cancel)) } })
        if (sessionChooser && sessions != null && sessionSnapshot != null) AlertDialog(onDismissRequest = { sessionChooser = false },
            title = { Text(stringResource(R.string.next_sessions)) },
            text = { LazyColumn(Modifier.heightIn(max = 360.dp)) {
                items(sessionSnapshot.sessions, key = { it.id }) { session ->
                    TextButton(onClick = { show(sessions.submit(com.labteto.dshmobile.uicontract.SessionUiCommand.Open(session.id))); sessionChooser = false }) {
                        Text(titleOverrides[session.id] ?: session.title)
                    }
                    TextButton(onClick = { deletingSession = session.id; deletionError = null; sessionChooser = false }) {
                        Text(stringResource(R.string.next_delete_session))
                    }
                    if (shell != null) {
                        TextButton(onClick = { show(shell.submit(com.labteto.dshmobile.uicontract.ConversationShellUiCommand.SetPinned(session.id, session.id !in pinnedSessions))) }) {
                            Text(stringResource(if (session.id in pinnedSessions) R.string.next_unpin_session else R.string.next_pin_session))
                        }
                        TextButton(onClick = { renamingSession = session.id; sessionTitle = titleOverrides[session.id] ?: session.title; sessionChooser = false }) {
                            Text(stringResource(R.string.next_rename_session))
                        }
                    }
                }
            } }, confirmButton = { TextButton(onClick = { sessionChooser = false }) { Text(stringResource(R.string.next_close)) } })
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.safeDrawingPadding().imePadding().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.next_title), style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.semantics { heading() })
                    Text(stringResource(R.string.next_scope), style = MaterialTheme.typography.bodySmall)
                    if (!workMode && chatSnapshot.group != null) {
                        Text(
                            stringResource(R.string.next_group_members,
                                chatSnapshot.group?.memberNames?.joinToString("、").orEmpty()),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!workMode && identity != null && groups != null) {
                        NextChatGroupManager(groups, identity, ::show)
                    }
                    if (!workMode && gallery != null) NextPersonaGalleryPanel(gallery, personas, personaTransfer, stories, galleryDeletion, gallerySave, portraits)
                    if (sessions != null && sessionSnapshot != null) {
                        TextButton(onClick = { sessionChooser = true }) { Text(stringResource(R.string.next_sessions)) }
                        TextButton(onClick = { show(sessions.submit(com.labteto.dshmobile.uicontract.SessionUiCommand.SelectMode(com.labteto.dshmobile.local.LocalUsageMode.CHAT))) }) {
                            Text(stringResource(R.string.next_chat_mode))
                        }
                        TextButton(onClick = { show(sessions.submit(com.labteto.dshmobile.uicontract.SessionUiCommand.SelectMode(com.labteto.dshmobile.local.LocalUsageMode.WORK))) }) {
                            Text(stringResource(R.string.next_work_mode))
                        }
                        TextButton(onClick = { show(sessions.submit(com.labteto.dshmobile.uicontract.SessionUiCommand.Create(
                            sessionSnapshot.identity, com.labteto.dshmobile.local.session.LocalConversationMode.INDEPENDENT, sessionSnapshot.usageMode))) },
                            enabled = !sessionSnapshot.loading) { Text(stringResource(R.string.next_new_session)) }
                        TextButton(onClick = {
                            val target = sessionSnapshot.identity ?: return@TextButton
                            val summary = sessions.handoffSummary(target)
                            if (summary == null) receipt = rejectionLabels[UiCommandRejection.STALE_IDENTITY]
                            else {
                                continuationSummary = summary
                                continuation = com.labteto.dshmobile.uicontract.SessionUiCommand.Create(target,
                                    com.labteto.dshmobile.local.session.LocalConversationMode.CONTINUATION,
                                    sessionSnapshot.usageMode, summary)
                            }
                        }, enabled = !sessionSnapshot.loading && sessionSnapshot.identity != null) {
                            Text(stringResource(R.string.next_continue_session))
                        }
                    }
                    if (workMode && identity != null && evidence != null) NextWorkEvidencePanel(evidence, identity)
                    if (workMode && identity != null && team != null) NextWorkTeamPanel(team, identity)
                    if (workMode && workReads != null) TextButton(onClick = { loadWorkFacts(false) }, enabled = identity != null && !workFactsLoading) {
                        Text(stringResource(R.string.next_work_facts))
                    }
                    if (files != null) {
                        TextButton(onClick = { loadFiles(false) }, enabled = !fileLoading) { Text(stringResource(R.string.next_workspace_files)) }
                        TextButton(onClick = { loadFiles(true) }, enabled = identity != null && !fileLoading) { Text(stringResource(R.string.next_conversation_files)) }
                    }
                    TextButton(onClick = onClose) { Text(stringResource(R.string.next_close)) }
                    }
                    Text(stringResource(when (phase) {
                        UiExecutionPhase.IDLE -> R.string.next_idle
                        UiExecutionPhase.RUNNING -> R.string.next_running
                        UiExecutionPhase.WAITING_FOR_USER -> R.string.next_waiting
                    }))
                    if (phase != UiExecutionPhase.IDLE) TextButton(onClick = {
                        identity?.let { target -> show(if (workMode) work.submit(WorkConversationUiCommand.Stop(target))
                            else chat.submit(ChatUiCommand.Stop(target))) }
                    }, enabled = identity != null) { Text(stringResource(R.string.next_stop)) }
                    if (policy != null) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (policy != null && policySnapshot?.approvalMode == com.labteto.dshmobile.local.interaction.LocalApprovalMode.AUTO) {
                        TextButton(onClick = { show(policy.submit(com.labteto.dshmobile.uicontract.WorkPolicyUiCommand.SetApprovalMode(identity,
                            com.labteto.dshmobile.local.interaction.LocalApprovalMode.MANUAL))) }) { Text(stringResource(R.string.next_disable_auto)) }
                    }
                    if (policy != null && policySnapshot != null && policySnapshot.approvalMode != com.labteto.dshmobile.local.interaction.LocalApprovalMode.DEFAULT) {
                        TextButton(onClick = { policyConsent = com.labteto.dshmobile.uicontract.WorkPolicyUiCommand.SetApprovalMode(identity,
                            com.labteto.dshmobile.local.interaction.LocalApprovalMode.DEFAULT) }) { Text(stringResource(R.string.next_default_policy)) }
                    }
                    if (workMode && policy != null && policySnapshot != null) {
                        TextButton(onClick = { identity?.let { show(policy.submit(com.labteto.dshmobile.uicontract.WorkPolicyUiCommand.SetPlanMode(it, !policySnapshot.planMode))) } },
                            enabled = identity != null && phase == UiExecutionPhase.IDLE) {
                            Text(stringResource(if (policySnapshot.planMode) R.string.next_disable_plan else R.string.next_enable_plan))
                        }
                        if (policySnapshot.deviceApprovalLease) TextButton(onClick = {
                            identity?.let { show(policy.submit(com.labteto.dshmobile.uicontract.WorkPolicyUiCommand.RevokeDeviceTurn(it))) }
                        }) { Text(stringResource(R.string.next_revoke_device)) }
                    }
                    }
                    if (queued > 0) Text(stringResource(R.string.next_queued, queued))
                    if (status == UiSceneStatus.LOADING) Text(stringResource(R.string.next_loading))
                    if (status == UiSceneStatus.EMPTY) Text(stringResource(R.string.next_empty))
                    if (documentSnapshot?.error != null) Text(stringResource(R.string.next_document_history_unavailable))
                    if (readingAnchorMissing) Text(stringResource(R.string.next_reading_anchor_unavailable))
                    LazyColumn(Modifier.weight(1f), state = listState, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (transcript != null && historyAvailable) item(key = "load_older") {
                            TextButton(onClick = {
                                val target = identity ?: return@TextButton
                                if (historyLoading) return@TextButton
                                historyLoading = true
                                importScope.launch {
                                    try {
                                        val page = transcript.transcriptPage(target.sessionId, historyCursor, 200)
                                        val current = if (workMode) work.snapshot().identity else chat.snapshot().identity
                                        if (current == target) {
                                            val rows = page.messages.map { row -> UiMessageSnapshot(row.id, when (row.role) {
                                                "user" -> UiMessageRole.USER; "assistant" -> UiMessageRole.ASSISTANT
                                                "tool" -> UiMessageRole.TOOL; else -> UiMessageRole.SYSTEM
                                            }, row.content, row.createdAt, proactive = row.proactive) }
                                            olderMessages = (rows + olderMessages).distinctBy { it.id }
                                            historyCursor = page.nextCursor
                                            historyAvailable = page.nextCursor != null
                                        }
                                    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                                    catch (failure: Exception) { receipt = failure.message }
                                    finally { historyLoading = false }
                                }
                            }, enabled = identity != null && !historyLoading && status != UiSceneStatus.LOADING) {
                                Text(stringResource(R.string.next_load_older))
                            }
                        }
                        items(messages, key = { "message:${it.id}" }) { message ->
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
                            if (policy != null) {
                                TextButton(onClick = { identity?.let { policyConsent = com.labteto.dshmobile.uicontract.WorkPolicyUiCommand.ApproveWithAutoMode(it, approval.pendingId) } }) {
                                    Text(stringResource(R.string.next_approve_auto))
                                }
                                if (approval.canApproveDeviceTurn) TextButton(onClick = {
                                    identity?.let { policyConsent = com.labteto.dshmobile.uicontract.WorkPolicyUiCommand.ApproveDeviceTurn(it, approval.pendingId) }
                                }) { Text(stringResource(R.string.next_approve_device)) }
                            }
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
                    if (selectedAttachments.isNotEmpty()) androidx.compose.foundation.lazy.LazyRow(Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(selectedAttachments, key = { it.relativePath }) { attachment ->
                            Column {
                        Text("${attachment.name} (${attachment.bytes} B)", maxLines = 2,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        if (files != null) TextButton(onClick = { previewFile(attachment.relativePath, standalone = true) }, enabled = !fileLoading) {
                            Text(stringResource(R.string.next_attachment_preview))
                        }
                        TextButton(onClick = { identity?.let { drafts.removeAttachment(it.sessionId, attachment.relativePath) } }) {
                            Text(stringResource(R.string.next_remove_attachment))
                        }
                    }
                        }
                    }
                    OutlinedTextField(value = draft, onValueChange = { text -> identity?.let { drafts.updateDraft(it.sessionId, text) } }, modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.next_input)) }, maxLines = 5)
                    if (workMode) TextButton(onClick = {
                        val target = identity ?: return@TextButton
                        val result = work.submit(WorkConversationUiCommand.SendWithTeam(target, draft, selectedAttachments))
                        show(result)
                        if (result == UiCommandReceipt.Accepted || result == UiCommandReceipt.Queued) {
                            drafts.updateDraft(target.sessionId, ""); drafts.clearAttachments(target.sessionId)
                        }
                    }, enabled = identity != null && send.available && (draft.isNotBlank() || selectedAttachments.isNotEmpty())) {
                        Text(stringResource(R.string.next_send_team))
                    }
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

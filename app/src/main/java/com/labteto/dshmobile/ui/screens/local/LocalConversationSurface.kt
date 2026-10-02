package com.labteto.dshmobile.ui.screens.local
import com.labteto.dshmobile.local.LocalChatUserEditResult
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.LocalConversationMode
import com.labteto.dshmobile.local.LocalHarnessMessage
import com.labteto.dshmobile.local.LocalModelProfile
import com.labteto.dshmobile.local.presentation.LocalConversationSurfaceState
import com.labteto.dshmobile.local.LocalHarnessStreamingState
import com.labteto.dshmobile.local.chatBranchInfo
import com.labteto.dshmobile.local.LocalImportedAttachment
import com.labteto.dshmobile.local.send.LocalSendFeedbackState
import com.labteto.dshmobile.local.send.LocalSendRejectReason
import com.labteto.dshmobile.local.send.LocalSendResult
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.chat.PersonaGalleryEntry
import com.labteto.dshmobile.local.chat.PersonaProfile
import com.labteto.dshmobile.local.chat.isUnboundChatPersona
import com.labteto.dshmobile.ui.components.DsBottomSheet
import com.labteto.dshmobile.ui.components.ConversationScrollShortcut
import com.labteto.dshmobile.ui.components.ConversationScrollTarget
import com.labteto.dshmobile.ui.components.rememberConversationScrollHint
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonSize
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.components.DsComposerAction
import com.labteto.dshmobile.ui.components.DsComposerField
import com.labteto.dshmobile.ui.components.DsComposerMetrics
import com.labteto.dshmobile.ui.components.DsConversationComposer
import com.labteto.dshmobile.ui.components.DsPopupMenu
import com.labteto.dshmobile.ui.components.DsCard
import com.labteto.dshmobile.ui.components.DsCategoryRow
import com.labteto.dshmobile.ui.components.DsGroupCard
import com.labteto.dshmobile.ui.components.DsIconButton
import com.labteto.dshmobile.ui.components.DsPill
import com.labteto.dshmobile.ui.components.DsQuickActionTile
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.components.MenuItem
import com.labteto.dshmobile.ui.screens.main.RenameDialog
import com.labteto.dshmobile.ui.theme.BackgroundRegion
import com.labteto.dshmobile.ui.theme.DsMetrics
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.withReadingWeight
import com.labteto.dshmobile.ui.theme.LocalAppBackgroundState
import com.labteto.dshmobile.ui.theme.WallpaperSurfaceLevel
import com.labteto.dshmobile.ui.theme.wallpaperSurface
import com.labteto.dshmobile.ui.theme.rootSurface
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.StateFlow
@Composable
internal fun LocalConversationSurface(
    state: LocalConversationSurfaceState,
    activeModelProfile: LocalModelProfile?,
    sendFeedback: LocalSendFeedbackState,
    streamingState: StateFlow<LocalHarnessStreamingState>,
    gallery: List<PersonaGalleryEntry>,
    transcriptHistory: LocalTranscriptHistoryState,
    modeIntro: LocalUsageMode?,
    onConfigure: () -> Unit,
    onSelectModel: (String) -> Unit,
    onSend: (String, List<LocalImportedAttachment>) -> LocalSendResult,
    onEditAndResend: suspend (String, String) -> LocalChatUserEditResult,
    onSelectMessageVariant: suspend (String, Int) -> Boolean,
    onRegenerate: (String) -> Boolean,
    onGenerateReplySuggestions: suspend () -> Boolean,
    onLoadOlderTranscript: suspend (String) -> Result<Int>,
    onImportAttachment: suspend (android.net.Uri) -> LocalImportedAttachment,
    onStop: () -> Unit,
    onNewSession: () -> Unit,
    onExitGroupChat: () -> Unit,
    onOpenRunCenter: () -> Unit,
    sessionTitle: String,
    sessionPinned: Boolean,
    onTogglePinSession: () -> Unit,
    onRenameSession: (String) -> Boolean,
    onDeleteSession: () -> Unit,
    onConfigureChatPersona: (PersonaProfile) -> Unit,
    onConfigureGroupMembers: (List<String>) -> Boolean,
    onSelectGalleryPersona: (String) -> Boolean,
    onAutoFillChatPersona: suspend (String) -> Result<PersonaProfile>,
    onSaveGroupAnnouncement: suspend (String) -> Result<Unit>,
    onGenerateGroupAnnouncement: suspend (String) -> Result<String>,
    onUndoPersonaCorrection: (Long, String, String) -> Unit,
    onPlanModeChange: (Boolean) -> Unit,
    onApprove: (String) -> Unit,
    onDeny: (String) -> Unit,
    onAutoApprove: () -> Unit,
    onAutoApprovePending: (String) -> Unit,
    onApproveDeviceTurn: (String) -> Unit,
    onDisableDeviceTurn: () -> Unit,
    onDisableAutoApprove: () -> Unit,
    onAnswerQuestion: (String, String) -> Unit,
    onCancelQuestion: (String) -> Unit,
) {
    val colors = DsTheme.colors
    val rootSurfaceColor = colors.rootSurface()
    // Custom wallpapers remain visible behind the chat toolbar; work mode still gets its
    // stable root work surface from rootSurfaceColor above.
    val topSurfaceColor = colors.rootSurface()
    val scope = rememberCoroutineScope()
    val drafts = rememberSaveable(
        saver = listSaver(
            save = { cache -> cache.save() },
            restore = { saved -> LocalSessionDraftCache.restore(saved) },
        ),
    ) { LocalSessionDraftCache() }
    val input = drafts[state.sessionId].orEmpty()
    var attachmentError by remember { mutableStateOf<String?>(null) }
    var showAttachmentPicker by rememberSaveable { mutableStateOf(false) }
    var approvalNoticeExpanded by rememberSaveable { mutableStateOf(false) }
    var showModelPicker by rememberSaveable { mutableStateOf(false) }
    var showPersonaPicker by rememberSaveable { mutableStateOf(false) }
    var showCharacterTuning by rememberSaveable(state.sessionId) { mutableStateOf(false) }
    var showGroupMemberPicker by rememberSaveable { mutableStateOf(false) }
    var showGroupAnnouncement by rememberSaveable { mutableStateOf(false) }
    var showPersonaEditor by rememberSaveable { mutableStateOf(false) }
    var personaEditorDraft by remember { mutableStateOf<PersonaProfile?>(null) }
    var showReplySuggestions by rememberSaveable { mutableStateOf(false) }
    var editingUserMessage by remember { mutableStateOf<LocalHarnessMessage?>(null) }
    var renameSessionOpen by rememberSaveable(state.sessionId) { mutableStateOf(false) }
    val attachments = remember(state.sessionId) { mutableStateListOf<LocalImportedAttachment>() }
    val listState = rememberLazyListState()
    val (scrollHint, scrollConnection) = rememberConversationScrollHint(listState, reverseLayout = false)
    var transcriptWindowSize by rememberSaveable(state.sessionId) {
        mutableStateOf(LOCAL_TRANSCRIPT_INITIAL_WINDOW_MESSAGES)
    }
    var planReviewBusy by remember(state.pendingQuestion?.callId) { mutableStateOf(false) }
    var previousTranscriptMessageCount by rememberSaveable(state.sessionId) {
        mutableStateOf(state.messages.size)
    }
    LaunchedEffect(state.messages.size) {
        val added = (state.messages.size - previousTranscriptMessageCount).coerceAtLeast(0)
        if (
            added > 0 &&
            transcriptHistory.sessionId == state.sessionId &&
            transcriptHistory.olderMessages.isNotEmpty()
        ) {
            transcriptWindowSize += added
        }
        previousTranscriptMessageCount = state.messages.size
    }
    val transcriptWindow = remember(state.messages, transcriptWindowSize) {
        localTranscriptWindow(state.messages, transcriptWindowSize)
    }
    val pagedOlderMessages = if (transcriptHistory.sessionId == state.sessionId) {
        transcriptHistory.olderMessages
    } else {
        emptyList()
    }
    val transcriptMessages = remember(pagedOlderMessages, transcriptWindow.messages) {
        mergeLocalTranscriptHistory(pagedOlderMessages, transcriptWindow.messages)
    }
    val transcriptItems = remember(transcriptMessages, state.usageMode) {
        buildLocalTranscript(
            transcriptMessages,
            includeWorkProcess = state.usageMode == LocalUsageMode.WORK,
        )
    }
    val hiddenTranscriptCount = (state.messages.size - transcriptMessages.size).coerceAtLeast(0)
    val hasOlderTranscript = transcriptHistory.sessionId == state.sessionId &&
        transcriptHistory.hasMore
    val loadingOlderTranscript = transcriptHistory.sessionId == state.sessionId &&
        transcriptHistory.loading
    val transcriptPrefixItemCount = if (hiddenTranscriptCount > 0 || hasOlderTranscript) 1 else 0
    val transcriptLastListIndex = transcriptPrefixItemCount + transcriptItems.lastIndex
    val messageEditingEnabled = true
    val messageBranchingEnabled = state.usageMode == LocalUsageMode.CHAT
    val messageActionsEnabled =
        state.configured &&
            !state.loading &&
            !state.running &&
            state.queuedInputCount == 0
    val currentGalleryEntry = remember(gallery, state.galleryId) {
        state.galleryId?.let { id -> gallery.firstOrNull { it.id == id } }
    }
    val currentGalleryStory = remember(currentGalleryEntry, state.galleryStoryId) {
        state.galleryStoryId?.let { id ->
            currentGalleryEntry?.stories?.firstOrNull { it.id == id }
        }
    }
    LaunchedEffect(
        state.sessionId,
        state.usageMode,
        state.groupChat.enabled,
        state.groupChat.members.size,
    ) {
        if (
            state.usageMode == LocalUsageMode.CHAT &&
            state.groupChat.enabled &&
            state.groupChat.members.size < 2
        ) {
            showGroupMemberPicker = true
        }
    }
    LaunchedEffect(state.sessionId, state.usageMode) {
        showReplySuggestions = false
        showPersonaPicker = false
        if (!state.groupChat.enabled) showGroupMemberPicker = false
        editingUserMessage = null
    }

    val imageLimitMessage = stringResource(R.string.local_image_selection_limit, MAX_LOCAL_IMAGE_SELECTION)
    val imageImportFailedMessage = stringResource(R.string.local_image_import_failed)
    val fileImportFailedMessage = stringResource(R.string.local_file_import_failed)
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch {
                val existingImageCount = attachments.count { it.mediaType.startsWith("image/") }
                val available = (MAX_LOCAL_IMAGE_SELECTION - existingImageCount).coerceAtLeast(0)
                if (available == 0) {
                    attachmentError = imageLimitMessage
                    return@launch
                }
                var failure: String? = if (uris.size > available) imageLimitMessage else null
                uris.take(available).forEach { uri ->
                    try {
                        val imported = onImportAttachment(uri)
                        val duplicate = imported.attachmentId != null &&
                            attachments.any { it.attachmentId == imported.attachmentId }
                        if (!duplicate) attachments += imported
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        failure = error.message ?: imageImportFailedMessage
                    }
                }
                attachmentError = failure
            }
        }
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    attachments += onImportAttachment(uri)
                    attachmentError = null
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    attachmentError = error.message ?: fileImportFailedMessage
                }
            }
        }
    }

    LaunchedEffect(state.sessionId) {
        scrollHint.hide()
        attachments.clear()
        attachmentError = null
        showReplySuggestions = false
        if (transcriptItems.isNotEmpty()) {
            listState.scrollToItem(transcriptLastListIndex)
        }
    }

    LaunchedEffect(state.messages.size, transcriptItems.size) {
        if (transcriptItems.isNotEmpty()) {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            if (lastVisible >= transcriptLastListIndex - 2) {
                listState.animateScrollToItem(transcriptLastListIndex)
            }
        }
    }

    Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().background(rootSurfaceColor)) {
        Column(
            Modifier.fillMaxWidth().background(topSurfaceColor)
                .padding(horizontal = DsMetrics.screenHorizontal, vertical = DsSpacing.small),
        ) {
            if (state.usageMode == LocalUsageMode.CHAT) {
                val hasSelectedPersona = !state.chatPersona.isUnboundChatPersona()
                val personaDisplayName = if (hasSelectedPersona) {
                    state.chatPersona.name
                } else {
                    stringResource(R.string.local_chat_no_persona)
                }
                val relationship = state.chatState.relationshipState.takeIf {
                    hasSelectedPersona && it.isNotBlank()
                }
                val storyTitle = currentGalleryStory?.title?.takeIf { it.isNotBlank() }
                val contextSource = when (state.conversationMode) {
                    LocalConversationMode.INDEPENDENT -> null
                    else -> localConversationModeLabel(state.conversationMode)
                }
                ChatSurfaceHeader(
                    personaName = personaDisplayName,
                    portraitPath = currentGalleryEntry?.portraitPath.orEmpty(),
                    secondary = listOfNotNull(storyTitle, relationship, contextSource).joinToString(" · "),
                    groupEnabled = state.groupChat.enabled,
                    groupMembers = state.groupChat.members,
                    activeSpeakerName = state.groupActiveSpeakerName,
                    running = state.running || state.loading,
                    behaviorTuningCustomized = !state.chatState.behaviorTuning.isNatural(),
                    onContextClick = {
                        if (state.groupChat.enabled) showGroupMemberPicker = true
                        else showPersonaPicker = true
                    },
                    onOpenCharacterTuning = { showCharacterTuning = true },
                    onExitGroupChat = onExitGroupChat,
                    onNewSession = onNewSession,
                    sessionPinned = sessionPinned,
                    onTogglePin = onTogglePinSession,
                    onRenameSession = { renameSessionOpen = true },
                    onDeleteSession = onDeleteSession,
                )
            } else {
                WorkSurfaceHeader(
                    sessionTitle = sessionTitle.takeIf(String::isNotBlank)
                        ?: stringResource(R.string.local_usage_work),
                    modelLabel = state.model,
                    configured = state.configured,
                    running = state.running,
                    onModelClick = {
                        if (state.configured) showModelPicker = true else onConfigure()
                    },
                    onOpenRunCenter = onOpenRunCenter,
                    onNewSession = onNewSession,
                    sessionPinned = sessionPinned,
                    onTogglePin = onTogglePinSession,
                    onRenameSession = { renameSessionOpen = true },
                    onDeleteSession = onDeleteSession,
                )
            }
        }

        modeIntro?.let { mode ->
            Surface(
                color = colors.wallpaperSurface(WallpaperSurfaceLevel.CARD),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = DsSpacing.medium, vertical = DsSpacing.tiny),
            ) {
                Text(
                    stringResource(
                        if (mode == LocalUsageMode.CHAT) R.string.local_mode_intro_chat
                        else R.string.local_mode_intro_work,
                    ),
                    style = DsType.small13.withReadingWeight(),
                    color = colors.labelSecondary,
                    modifier = Modifier.padding(horizontal = DsSpacing.medium, vertical = DsSpacing.small),
                )
            }
        }

        if (state.usageMode == LocalUsageMode.CHAT && state.groupChat.enabled) {
            GroupAnnouncementCard(
                announcement = state.groupChat.announcement,
                onClick = { showGroupAnnouncement = true },
            )
        }

        state.personaCorrectionNotice?.takeIf {
            state.usageMode == LocalUsageMode.CHAT && !state.groupChat.enabled
        }?.let { notice ->
            Surface(
                color = colors.wallpaperSurface(WallpaperSurfaceLevel.CARD),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = DsSpacing.medium, vertical = DsSpacing.tiny),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = DsSpacing.medium, vertical = DsSpacing.small),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
                ) {
                    Text(
                        stringResource(R.string.local_persona_correction_recorded),
                        style = DsType.small13.withReadingWeight(),
                        color = colors.labelSecondary,
                        modifier = Modifier.weight(1f),
                    )
                    DsButton(
                        text = stringResource(R.string.local_persona_correction_undo),
                        onClick = {
                            onUndoPersonaCorrection(notice.id, notice.personaId, notice.correction)
                        },
                        variant = DsButtonVariant.Ghost,
                        size = DsButtonSize.Small,
                    )
                }
            }
        }

        if (state.usageMode == LocalUsageMode.WORK && state.deviceApprovalLease) {
            Surface(
                color = colors.warnTertiary,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
                    .padding(horizontal = DsSpacing.medium, vertical = DsSpacing.tiny),
            ) {
                Row(
                    Modifier.padding(horizontal = DsSpacing.medium, vertical = DsSpacing.small),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
                ) {
                    Column(
                        Modifier.weight(1f)
                            .heightIn(min = DsSpacing.touchTarget)
                            .clickable { approvalNoticeExpanded = !approvalNoticeExpanded },
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            stringResource(R.string.local_device_approval_on),
                            style = DsType.small13Strong.withReadingWeight(),
                            color = colors.warnLabel,
                        )
                        Text(
                            if (approvalNoticeExpanded) stringResource(R.string.local_approval_scope_hide)
                            else stringResource(R.string.local_approval_scope_show),
                            style = DsType.caption11.withReadingWeight(),
                            color = colors.labelSecondary,
                        )
                    }
                    DsButton(
                        stringResource(R.string.common_close),
                        onDisableDeviceTurn,
                        modifier = Modifier.heightIn(min = DsSpacing.touchTarget),
                        variant = DsButtonVariant.Ghost,
                    )
                }
                if (approvalNoticeExpanded) {
                    Text(
                        stringResource(R.string.local_device_approval_scope),
                        style = DsType.caption11.withReadingWeight(),
                        color = colors.labelSecondary,
                    )
                }
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth().nestedScroll(scrollConnection)) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = DsSpacing.medium,
                    end = DsSpacing.medium,
                    top = DsSpacing.comfortable,
                    bottom = DsSpacing.xlarge,
                ),
                verticalArrangement = Arrangement.spacedBy(DsSpacing.comfortable),
            ) {
                if (hiddenTranscriptCount > 0 || hasOlderTranscript) {
                    item(key = "local-transcript-load-older") {
                        DsButton(
                            text = when {
                                loadingOlderTranscript -> stringResource(R.string.tools_processing)
                                hiddenTranscriptCount > 0 -> stringResource(
                                    R.string.local_transcript_load_older,
                                    hiddenTranscriptCount,
                                )
                                else -> stringResource(R.string.local_transcript_load_older_unknown)
                            },
                            onClick = {
                                if (!loadingOlderTranscript) {
                                    scope.launch {
                                        onLoadOlderTranscript(state.sessionId)
                                    }
                                }
                            },
                            enabled = !loadingOlderTranscript,
                            modifier = Modifier.fillMaxWidth(),
                            variant = DsButtonVariant.Ghost,
                        )
                    }
                }
                if (transcriptItems.isEmpty() && state.usageMode == LocalUsageMode.WORK) {
                    item {
                        Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                            EmptyLocalHarness { suggestion ->
                                drafts.putBoundedLocalDraft(state.sessionId, suggestion)
                            }
                        }
                    }
                }
                if (
                    transcriptItems.isEmpty() &&
                    state.usageMode == LocalUsageMode.CHAT &&
                    state.groupChat.enabled &&
                    state.groupChat.members.size < 2
                ) {
                    item {
                        DsCard {
                            Text(
                                stringResource(R.string.local_group_chat_setup_required),
                                style = DsType.std14.withReadingWeight(),
                                color = colors.labelSecondary,
                            )
                            DsButton(
                                text = stringResource(R.string.local_group_chat_manage_members),
                                onClick = { showGroupMemberPicker = true },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
                val planReview = state.pendingQuestion
                    ?.takeIf { state.usageMode == LocalUsageMode.WORK }
                    ?.let(::localPlanReviewOf)
                planReview?.let { review ->
                    item(key = "pending-plan-review") {
                        LocalPlanReviewCard(
                            review = review,
                            busy = planReviewBusy,
                            onApprove = {
                                if (!planReviewBusy) {
                                    planReviewBusy = true
                                    onAnswerQuestion(state.pendingQuestion?.callId.orEmpty(), review.approve)
                                }
                            },
                            onDecline = {
                                review.decline?.let { decline ->
                                    if (!planReviewBusy) {
                                        planReviewBusy = true
                                        onAnswerQuestion(state.pendingQuestion?.callId.orEmpty(), decline)
                                    }
                                }
                            },
                            onRegenerate = {
                                val prompt = state.messages.lastOrNull { it.role == "user" }?.content
                                    ?.takeIf(String::isNotBlank)
                                val callId = state.pendingQuestion?.callId
                                if (!planReviewBusy && prompt != null && callId != null) {
                                    planReviewBusy = true
                                    onCancelQuestion(callId)
                                    onSend(prompt, emptyList())
                                }
                            },
                        )
                    }
                }
                items(transcriptItems, key = { it.key }) { transcriptItem ->
                    when (transcriptItem) {
                        is LocalTranscriptItem.Message -> LocalMessageRow(
                            message = transcriptItem.message,
                            chatMode = state.usageMode == LocalUsageMode.CHAT,
                            groupMode = state.groupChat.enabled,
                            canEdit = messageEditingEnabled &&
                                messageActionsEnabled &&
                                transcriptItem.message.role == "user",
                            canRegenerate = !state.groupChat.enabled &&
                                messageActionsEnabled &&
                                !transcriptItem.message.proactive &&
                                state.messages.lastOrNull()?.id == transcriptItem.message.id,
                            canSelectVariant = messageBranchingEnabled &&
                                messageActionsEnabled &&
                                !state.groupChat.enabled,
                            branchInfo = if (messageBranchingEnabled && !state.groupChat.enabled) {
                                chatBranchInfo(state.chatBranches, transcriptItem.message.id)
                            } else {
                                null
                            },
                            onEdit = { message -> editingUserMessage = message },
                            onSelectVariant = onSelectMessageVariant,
                            onRegenerate = onRegenerate,
                        )
                        is LocalTranscriptItem.Thinking -> ChatThinkingRow(transcriptItem.messages)
                        is LocalTranscriptItem.WorkProcess -> WorkProcessRow(transcriptItem.messages, state.running && state.usageMode == LocalUsageMode.WORK && transcriptItem.key == (transcriptItems.lastOrNull() as? LocalTranscriptItem.WorkProcess)?.key)
                    }
                }
                if (state.usageMode == LocalUsageMode.CHAT && state.running) {
                    item(key = "streaming:${state.sessionId}") {
                        LocalStreamingChatTurn(
                            sessionId = state.sessionId,
                            streamingState = streamingState,
                        )
                    }
                }
            }

            ConversationScrollShortcut(
                target = scrollHint.target,
                modifier = Modifier.align(Alignment.BottomEnd)
                    .padding(end = DsSpacing.medium, bottom = DsSpacing.small),
                onClick = { target ->
                    scrollHint.hide()
                    scope.launch {
                        listState.animateScrollToItem(
                            if (target == ConversationScrollTarget.START) 0
                            else listState.layoutInfo.totalItemsCount.dec().coerceAtLeast(0),
                        )
                    }
                },
            )
        }

        if (state.usageMode == LocalUsageMode.WORK && state.running) {
            LocalStreamingWorkPreview(
                sessionId = state.sessionId,
                streamingState = streamingState,
                surfaceColor = colors.wallpaperSurface(WallpaperSurfaceLevel.CARD),
                hasDurableProgress = transcriptItems.lastOrNull() is LocalTranscriptItem.WorkProcess,
            )
        }

        val sendRejectMessage = sendFeedback.rejectReason
            ?.takeIf { sendFeedback.sessionId == state.sessionId }
            ?.let { reason -> localSendRejectMessage(reason, sendFeedback.rejectLimit) }
        LocalConversationErrorCard(
            sendRejectMessage = sendRejectMessage,
            stateError = state.error,
            restoreRequest = state.messages.lastOrNull { it.role == "user" }?.content,
            onRestoreRequest = { drafts.putBoundedLocalDraft(state.sessionId, it) },
            onSwitchModelSource = {
                if (state.modelProfiles.isNotEmpty()) showModelPicker = true else onConfigure()
            },
        )
        attachmentError?.let {
            Text(
                it,
                style = DsType.small13.withReadingWeight(),
                color = colors.error,
                modifier = Modifier.fillMaxWidth().padding(horizontal = DsSpacing.medium),
            )
        }

        if (
            state.usageMode == LocalUsageMode.CHAT &&
            !state.groupChat.enabled &&
            !state.running &&
            state.replySuggestions.any { it.text.isNotBlank() }
        ) {
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = DsSpacing.medium),
                horizontalArrangement = Arrangement.spacedBy(DsSpacing.xsmall),
            ) {
                items(
                    items = state.replySuggestions.filter { it.text.isNotBlank() }.take(3),
                    key = { suggestion -> suggestion.label + "|" + suggestion.text },
                ) { suggestion ->
                    Surface(
                        onClick = { drafts.putBoundedLocalDraft(state.sessionId, suggestion.text) },
                        shape = DsShapes.pillFull,
                        color = colors.wallpaperSurface(
                            WallpaperSurfaceLevel.FLOATING,
                            BackgroundRegion.BOTTOM,
                        ),
                        border = BorderStroke(1.dp, colors.borderL1),
                    ) {
                        Text(
                            suggestion.label.ifBlank {
                                suggestion.text.take(18)
                            },
                            style = DsType.small13.withReadingWeight(),
                            color = colors.labelSecondary,
                            maxLines = 1,
                            modifier = Modifier.padding(
                                horizontal = DsSpacing.medium,
                                vertical = DsSpacing.xsmall,
                            ),
                        )
                    }
                }
            }
        }

        LocalConversationComposer(
            state = state,
            activeModelProfile = activeModelProfile,
            input = input,
            attachments = attachments,
            onInputChange = { drafts.putBoundedLocalDraft(state.sessionId, it) },
            onRemoveAttachment = { index -> attachments.removeAt(index) },
            onClearAttachments = attachments::clear,
            onOpenAttachmentPicker = { showAttachmentPicker = true },
            onShowReplySuggestions = { showReplySuggestions = true },
            onGenerateReplySuggestions = onGenerateReplySuggestions,
            onConfigure = onConfigure,
            onSend = onSend,
            onStop = onStop,
            onPlanModeChange = onPlanModeChange,
            onAutoApprove = onAutoApprove,
            onDisableAutoApprove = onDisableAutoApprove,
        )

    }

    state.pendingApproval
        ?.takeIf { state.usageMode == LocalUsageMode.WORK }
        ?.let { approval ->
        ApprovalDialog(
            approval = approval,
            safeAutoApprovalEnabled = state.safeAutoApprovalEnabled,
            onApprove = { onApprove(approval.callId) },
            onDeny = { onDeny(approval.callId) },
            onAutoApprove = { onAutoApprovePending(approval.callId) },
            onApproveDeviceTurn = { onApproveDeviceTurn(approval.callId) },
        )
    }
    state.pendingQuestion
        ?.takeIf { state.usageMode == LocalUsageMode.WORK }
        ?.takeUnless { localPlanReviewOf(it) != null }
        ?.let { question ->
        QuestionDialog(
            question = question.question,
            options = question.options,
            onAnswer = { answer -> onAnswerQuestion(question.callId, answer) },
            onDismiss = { onCancelQuestion(question.callId) },
        )
    }
    if (
        showGroupMemberPicker &&
        state.usageMode == LocalUsageMode.CHAT &&
        state.groupChat.enabled
    ) {
        GroupChatMemberPickerSheet(
            entries = gallery,
            currentIds = state.groupChat.members.map { it.galleryId },
            enabled = !state.running,
            onSave = onConfigureGroupMembers,
            onDismiss = { showGroupMemberPicker = false },
        )
    }
    if (showGroupAnnouncement && state.usageMode == LocalUsageMode.CHAT && state.groupChat.enabled) {
        GroupAnnouncementSheet(
            sessionId = state.sessionId,
            announcement = state.groupChat.announcement,
            enabled = !state.running,
            onSave = onSaveGroupAnnouncement,
            onGenerate = onGenerateGroupAnnouncement,
            onDismiss = { showGroupAnnouncement = false },
        )
    }
    CharacterBehaviorTuningDialogHost(
        showCharacterTuning && state.usageMode == LocalUsageMode.CHAT && !state.groupChat.enabled,
        state.chatPersona, currentGalleryEntry?.portraitPath.orEmpty(), state.chatState,
        onConfigureChatPersona,
    ) { showCharacterTuning = false }
    if (
        showPersonaPicker &&
        state.usageMode == LocalUsageMode.CHAT &&
        !state.groupChat.enabled
    ) {
        ChatPersonaPickerDialog(
            entries = gallery,
            currentPersona = state.chatPersona,
            currentGalleryId = state.galleryId,
            canSwitchPersona = !state.running &&
                state.messages.none { it.role == "user" || it.role == "assistant" },
            onSelect = onSelectGalleryPersona,
            onEditCurrent = {
                showPersonaPicker = false
                personaEditorDraft = state.chatPersona
                showPersonaEditor = true
            },
            onDismiss = { showPersonaPicker = false },
        )
    }
    if (showPersonaEditor) {
        ChatPersonaDialog(
            profile = personaEditorDraft ?: state.chatPersona,
            onSave = { profile ->
                onConfigureChatPersona(profile)
                personaEditorDraft = null
                Result.success(Unit)
            },
            onAutoFill = onAutoFillChatPersona,
            onDismiss = {
                showPersonaEditor = false
                personaEditorDraft = null
            },
        )
    }
    editingUserMessage?.let { message ->
        LocalChatEditMessageSheet(
            message = message,
            actionsEnabled = messageActionsEnabled,
            onEditAndResend = onEditAndResend,
            onDismiss = { editingUserMessage = null },
        )
    }

    if (renameSessionOpen) {
        RenameDialog(
            initial = sessionTitle,
            title = stringResource(R.string.chatlist_session_rename),
            onDismiss = { renameSessionOpen = false },
            onConfirm = { title ->
                if (onRenameSession(title)) renameSessionOpen = false
            },
        )
    }

    if (
        showReplySuggestions &&
        state.usageMode == LocalUsageMode.CHAT &&
        !state.groupChat.enabled &&
        state.replySuggestions.any { it.text.isNotBlank() }
    ) {
        DsBottomSheet(
            title = stringResource(R.string.local_reply_suggestions_title),
            onDismiss = { showReplySuggestions = false },
        ) {
            Text(
                stringResource(R.string.local_reply_suggestions_hint),
                style = DsType.small13.withReadingWeight(),
                color = colors.labelSecondary,
            )
            state.replySuggestions.filter { it.text.isNotBlank() }.forEach { suggestion ->
                Surface(
                    onClick = {
                        drafts.putBoundedLocalDraft(state.sessionId, suggestion.text)
                        showReplySuggestions = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.running,
                    shape = DsShapes.row,
                    color = colors.wallpaperSurface(WallpaperSurfaceLevel.CARD),
                    border = BorderStroke(1.dp, colors.borderL2),
                ) {
                    Column(
                        Modifier.heightIn(min = DsSpacing.touchTarget)
                            .padding(horizontal = DsSpacing.medium, vertical = DsSpacing.small),
                        verticalArrangement = Arrangement.spacedBy(DsSpacing.tiny),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                suggestion.label,
                                style = DsType.std14Strong.withReadingWeight(),
                                color = colors.labelPrimary,
                                modifier = Modifier.weight(1f),
                            )
                            if (suggestion.style.isNotBlank() || suggestion.bold) {
                                Text(
                                    if (suggestion.bold) stringResource(R.string.local_reply_suggestions_bold)
                                    else suggestion.style,
                                    style = DsType.caption11.withReadingWeight(),
                                    color = if (suggestion.bold) colors.warnLabel else colors.labelTertiary,
                                )
                            }
                        }
                        Text(
                            suggestion.text,
                            style = DsType.small13.withReadingWeight(),
                            color = colors.labelSecondary,
                        )
                    }
                }
            }
        }
    }
    if (showModelPicker) {
        LocalModelPickerSheet(
            profiles = state.modelProfiles,
            activeProfileId = activeModelProfile?.id,
            onSelect = onSelectModel,
            onConfigure = onConfigure,
            onDismiss = { showModelPicker = false },
        )
    }
    if (showAttachmentPicker) {
        DsBottomSheet(title = stringResource(R.string.chat_composer_add_attachment), onDismiss = { showAttachmentPicker = false }) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(DsSpacing.medium),
            ) {
                DsQuickActionTile(
                    icon = Icons.Outlined.Image,
                    label = stringResource(R.string.local_attachment_image),
                    onClick = {
                        showAttachmentPicker = false
                        imagePicker.launch(arrayOf("image/*"))
                    },
                    modifier = Modifier.weight(1f),
                )
                DsQuickActionTile(
                    icon = Icons.Outlined.AttachFile,
                    label = stringResource(R.string.local_attachment_file),
                    onClick = {
                        showAttachmentPicker = false
                        filePicker.launch(arrayOf("*/*"))
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun EmptyLocalHarness(onSuggestion: (String) -> Unit) {
    val colors = DsTheme.colors
    val filesPrompt = stringResource(R.string.local_prompt_files)
    val researchPrompt = stringResource(R.string.local_prompt_research)
    val tasksPrompt = stringResource(R.string.local_prompt_tasks)
    Column(
        Modifier.fillMaxWidth().padding(horizontal = DsSpacing.small, vertical = DsSpacing.xlarge),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(DsSpacing.medium),
    ) {
        Text(stringResource(R.string.local_welcome_title), style = DsType.display24.withReadingWeight(), color = colors.labelPrimary)
        Text(
            stringResource(R.string.local_welcome_hint),
            style = DsType.std14.withReadingWeight(),
            color = colors.labelSecondary,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Text(stringResource(R.string.local_suggestions_title), style = DsType.small13Strong.withReadingWeight(), color = colors.labelTertiary)
        DsGroupCard {
            DsCategoryRow(
                icon = FeatherIcons.FileText,
                title = stringResource(R.string.local_suggestion_files),
                subtitle = stringResource(R.string.local_suggestion_files_hint),
                onClick = { onSuggestion(filesPrompt) },
            )
            DsCategoryRow(
                icon = FeatherIcons.Globe,
                title = stringResource(R.string.local_suggestion_research),
                subtitle = stringResource(R.string.local_suggestion_research_hint),
                onClick = { onSuggestion(researchPrompt) },
            )
            DsCategoryRow(
                icon = FeatherIcons.CheckSquare,
                title = stringResource(R.string.local_suggestion_tasks),
                subtitle = stringResource(R.string.local_suggestion_tasks_hint),
                onClick = { onSuggestion(tasksPrompt) },
            )
        }
    }
}

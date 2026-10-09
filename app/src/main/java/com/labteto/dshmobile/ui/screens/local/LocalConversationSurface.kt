package com.labteto.dshmobile.ui.screens.local

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.attachment.LocalImportedAttachment
import com.labteto.dshmobile.local.chat.LocalChatUserEditResult
import com.labteto.dshmobile.local.chat.PersonaGalleryEntry
import com.labteto.dshmobile.local.chat.PersonaProfile
import com.labteto.dshmobile.local.presentation.chatBranchInfo
import com.labteto.dshmobile.local.presentation.isUnboundChatPersona
import com.labteto.dshmobile.local.model.LocalHarnessStreamingState
import com.labteto.dshmobile.local.model.LocalModelProfile
import com.labteto.dshmobile.local.model.LocalModelPresets
import com.labteto.dshmobile.local.presentation.LocalWorkTemperatureControls
import com.labteto.dshmobile.local.presentation.LocalReasoningUiMode
import com.labteto.dshmobile.local.presentation.LocalReasoningControls
import com.labteto.dshmobile.local.presentation.LocalConversationSurfaceState
import com.labteto.dshmobile.local.send.LocalSendFeedbackState
import com.labteto.dshmobile.local.send.LocalSendRejectReason
import com.labteto.dshmobile.local.send.LocalSendResult
import com.labteto.dshmobile.local.session.LocalConversationMode
import com.labteto.dshmobile.local.session.LocalHarnessMessage
import com.labteto.dshmobile.ui.components.ConversationScrollShortcut
import com.labteto.dshmobile.ui.components.ConversationScrollTarget
import com.labteto.dshmobile.ui.components.DsBottomSheet
import com.labteto.dshmobile.ui.components.MenuItem
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonSize
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.components.DsCard
import com.labteto.dshmobile.ui.components.DsCategoryRow
import com.labteto.dshmobile.ui.components.DsComposerAction
import com.labteto.dshmobile.ui.components.DsComposerField
import com.labteto.dshmobile.ui.components.DsComposerMetrics
import com.labteto.dshmobile.ui.components.DsConversationComposer
import com.labteto.dshmobile.ui.components.DsGroupCard
import com.labteto.dshmobile.ui.components.DsIconButton
import com.labteto.dshmobile.ui.components.DsPill
import com.labteto.dshmobile.ui.components.DsPopupMenu
import com.labteto.dshmobile.ui.components.DsSheetChoiceRow
import com.labteto.dshmobile.ui.components.DsQuickActionTile
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.components.rememberConversationScrollHint
import com.labteto.dshmobile.ui.screens.main.RenameDialog
import com.labteto.dshmobile.ui.theme.BackgroundRegion
import com.labteto.dshmobile.ui.theme.DsMetrics
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.LocalAppBackgroundState
import com.labteto.dshmobile.ui.theme.WallpaperSurfaceLevel
import com.labteto.dshmobile.ui.theme.rootSurface
import com.labteto.dshmobile.ui.theme.wallpaperSurface
import com.labteto.dshmobile.ui.theme.withReadingWeight
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Optional introduction is wrapped so Compose never tracks a nullable enum via ordinal(). */
internal data class LocalModeIntro(val usageMode: LocalUsageMode)

@Composable
internal fun LocalConversationSurface(
    state: LocalConversationSurfaceState,
    activeModelProfile: LocalModelProfile?,
    sendFeedback: LocalSendFeedbackState,
    streamingState: StateFlow<LocalHarnessStreamingState>,
    gallery: List<PersonaGalleryEntry>,
    transcriptHistory: LocalTranscriptHistoryState,
    modeIntro: LocalModeIntro?,
    onConfigure: () -> Unit,
    onSelectModel: (String) -> Unit,
    onSend: (String, List<LocalImportedAttachment>) -> LocalSendResult,
    onSendTeam: (String, List<LocalImportedAttachment>) -> LocalSendResult,
    onTeamMemberOutput: (String) -> String,
    onSendTeamMemberMessage: suspend (String, String) -> LocalWorkUiActionResult,
    onStopTeamMember: suspend (String) -> LocalWorkUiActionResult,
    onStopTeam: suspend () -> LocalWorkUiActionResult,
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
    onConfigureChatPersona: suspend (PersonaProfile) -> Result<Unit>,
    onConfigureGroupMembers: (List<String>) -> Boolean,
    onSelectGalleryPersona: (String) -> Boolean,
    onAutoFillChatPersona: suspend (String) -> Result<PersonaProfile>,
    onSaveGroupAnnouncement: suspend (String) -> Result<Unit>,
    onGenerateGroupAnnouncement: suspend (String) -> Result<String>,
    onUndoPersonaCorrection: (Long, String, String) -> Unit,
    onPlanModeChange: (Boolean) -> Unit,
    onApprove: (String) -> Unit,
    onDeny: (String) -> Unit,
    onAutoApprovePending: (String) -> Unit,
    onApproveDeviceTurn: (String) -> Unit,
    onDisableDeviceTurn: () -> Unit,
    onAnswerQuestion: (String, String) -> Unit,
    onCancelQuestion: (String) -> Unit,
    composerHandoff: List<String> = emptyList(),
    skillDisplayNames: Map<String, String> = emptyMap(),
    onConsumeComposerHandoff: () -> Unit = {},
    onOpenDrawer: (() -> Unit)? = null,
    onUseWorkCapability: ((String) -> Unit)? = null,
) {
    val colors = DsTheme.colors
    val rootSurfaceColor = colors.rootSurface()
    // Custom wallpapers remain visible behind the chat toolbar; work mode still gets its
    // stable root work surface from rootSurfaceColor above.
    val topSurfaceColor = colors.rootSurface()
    val scope = rememberCoroutineScope()
    val appContext = LocalContext.current
    var reasoningMode by remember(state.sessionId, state.usageMode) {
        LocalReasoningControls.attach(appContext)
        mutableStateOf(LocalReasoningControls.mode(state.sessionId, state.usageMode))
    }
    val workTemperatureRange = activeModelProfile?.let {
        LocalModelPresets.chatTemperatureRangeFor(it.model, it.baseUrl)
    }
    var workTemperatureLevel by remember(state.sessionId, activeModelProfile?.id, workTemperatureRange) {
        LocalWorkTemperatureControls.attach(appContext)
        androidx.compose.runtime.mutableIntStateOf(
            LocalWorkTemperatureControls.level(state.sessionId, workTemperatureRange),
        )
    }
    var temperatureSaving by remember(state.sessionId) { mutableStateOf(false) }
    var temperatureSaveFailed by remember(state.sessionId) { mutableStateOf(false) }
    val drafts = rememberSaveable(
        saver = listSaver(
            save = { cache -> cache.save() },
            restore = { saved -> LocalSessionDraftCache.restore(saved) },
        ),
    ) { LocalSessionDraftCache() }
    LaunchedEffect(composerHandoff, state.sessionId) {
        if (composerHandoff.size == 2 && composerHandoff[0] == state.sessionId) {
            val previous = drafts[state.sessionId].orEmpty()
            drafts.putBoundedLocalDraft(state.sessionId, listOf(composerHandoff[1], previous).filter(String::isNotBlank).joinToString("\n\n"))
            onConsumeComposerHandoff()
        }
    }
    val input = drafts[state.sessionId].orEmpty()
    var attachmentError by remember { mutableStateOf<String?>(null) }
    var showAttachmentPicker by rememberSaveable { mutableStateOf(false) }
    var teamDispatchSelected by rememberSaveable(state.sessionId) { mutableStateOf(false) }
    var teamLaunchPending by rememberSaveable(state.sessionId) { mutableStateOf(false) }
    var teamLaunchSawRunning by rememberSaveable(state.sessionId) { mutableStateOf(false) }
    var showTeamPanel by rememberSaveable(state.sessionId) { mutableStateOf(false) }
    var teamCardDismissed by rememberSaveable(state.sessionId) { mutableStateOf(false) }
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
    var composerTailViewportAnchor by remember(state.sessionId) { mutableStateOf<Int?>(null) }
    val (scrollHint, scrollConnection) = rememberConversationScrollHint(listState, reverseLayout = false)
    var transcriptInitialPositionReady by rememberSaveable(state.sessionId) { mutableStateOf(false) }
    var planReviewBusy by remember(state.pendingQuestion?.callId) { mutableStateOf(false) }
    val pagedOlderMessages = if (transcriptHistory.sessionId == state.sessionId) {
        transcriptHistory.olderMessages
    } else {
        emptyList()
    }
    val transcriptMessages = remember(pagedOlderMessages, state.messages) {
        mergeLocalTranscriptHistory(pagedOlderMessages, state.messages)
    }
    val transcriptItems = remember(transcriptMessages, state.usageMode) {
        buildLocalTranscript(
            transcriptMessages,
            includeWorkProcess = state.usageMode == LocalUsageMode.WORK,
        )
    }
    val hasOlderTranscript = transcriptHistory.sessionId == state.sessionId &&
        transcriptHistory.hasMore
    val loadingOlderTranscript = transcriptHistory.sessionId == state.sessionId &&
        transcriptHistory.loading
    val transcriptHistoryError = transcriptHistory.error
        ?.takeIf { transcriptHistory.sessionId == state.sessionId }
    val showTranscriptPagingRow = transcriptHistoryError != null ||
        (hasOlderTranscript && loadingOlderTranscript)
    val transcriptPrefixItemCount = if (showTranscriptPagingRow) 1 else 0
    val showStreamingTail = shouldShowWorkStreamingTail(
        running = state.running,
        usageMode = state.usageMode,
        lastItem = transcriptItems.lastOrNull(),
    )
    val transcriptLastListIndex = transcriptPrefixItemCount + transcriptItems.lastIndex +
        (if (showStreamingTail) 1 else 0)
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
        teamDispatchSelected = false
        teamLaunchPending = false
        teamLaunchSawRunning = false
        showTeamPanel = false
    }

    LaunchedEffect(state.running, state.team.visible, teamLaunchPending) {
        if (!teamLaunchPending) return@LaunchedEffect
        if (state.running) teamLaunchSawRunning = true
        if (state.team.visible || (teamLaunchSawRunning && !state.running)) {
            teamLaunchPending = false
        }
    }

    val imageLimitMessage = stringResource(R.string.local_image_selection_limit, MAX_LOCAL_IMAGE_SELECTION)
    val imageImportFailedMessage = stringResource(R.string.local_image_import_failed)
    val fileImportFailedMessage = stringResource(R.string.local_file_import_failed)
    val cameraFailedMessage = stringResource(R.string.composer_camera_failed)
    val takePhoto = rememberLocalCameraCapture(
        sessionId = state.sessionId,
        onCaptured = { uri, release ->
            scope.launch {
                try {
                    val imported = onImportAttachment(uri)
                    attachmentError = when (localComposerAttachmentDecision(attachments, imported)) {
                        LocalComposerAttachmentDecision.ACCEPT -> { attachments += imported; null }
                        LocalComposerAttachmentDecision.DUPLICATE -> null
                        LocalComposerAttachmentDecision.IMAGE_LIMIT -> imageLimitMessage
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    attachmentError = error.message ?: imageImportFailedMessage
                } finally {
                    release()
                }
            }
        },
        onFailure = { attachmentError = cameraFailedMessage },
    )
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
                        when (localComposerAttachmentDecision(attachments, imported)) {
                            LocalComposerAttachmentDecision.ACCEPT -> attachments += imported
                            LocalComposerAttachmentDecision.DUPLICATE -> Unit
                            LocalComposerAttachmentDecision.IMAGE_LIMIT -> failure = imageLimitMessage
                        }
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
                    val imported = onImportAttachment(uri)
                    attachmentError = when (localComposerAttachmentDecision(attachments, imported)) {
                        LocalComposerAttachmentDecision.ACCEPT -> {
                            attachments += imported
                            null
                        }
                        LocalComposerAttachmentDecision.DUPLICATE -> null
                        LocalComposerAttachmentDecision.IMAGE_LIMIT -> imageLimitMessage
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    attachmentError = error.message ?: fileImportFailedMessage
                }
            }
        }
    }

    LaunchedEffect(state.sessionId) {
        transcriptInitialPositionReady = false
        scrollHint.hide()
        attachments.clear()
        attachmentError = null
        showReplySuggestions = false
        if (transcriptItems.isNotEmpty()) {
            listState.scrollToItem(transcriptLastListIndex)
        }
        transcriptInitialPositionReady = true
    }

    LocalTranscriptAutoPager(
        sessionId = state.sessionId,
        listState = listState,
        enabled = transcriptInitialPositionReady &&
            hasOlderTranscript &&
            !loadingOlderTranscript &&
            transcriptHistoryError == null,
        onLoadOlder = onLoadOlderTranscript,
    )
    LocalComposerTailFollower(
        listState = listState,
        anchoredViewportExtent = composerTailViewportAnchor,
    )
    LocalWorkStreamingTailFollower(
        listState = listState,
        enabled = showStreamingTail,
    )

    LaunchedEffect(state.messages.size, transcriptItems.size, state.running) {
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onOpenDrawer != null) DsIconButton(
                    icon = FeatherIcons.Menu,
                    contentDescription = stringResource(R.string.app_open_navigation),
                    onClick = onOpenDrawer,
                    containerColor = colors.bgLayer1,
                    tint = colors.labelPrimary,
                )
                Column(Modifier.weight(1f)) {
                    if (state.usageMode == LocalUsageMode.CHAT) {
                        val hasSelectedPersona = !state.chatPersona.isUnboundChatPersona()
                        val personaDisplayName = if (hasSelectedPersona) {
                            state.chatPersona.name
                        } else {
                            stringResource(R.string.local_chat_no_persona)
                        }
                        ChatSurfaceHeader(
                            personaName = personaDisplayName,
                            portraitPath = currentGalleryEntry?.portraitPath.orEmpty(),
                            secondary = if (state.conversationMode == LocalConversationMode.INDEPENDENT) {
                                stringResource(R.string.local_new_session_chat_new)
                            } else {
                                localConversationModeLabel(state.conversationMode)
                            },
                            groupEnabled = state.groupChat.enabled,
                            groupMembers = state.groupChat.members,
                            activeSpeakerName = state.groupActiveSpeakerName,
                            running = state.running || state.loading,
                            behaviorTuningCustomized = !state.chatState.behaviorTuning.isNatural(),
                            tuningEnabled = hasSelectedPersona,
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
                // 隐藏大卡片后仍可从顶部直接查看，不占用聊天记录和输入区。
                if (state.usageMode == LocalUsageMode.WORK &&
                    teamCardDismissed && state.team.visible) {
                    DsIconButton(
                        icon = FeatherIcons.Users,
                        contentDescription = stringResource(R.string.local_team_reopen),
                        onClick = { showTeamPanel = true },
                        tint = colors.labelSecondary,
                    )
                }
            }
        }

        modeIntro?.usageMode?.let { mode ->
            Surface(
                color = colors.wallpaperSurface(WallpaperSurfaceLevel.CARD),
                shape = DsShapes.block,
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
                shape = DsShapes.block,
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
                shape = DsShapes.block,
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

        if (state.groupChat.enabled && state.groupChat.failedReplyMemberIds.isNotEmpty()) {
            GroupReplyFailureNotice(state.groupChat, state.running) { request -> onSend(request, emptyList()) }
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
                if (showTranscriptPagingRow) {
                    item(key = "local-transcript-history-status") {
                        LocalTranscriptPagingStatus(
                            loading = loadingOlderTranscript,
                            error = transcriptHistoryError,
                            onRetry = {
                                scope.launch { onLoadOlderTranscript(state.sessionId) }
                            },
                        )
                    }
                }
                if (transcriptItems.isEmpty() && (state.usageMode == LocalUsageMode.WORK || (!state.groupChat.enabled && state.chatPersona.isUnboundChatPersona()))) {
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
                            workspacePath = state.workspacePath,
                            skillDisplayNames = skillDisplayNames,
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
                if (showStreamingTail) {
                    if (state.usageMode == LocalUsageMode.CHAT) {
                        item(key = "streaming:${state.sessionId}") {
                            LocalStreamingChatTurn(
                                sessionId = state.sessionId,
                                streamingState = streamingState,
                            )
                        }
                    } else {
                        item(key = "work-streaming:${state.sessionId}") {
                            LocalStreamingWorkPreview(
                                sessionId = state.sessionId,
                                streamingState = streamingState,
                                hasDurableProgress = transcriptItems.lastOrNull() is LocalTranscriptItem.WorkProcess,
                                lastDurableNarrative = (transcriptItems.lastOrNull() as?
                                    LocalTranscriptItem.WorkProcess)?.messages?.lastOrNull {
                                    it.role == "progress" || it.role == "assistant"
                                }?.content,
                            )
                        }
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

        if (!state.configured && !state.loading && transcriptItems.isEmpty()) {
            Surface(
                modifier = Modifier.fillMaxWidth().padding(
                    horizontal = DsSpacing.medium,
                    vertical = DsSpacing.small,
                ),
                shape = DsShapes.row,
                color = colors.wallpaperSurface(WallpaperSurfaceLevel.CARD),
            ) {
                Row(
                    modifier = Modifier.padding(DsSpacing.medium),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.local_welcome_connect_title),
                            style = DsType.std14Strong.withReadingWeight(),
                            color = colors.labelPrimary,
                        )
                        Text(
                            stringResource(R.string.local_welcome_connect_body),
                            style = DsType.small13.withReadingWeight(),
                            color = colors.labelSecondary,
                        )
                    }
                    DsButton(
                        text = stringResource(R.string.local_welcome_connect_action),
                        onClick = onConfigure,
                        size = DsButtonSize.Small,
                    )
                }
            }
        }

        val sendRejectMessage = sendFeedback.rejectReason
            ?.takeIf { sendFeedback.sessionId == state.sessionId }
            ?.let { reason -> localSendRejectMessage(reason, sendFeedback.rejectLimit) }
        LocalConversationErrorCard(
            sendRejectMessage = sendRejectMessage,
            showConnectAction = sendFeedback.sessionId == state.sessionId &&
                sendFeedback.rejectReason == LocalSendRejectReason.UNCONFIGURED,
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

        LocalAgentTeamStatusBar(
            team = state.team,
            launchPending = teamLaunchPending,
            dismissed = teamCardDismissed,
            onClick = {
                if (state.team.visible) showTeamPanel = true
            },
            modifier = Modifier.padding(
                horizontal = DsSpacing.medium,
                vertical = DsSpacing.xsmall,
            ),
        )

        LocalConversationComposer(
            state = state,
            skillDisplayNames = skillDisplayNames,
            activeModelProfile = activeModelProfile,
            input = input,
            attachments = attachments,
            onInputChange = { drafts.putBoundedLocalDraft(state.sessionId, it) },
            onRemoveAttachment = { index -> attachments.removeAt(index) },
            onClearAttachments = attachments::clear,
            onOpenAttachmentPicker = { showAttachmentPicker = true },
            onShowReplySuggestions = { showReplySuggestions = true },
            onGenerateReplySuggestions = onGenerateReplySuggestions,
            onSend = onSend,
            onSendTeam = { text, files ->
                onSendTeam(text, files).also { result ->
                    if (result.accepted) {
                        teamLaunchPending = true
                        teamCardDismissed = false
                        teamLaunchSawRunning = false
                    }
                }
            },
            teamDispatchSelected = teamDispatchSelected,
            onClearTeamDispatch = { teamDispatchSelected = false },
            onStop = onStop,
            reasoningMode = reasoningMode,
            temperatureLevel = if (state.usageMode == LocalUsageMode.WORK) {
                workTemperatureLevel
            } else {
                state.chatState.behaviorTuning.composerTemperatureLevel(
                    activeModelProfile?.let { LocalModelPresets.chatTemperatureRangeFor(it.model, it.baseUrl) },
                )
            },
            temperaturePosition = if (state.usageMode == LocalUsageMode.CHAT) {
                activeModelProfile?.let {
                    LocalModelPresets.chatTemperatureRangeFor(it.model, it.baseUrl)
                }?.let { state.chatState.behaviorTuning.temperaturePosition(it) }
            } else null,
            temperatureSaveFailed = temperatureSaveFailed,
            temperatureSaving = temperatureSaving,
            temperatureGroupChat = state.groupChat.enabled,
            temperatureEnabled = state.usageMode == LocalUsageMode.WORK ||
                (!state.groupChat.enabled && !temperatureSaving && !state.loading && !state.running),
            onTemperatureLevelChange = { level ->
                if (state.usageMode == LocalUsageMode.WORK) {
                    LocalWorkTemperatureControls.setLevel(state.sessionId, level)
                    workTemperatureLevel = LocalWorkTemperatureControls.level(state.sessionId, workTemperatureRange)
                } else if (!state.groupChat.enabled && !temperatureSaving &&
                    !state.loading && !state.running) {
                    temperatureSaving = true
                    temperatureSaveFailed = false
                    scope.launch {
                        try {
                            val proposed = state.chatPersona.copy(
                                behaviorTuning = state.chatState.behaviorTuning.withComposerTemperatureLevel(level),
                            )
                            temperatureSaveFailed = onConfigureChatPersona(proposed).isFailure
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            temperatureSaveFailed = true
                        } finally {
                            temperatureSaving = false
                        }
                    }
                }
            },
            onReasoningModeChange = { mode ->
                LocalReasoningControls.setMode(state.sessionId, mode)
                reasoningMode = LocalReasoningControls.mode(state.sessionId, state.usageMode)
            },
            onPlanModeChange = onPlanModeChange,
            onFocusChanged = { focused ->
                composerTailViewportAnchor = if (focused && listState.isConversationTailVisible()) {
                    listState.conversationViewportExtent().takeIf { it > 0 }
                } else {
                    null
                }
            },
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
        visible = showCharacterTuning &&
            state.usageMode == LocalUsageMode.CHAT &&
            !state.groupChat.enabled,
        persona = state.chatPersona,
        portraitPath = currentGalleryEntry?.portraitPath.orEmpty(),
        state = state.chatState,
        temperatureTuningAvailable = activeModelProfile?.let { profile ->
            val range = LocalModelPresets.chatTemperatureRangeFor(profile.model, profile.baseUrl)
            LocalModelPresets.runtimeCapabilitiesFor(
                model = profile.model,
                baseUrl = profile.baseUrl,
                protocol = profile.protocol,
                authKind = profile.authKind,
            ).temperature && range != null &&
                (!range.requiresDisabledThinking || reasoningMode == LocalReasoningUiMode.FAST)
        } ?: false,
        temperatureRange = activeModelProfile?.let {
            LocalModelPresets.chatTemperatureRangeFor(it.model, it.baseUrl)
        },
        onConfigurePersona = onConfigureChatPersona,
        onDismiss = { showCharacterTuning = false },
    )
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
                onConfigureChatPersona(profile).onSuccess { personaEditorDraft = null }
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
            workMode = state.usageMode == LocalUsageMode.WORK,
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
        DsBottomSheet(
            title = null,
            onDismiss = { showAttachmentPicker = false },
            showDragHandle = false,
            floating = true,
            scrollable = true,
        ) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    DsQuickActionTile(
                        icon = FeatherIcons.Camera,
                        label = stringResource(R.string.composer_camera),
                        modifier = Modifier.width(96.dp),
                        onClick = {
                            showAttachmentPicker = false
                            if (attachments.count { it.mediaType.startsWith("image/") } >= MAX_LOCAL_IMAGE_SELECTION) {
                                attachmentError = imageLimitMessage
                            } else takePhoto()
                        },
                    )
                }
                item {
                    DsQuickActionTile(
                        icon = FeatherIcons.Image,
                        label = stringResource(R.string.local_attachment_image),
                        modifier = Modifier.width(96.dp),
                        onClick = { showAttachmentPicker = false; imagePicker.launch(arrayOf("image/*")) },
                    )
                }
                item {
                    DsQuickActionTile(
                        icon = FeatherIcons.Folder,
                        label = stringResource(R.string.local_attachment_file),
                        modifier = Modifier.width(96.dp),
                        onClick = { showAttachmentPicker = false; filePicker.launch(arrayOf("*/*")) },
                    )
                }
            }
            if (state.usageMode == LocalUsageMode.CHAT && !state.groupChat.enabled) {
                DsSheetChoiceRow(
                    title = stringResource(R.string.local_persona_picker_title),
                    icon = FeatherIcons.User,
                    onClick = {
                        showAttachmentPicker = false
                        showPersonaPicker = true
                    },
                )
            }
            if (state.usageMode == LocalUsageMode.WORK && teamCardDismissed && state.team.visible) {
                DsSheetChoiceRow(
                    title = stringResource(R.string.local_team_reopen),
                    icon = FeatherIcons.ChevronRight,
                    onClick = {
                        showAttachmentPicker = false
                        showTeamPanel = true
                    },
                )
            }
            if (state.usageMode == LocalUsageMode.WORK) {
                LocalAgentSwarmLaunchEntry(
                    selected = teamDispatchSelected,
                    onClick = {
                        teamDispatchSelected = !teamDispatchSelected
                        showAttachmentPicker = false
                    },
                )
            }
        }
    }

    val teamPanelResources = androidx.compose.ui.platform.LocalResources.current
    if (showTeamPanel && state.usageMode == LocalUsageMode.WORK && state.team.visible) {
        LocalAgentTeamSheet(
            team = state.team,
            onMemberOutput = onTeamMemberOutput,
            onSendMemberMessage = onSendTeamMemberMessage,
            onStopMember = onStopTeamMember,
            onStopAll = onStopTeam,
            onLeadFollowup = { memberName ->
                val request = teamPanelResources.getString(
                    if (memberName.isBlank()) R.string.local_team_lead_followup_request else R.string.local_team_lead_recover_request,
                    memberName,
                )
                val previous = drafts[state.sessionId].orEmpty()
                drafts.putBoundedLocalDraft(state.sessionId, listOf(request, previous).filter(String::isNotBlank).joinToString("\n\n"))
                teamDispatchSelected = true
                showTeamPanel = false
            },
            onDismiss = {
                teamCardDismissed = true
                showTeamPanel = false
            },
            onCollapse = {
                teamCardDismissed = true
                showTeamPanel = false
            },
        )
    }
}

internal enum class LocalComposerAttachmentDecision {
    ACCEPT,
    DUPLICATE,
    IMAGE_LIMIT,
}

internal fun localComposerAttachmentDecision(
    current: List<LocalImportedAttachment>,
    candidate: LocalImportedAttachment,
    maxImages: Int = MAX_LOCAL_IMAGE_SELECTION,
): LocalComposerAttachmentDecision {
    require(maxImages > 0)
    if (candidate.attachmentId != null && current.any { it.attachmentId == candidate.attachmentId }) {
        return LocalComposerAttachmentDecision.DUPLICATE
    }
    if (
        candidate.mediaType.startsWith("image/") &&
        current.count { it.mediaType.startsWith("image/") } >= maxImages
    ) {
        return LocalComposerAttachmentDecision.IMAGE_LIMIT
    }
    return LocalComposerAttachmentDecision.ACCEPT
}

@Composable
private fun EmptyLocalHarness(
    onSuggestion: (String) -> Unit,
) {
    val colors = DsTheme.colors
    val filesPrompt = stringResource(R.string.local_prompt_files)
    val researchPrompt = stringResource(R.string.local_prompt_research)
    val tasksPrompt = stringResource(R.string.local_prompt_tasks)
    var suggestionsOpen by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Text(
            stringResource(R.string.local_welcome_title),
            style = DsType.large20.withReadingWeight(),
            color = colors.labelPrimary,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.widthIn(max = 280.dp),
        )
        Box {
            DsButton(
                text = stringResource(R.string.local_suggestions_title),
                icon = FeatherIcons.Sparkles,
                variant = DsButtonVariant.Info,
                onClick = { suggestionsOpen = true },
            )
            DsPopupMenu(
                expanded = suggestionsOpen,
                onDismiss = { suggestionsOpen = false },
                items = listOf(
                    MenuItem(stringResource(R.string.local_suggestion_files), FeatherIcons.FileText, onClick = { onSuggestion(filesPrompt) }),
                    MenuItem(stringResource(R.string.local_suggestion_research), FeatherIcons.Globe, onClick = { onSuggestion(researchPrompt) }),
                    MenuItem(stringResource(R.string.local_suggestion_tasks), FeatherIcons.CheckSquare, onClick = { onSuggestion(tasksPrompt) }),
                ),
            )
        }
    }
}

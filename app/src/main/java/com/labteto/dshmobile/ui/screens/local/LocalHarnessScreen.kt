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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
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
internal fun localHarnessDrawerUsageMode(
    current: LocalUsageMode,
    pending: LocalUsageMode?,
): LocalUsageMode = pending ?: current
@Composable
internal fun localSendRejectMessage(reason: LocalSendRejectReason, limit: Int?): String = when (reason) {
    LocalSendRejectReason.EMPTY -> stringResource(R.string.local_send_rejected_empty)
    LocalSendRejectReason.LOADING -> stringResource(R.string.local_send_rejected_loading)
    LocalSendRejectReason.UNCONFIGURED -> stringResource(R.string.local_send_rejected_unconfigured)
    LocalSendRejectReason.SESSION_TRANSITION -> stringResource(R.string.local_send_rejected_session_transition)
    LocalSendRejectReason.QUEUE_FULL -> stringResource(
        R.string.local_send_rejected_queue_full,
        limit ?: 0,
    )
    LocalSendRejectReason.QUEUE_UNAVAILABLE -> stringResource(R.string.local_send_rejected_queue_unavailable)
}

/** Default Android 16 home: local Harness first, remote transports live in the left drawer. */
@Composable
fun LocalHarnessScreen(
    requestedSessionId: String? = null,
    onSessionRequestConsumed: () -> Unit = {},
    onOpenRemote: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenTasks: () -> Unit,
    onOpenTools: () -> Unit,
    viewModel: LocalHarnessViewModel = hiltViewModel(),
) {
    val shell by viewModel.shellState.collectAsStateWithLifecycle()
    val activeModelProfile by viewModel.activeModelProfile.collectAsStateWithLifecycle()
    val sendFeedback by viewModel.sendFeedbackState.collectAsStateWithLifecycle()
    val gallery by viewModel.gallery.collectAsStateWithLifecycle()
    val transcriptHistory by viewModel.transcriptHistory.collectAsStateWithLifecycle()
    val pinnedSessionIds by viewModel.pinnedSessionIds.collectAsStateWithLifecycle()
    val sessionTitleOverrides by viewModel.sessionTitleOverrides.collectAsStateWithLifecycle()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val drawerFocusManager = LocalFocusManager.current
    val drawerKeyboard = LocalSoftwareKeyboardController.current
    val modeIntroPreferences = remember(context) { context.getSharedPreferences("local_mode_intro", android.content.Context.MODE_PRIVATE) }
    var showNewSessionMode by rememberSaveable { mutableStateOf(false) }
    var filesMode by remember { mutableStateOf<LocalFilesMode?>(null) }
    var showPersonaGallery by rememberSaveable { mutableStateOf(false) }
    var showDiary by rememberSaveable { mutableStateOf(false) }
    var showPersonaGallerySavePrompt by rememberSaveable { mutableStateOf(false) }
    var showNewPersona by rememberSaveable { mutableStateOf(false) }
    var showRunCenter by rememberSaveable { mutableStateOf(false) }
    var modeIntro by remember { mutableStateOf<LocalUsageMode?>(null) }
    var pendingUsageMode by remember { mutableStateOf<LocalUsageMode?>(null) }

    LaunchedEffect(modeIntro) { if (modeIntro != null) { delay(6_000); modeIntro = null } }

    LaunchedEffect(pendingUsageMode, shell.loading, shell.usageMode) {
        val pending = pendingUsageMode ?: return@LaunchedEffect
        when {
            shell.usageMode == pending && !shell.loading -> pendingUsageMode = null
            !shell.loading -> {
                // A rejected/no-op transition should not leave the sidebar showing a phantom mode.
                // Give the engine one frame window to publish loading=true before rolling back.
                delay(250)
                if (!shell.loading && shell.usageMode != pending) pendingUsageMode = null
            }
        }
    }

    LaunchedEffect(shell.sessionId) {
        viewModel.prepareTranscriptHistory(shell.sessionId)
    }

    // Swiping the drawer open must release the composer focus as well as its IME.
    LaunchedEffect(drawerState.targetValue, drawerState.isOpen) {
        if (drawerState.targetValue == DrawerValue.Open || drawerState.isOpen) {
            drawerFocusManager.clearFocus(force = true)
            drawerKeyboard?.hide()
        }
    }

    fun switchUsageMode(target: LocalUsageMode) {
        val returningFromGroupToSingle =
            target == LocalUsageMode.CHAT &&
                shell.usageMode == LocalUsageMode.CHAT &&
                shell.groupChat.enabled
        if (target == shell.usageMode && pendingUsageMode == null && !returningFromGroupToSingle) return
        if (target != shell.usageMode) {
            val key = "shown_" + target.name.lowercase()
            if (!modeIntroPreferences.getBoolean(key, false)) {
                modeIntroPreferences.edit().putBoolean(key, true).apply()
                modeIntro = target
            }
        }
        pendingUsageMode = target
        viewModel.switchUsageMode(target)
    }

    LaunchedEffect(requestedSessionId, shell.sessions) {
        val target = requestedSessionId?.takeIf(String::isNotBlank) ?: return@LaunchedEffect
        if (target == shell.sessionId || shell.sessions.any { it.id == target }) {
            if (target != shell.sessionId) viewModel.switchSession(target)
            onSessionRequestConsumed()
        }
    }

    BackHandler(enabled = drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            LocalModeDrawer(
                currentSessionId = shell.sessionId,
                sessions = shell.sessions,
                gallery = gallery,
                usageMode = localHarnessDrawerUsageMode(shell.usageMode, pendingUsageMode),
                modeSwitchEnabled = !shell.loading &&
                    localHarnessModeSwitchEnabled(shell.usageMode, shell.running),
                pinnedSessionIds = pinnedSessionIds,
                sessionTitleOverrides = sessionTitleOverrides,
                currentGalleryId = shell.galleryId,
                currentGalleryStoryId = shell.galleryStoryId,
                currentPersonaName = shell.chatPersona.name,
                currentPersonaIdentity = shell.chatPersona.identity,
                workModelLabel = activeModelProfile?.displayName
                    ?: activeModelProfile?.model
                    ?: stringResource(R.string.local_model_setup),
                groupChatEnabled = shell.groupChat.enabled,
                running = shell.running,
                onUsageModeChange = { showDiary = false; switchUsageMode(it) },
                onNewSession = {
                    scope.launch { drawerState.close() }
                    showNewSessionMode = true
                },
                onRemote = {
                    scope.launch { drawerState.close() }
                    onOpenRemote()
                },
                onSwitchSession = { sessionId ->
                    viewModel.switchSession(sessionId)
                    scope.launch { drawerState.close() }
                },
                onDeleteSessions = { ids -> scope.launch { viewModel.deleteSessions(ids) } },
                onWorkspaceFiles = {
                    filesMode = LocalFilesMode.WORKSPACE
                    scope.launch { drawerState.close() }
                },
                onOpenRunCenter = {
                    scope.launch { drawerState.close() }
                    showRunCenter = true
                },
                groupMemberCount = shell.groupChat.members.size,
                onOpenGroupChat = {
                    scope.launch { drawerState.close() }
                    viewModel.openGroupChatMode()
                },
                onOpenPersonaGallery = {
                    scope.launch { drawerState.close() }
                    if (viewModel.hasUnsavedCurrentPersona()) {
                        showPersonaGallerySavePrompt = true
                    } else {
                        showPersonaGallery = true
                    }
                },
                onOpenDiary = { showDiary = true; scope.launch { drawerState.close() } },
                onTasks = {
                    scope.launch { drawerState.close() }
                    onOpenTasks()
                },
                onTools = {
                    scope.launch { drawerState.close() }
                    onOpenTools()
                },
                onSettings = {
                    scope.launch { drawerState.close() }
                    onOpenSettings()
                },
            )
        },
    ) {
        LocalConversationStateContent(viewModel, shell.usageMode) { state ->
            Box(Modifier.fillMaxSize()) {
                when {
                    showDiary && state.usageMode == LocalUsageMode.CHAT -> CharacterDiaryScreen(
                        gallery = gallery,
                        currentPersona = state.chatPersona,
                        currentGalleryId = state.galleryId,
                        loadEntries = viewModel::diaryEntries,
                        onDismiss = { showDiary = false },
                    )
                    showPersonaGallery && state.usageMode == LocalUsageMode.CHAT -> PersonaGalleryScreen(
                    entries = gallery,
                    presets = viewModel.personaPresets,
                    currentPersona = state.chatPersona,
                    currentGalleryId = state.galleryId,
                    currentGalleryStoryId = state.galleryStoryId,
                    currentHasUnsavedChanges = viewModel.currentGalleryHasUnsavedChanges(),
                    currentSessionId = state.sessionId,
                    canSave = !state.loading && !state.running,
                    onSaveCurrent = viewModel::saveCurrentToGallery,
                    onEditNotes = viewModel::editGalleryNotes,
                    onRenameStory = viewModel::renameGalleryStory,
                    onInspect = viewModel::inspectGalleryPersona,
                    onApplySuggestions = viewModel::applyGallerySuggestions,
                    onDelete = viewModel::deleteGalleryEntry,
                    onDeleteStory = viewModel::deleteGalleryStory,
                    onDeleteHistoryMessage = viewModel::deleteGalleryHistoryMessage,
                    onExport = viewModel::exportGalleryPersona,
                    onImport = viewModel::importGalleryPersona,
                    onInstallPreset = viewModel::installPersonaPreset,
                    onSetPortrait = viewModel::setGalleryPortrait,
                    onRemovePortrait = viewModel::removeGalleryPortrait,
                    onStart = { id, storyId, freshStory ->
                        if (viewModel.startFromGallery(id, storyId, freshStory)) showPersonaGallery = false
                    },
                    onCreate = { showNewPersona = true },
                    onDismiss = { showPersonaGallery = false },
                )
                else -> LocalConversationSurface(
                    state = state,
                    activeModelProfile = activeModelProfile,
                    sendFeedback = sendFeedback,
                    streamingState = viewModel.streamingState,
                    gallery = gallery,
                    transcriptHistory = transcriptHistory,
                    modeIntro = modeIntro,
                    onConfigure = onOpenSettings,
                    onSelectModel = viewModel::selectModel,
                    onSend = viewModel::send,
                    onEditAndResend = viewModel::editAndResendUserMessage,
                    onSelectMessageVariant = viewModel::selectChatMessageVariant,
                    onRegenerate = viewModel::regenerateReply,
                    onGenerateReplySuggestions = viewModel::generateReplySuggestions,
                    onLoadOlderTranscript = viewModel::loadOlderTranscript,
                    onImportAttachment = viewModel::importAttachment,
                    onStop = viewModel::stop,
                    onNewSession = { showNewSessionMode = true },
                    onExitGroupChat = viewModel::leaveGroupChatMode,
                    onOpenRunCenter = { showRunCenter = true },
                    sessionTitle = sessionTitleOverrides[state.sessionId]
                        ?: shell.sessions.firstOrNull { it.id == state.sessionId }?.title
                        ?: stringResource(R.string.chatlist_new_session),
                    sessionPinned = state.sessionId in pinnedSessionIds,
                    onTogglePinSession = { viewModel.toggleSessionPinned(state.sessionId) },
                    onRenameSession = { title -> viewModel.renameSession(state.sessionId, title) },
                    onDeleteSession = { scope.launch { viewModel.deleteSessions(setOf(state.sessionId)) } },
                    onConfigureChatPersona = viewModel::configureChatPersona,
                    onConfigureGroupMembers = viewModel::configureGroupChatMembers,
                    onSelectGalleryPersona = viewModel::selectGalleryPersonaForCurrentChat,
                    onAutoFillChatPersona = viewModel::autoFillChatPersona,
                    onSaveGroupAnnouncement = viewModel::setGroupChatAnnouncement,
                    onGenerateGroupAnnouncement = viewModel::generateGroupChatAnnouncement,
                    onUndoPersonaCorrection = viewModel::undoChatPersonaCorrection,
                    onPlanModeChange = viewModel::setPlanMode,
                    onApprove = viewModel::approve,
                    onDeny = viewModel::deny,
                    onAutoApprove = viewModel::enableAutoApproval,
                    onAutoApprovePending = viewModel::enableAutoApprovalForPending,
                    onApproveDeviceTurn = viewModel::enableDeviceApprovalLease,
                    onDisableDeviceTurn = viewModel::disableDeviceApprovalLease,
                    onDisableAutoApprove = viewModel::disableAutoApproval,
                    onAnswerQuestion = viewModel::answerQuestion,
                    onCancelQuestion = viewModel::cancelQuestion,
                )
                }
            }
        }
    }

    if (showNewSessionMode) {
        if (shell.usageMode == LocalUsageMode.CHAT && shell.groupChat.enabled) {
            GroupNewSessionDialog(
                onDismiss = { showNewSessionMode = false },
                onNewGroup = {
                    showNewSessionMode = false
                    viewModel.createGroupChatSession()
                },
                onNewSingle = {
                    showNewSessionMode = false
                    viewModel.createSingleChatSession()
                },
            )
        } else {
            NewSessionModeDialog(
                usageMode = shell.usageMode,
                onDismiss = { showNewSessionMode = false },
                onSelect = { mode ->
                    showNewSessionMode = false
                    viewModel.createSession(mode)
                },
            )
        }
    }

    if (showNewPersona && shell.usageMode == LocalUsageMode.CHAT) {
        ChatPersonaDialog(
            profile = remember { PersonaProfile(name = "") },
            onSave = { profile -> viewModel.createGalleryPersona(profile).map { Unit } },
            onAutoFill = viewModel::autoFillNewPersona,
            onDismiss = { showNewPersona = false },
            creatingNew = true,
        )
    }

    filesMode?.let { mode ->
        LocalWorkspaceFilesDialog(
            mode = mode,
            sessionId = shell.sessionId,
            workspacePath = shell.workspacePath,
            loadWorkspace = viewModel::workspaceFiles,
            loadConversation = viewModel::conversationFiles,
            loadPreview = viewModel::previewWorkspaceFile,
            onDismiss = { filesMode = null },
        )
    }

    if (showRunCenter && shell.usageMode == LocalUsageMode.WORK) {
        LocalWorkStateContent(viewModel) { workState ->
            LocalRunCenterScreen(
                state = workState,
                onJobOutput = viewModel::backgroundJobOutput,
                onStopJob = viewModel::stopBackgroundJob,
                onOpenResults = {
                    showRunCenter = false
                    filesMode = LocalFilesMode.CONVERSATION
                },
                onDismiss = { showRunCenter = false },
            )
        }
    }

    if (showPersonaGallerySavePrompt && shell.usageMode == LocalUsageMode.CHAT) {
        PersonaGallerySavePromptDialog(
            persona = shell.chatPersona,
            isUpdate = viewModel.currentGalleryNeedsUpdate(),
            canSave = !shell.loading && !shell.running,
            onSaveCurrent = {
                viewModel.saveCurrentToGallery(
                    notes = "",
                    existingId = shell.galleryId,
                    existingStoryId = shell.galleryStoryId,
                )
            },
            onContinue = {
                showPersonaGallerySavePrompt = false
                showPersonaGallery = true
            },
            onDismiss = { showPersonaGallerySavePrompt = false },
        )
    }

}

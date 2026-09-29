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
import com.labteto.dshmobile.local.presentation.LocalConversationSurfaceState
import com.labteto.dshmobile.local.LocalHarnessStreamingState
import com.labteto.dshmobile.local.chatBranchInfo
import com.labteto.dshmobile.local.LocalImportedAttachment
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.chat.PersonaGalleryEntry
import com.labteto.dshmobile.local.chat.PersonaProfile
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
import com.labteto.dshmobile.ui.components.DsCard
import com.labteto.dshmobile.ui.components.DsCategoryRow
import com.labteto.dshmobile.ui.components.DsGroupCard
import com.labteto.dshmobile.ui.components.DsIconButton
import com.labteto.dshmobile.ui.components.DsPill
import com.labteto.dshmobile.ui.components.DsQuickActionTile
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.screens.main.RenameDialog
import com.labteto.dshmobile.ui.theme.BackgroundRegion
import com.labteto.dshmobile.ui.theme.DsMetrics
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.LocalAppBackgroundState
import com.labteto.dshmobile.ui.theme.WallpaperSurfaceLevel
import com.labteto.dshmobile.ui.theme.wallpaperSurface
import com.labteto.dshmobile.ui.theme.rootSurface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.StateFlow
internal fun localHarnessDrawerUsageMode(
    current: LocalUsageMode,
    pending: LocalUsageMode?,
): LocalUsageMode = pending ?: current
internal fun localHarnessShowsBlockingLoading(
    loading: Boolean,
    hasRenderedSurface: Boolean,
): Boolean = loading && !hasRenderedSurface
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
    val gallery by viewModel.gallery.collectAsStateWithLifecycle()
    val transcriptHistory by viewModel.transcriptHistory.collectAsStateWithLifecycle()
    val pinnedSessionIds by viewModel.pinnedSessionIds.collectAsStateWithLifecycle()
    val sessionTitleOverrides by viewModel.sessionTitleOverrides.collectAsStateWithLifecycle()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val drawerFocusManager = LocalFocusManager.current
    val drawerKeyboard = LocalSoftwareKeyboardController.current
    val modeIntroPreferences = remember(context) {
        context.getSharedPreferences("local_mode_intro", android.content.Context.MODE_PRIVATE)
    }
    var showNewSessionMode by rememberSaveable { mutableStateOf(false) }
    var filesMode by remember { mutableStateOf<LocalFilesMode?>(null) }
    var showPersonaGallery by rememberSaveable { mutableStateOf(false) }
    var showPersonaGallerySavePrompt by rememberSaveable { mutableStateOf(false) }
    var showNewPersona by rememberSaveable { mutableStateOf(false) }
    var showRunCenter by rememberSaveable { mutableStateOf(false) }
    var modeIntro by remember { mutableStateOf<LocalUsageMode?>(null) }
    var hasRenderedHarnessSurface by rememberSaveable { mutableStateOf(false) }
    var pendingUsageMode by remember { mutableStateOf<LocalUsageMode?>(null) }

    LaunchedEffect(modeIntro) {
        if (modeIntro != null) {
            delay(6_000)
            modeIntro = null
        }
    }

    LaunchedEffect(shell.loading) {
        if (!shell.loading) hasRenderedHarnessSurface = true
    }

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
                modeSwitchEnabled = !shell.running,
                pinnedSessionIds = pinnedSessionIds,
                sessionTitleOverrides = sessionTitleOverrides,
                onUsageModeChange = ::switchUsageMode,
                onNewSession = {
                    scope.launch { drawerState.close() }
                    showNewSessionMode = true
                },
                onClose = { scope.launch { drawerState.close() } },
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
                galleryCount = gallery.size,
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
                    localHarnessShowsBlockingLoading(state.loading, hasRenderedHarnessSurface) -> LoadingScreen()
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
                    streamingState = viewModel.streamingState,
                    gallery = gallery,
                    transcriptHistory = transcriptHistory,
                    modeIntro = modeIntro,
                    onOpenMenu = {
                        drawerFocusManager.clearFocus(force = true)
                        drawerKeyboard?.hide()
                        scope.launch { drawerState.open() }
                    },
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
                if (state.loading && hasRenderedHarnessSurface) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = {},
                            ),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .padding(top = DsSpacing.large)
                                .size(20.dp),
                            strokeWidth = 2.dp,
                            color = DsTheme.colors.accent,
                        )
                    }
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
            DsBottomSheet(
                title = stringResource(R.string.local_run_center),
                onDismiss = { showRunCenter = false },
            ) {
                ExecutionStatusCard(
                    state = workState,
                    onJobOutput = viewModel::backgroundJobOutput,
                    onStopJob = viewModel::stopBackgroundJob,
                    onOpenResults = {
                        showRunCenter = false
                        filesMode = LocalFilesMode.CONVERSATION
                    },
                    showHeader = false,
                )
            }
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

@Composable
private fun LoadingScreen() {
    Box(
        Modifier.fillMaxSize().background(DsTheme.colors.rootSurface()),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(color = DsTheme.colors.brandPrimary)
    }
}

@Composable
private fun LocalConfiguration(
    state: LocalConversationSurfaceState,
    canCancel: Boolean,
    onOpenMenu: () -> Unit,
    onCancel: () -> Unit,
    onSave: (String, String, String) -> Unit,
    onClearCredential: () -> Unit,
) {
    val colors = DsTheme.colors
    var apiKey by remember { mutableStateOf("") }
    var model by rememberSaveable(state.model) { mutableStateOf(state.model) }
    var baseUrl by rememberSaveable(state.baseUrl) { mutableStateOf(state.baseUrl) }

    Column(
        Modifier.fillMaxSize().background(colors.rootSurface()).safeDrawingPadding().imePadding()
            .verticalScroll(rememberScrollState())
            .padding(DsSpacing.xlarge),
        verticalArrangement = Arrangement.spacedBy(DsSpacing.comfortable),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            DsIconButton(
                icon = FeatherIcons.Menu,
                contentDescription = stringResource(R.string.local_open_menu),
                onClick = onOpenMenu,
                containerColor = colors.wallpaperSurface(WallpaperSurfaceLevel.FLOATING, BackgroundRegion.TOP),
                shadowElevation = 3.dp,
            )
            Text(
                stringResource(R.string.local_harness_title),
                style = DsType.large20,
                color = colors.labelPrimary,
                modifier = Modifier.weight(1f),
            )
            if (canCancel) {
                DsButton(stringResource(R.string.common_cancel), onCancel, variant = DsButtonVariant.Ghost)
            } else {
                Spacer(Modifier.size(56.dp))
            }
        }

        DsCard(verticalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
            Text(stringResource(R.string.local_on_device_execution_title), style = DsType.base16Strong, color = colors.labelPrimary)
            Text(
                stringResource(R.string.local_on_device_execution_hint),
                style = DsType.std14,
                color = colors.labelSecondary,
            )
        }

        Text(stringResource(R.string.local_model_section_title), style = DsType.std14, color = colors.labelTertiary)
        DsGroupCard {
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(if (state.configured) R.string.advanced_replace_model_key else R.string.advanced_model_key)) },
                supportingText = {
                    Text(
                        stringResource(
                            if (state.configured) R.string.advanced_model_configured
                            else R.string.advanced_model_unconfigured,
                        ),
                    )
                },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
            )
            Spacer(Modifier.height(DsSpacing.medium))
            Column(verticalArrangement = Arrangement.spacedBy(DsSpacing.tiny)) {
                Text(stringResource(R.string.models_title), style = DsType.std14Strong, color = colors.labelPrimary)
                ModelChoice("deepseek-flash", stringResource(R.string.local_model_flash_label), model) { model = it }
                ModelChoice("deepseek-v4-pro", stringResource(R.string.local_model_pro_label), model) { model = it }
            }
            Spacer(Modifier.height(DsSpacing.medium))
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.advanced_endpoint)) },
                supportingText = { Text(stringResource(R.string.local_endpoint_hint)) },
                singleLine = true,
            )
        }

        state.error?.let { Text(it, style = DsType.small13, color = colors.error) }

        DsButton(
            text = stringResource(R.string.local_model_save_enter),
            onClick = { onSave(apiKey, model, baseUrl) },
            modifier = Modifier.fillMaxWidth(),
            enabled = (state.configured || apiKey.isNotBlank()) && baseUrl.isNotBlank(),
        )
        if (state.configured) {
            DsButton(
                text = stringResource(R.string.advanced_clear_key),
                onClick = onClearCredential,
                modifier = Modifier.fillMaxWidth(),
                variant = DsButtonVariant.Danger,
            )
        }
    }
}

@Composable
private fun ModelChoice(id: String, label: String, selected: String, onSelect: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
            .selectable(selected = selected == id, onClick = { onSelect(id) })
            .padding(vertical = DsSpacing.tiny),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected == id, onClick = { onSelect(id) })
        Text(label, style = DsType.std14, color = DsTheme.colors.labelPrimary)
    }
}

@Composable
private fun LocalConversationSurface(
    state: LocalConversationSurfaceState,
    streamingState: StateFlow<LocalHarnessStreamingState>,
    gallery: List<PersonaGalleryEntry>,
    transcriptHistory: LocalTranscriptHistoryState,
    modeIntro: LocalUsageMode?,
    onOpenMenu: () -> Unit,
    onConfigure: () -> Unit,
    onSelectModel: (String) -> Unit,
    onSend: (String, List<LocalImportedAttachment>) -> Unit,
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
    onSaveGroupAnnouncement: (String) -> Boolean,
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
    val backgroundState = LocalAppBackgroundState.current
    val rootSurfaceColor = colors.rootSurface()
    // Custom wallpapers remain visible behind the chat toolbar; work mode still gets its
    // stable root work surface from rootSurfaceColor above.
    val topSurfaceColor = colors.rootSurface()
    val composerSurfaceColor = if (backgroundState.hasImage) Color.Transparent else colors.composerCard
    val scope = rememberCoroutineScope()
    val drafts = rememberSaveable(
        saver = listSaver(
            save = { map -> map.entries.flatMap { listOf(it.key, it.value) } },
            restore = { values ->
                mutableStateMapOf<String, String>().apply {
                    values.chunked(2).forEach { pair ->
                        if (pair.size == 2) this[pair[0]] = pair[1]
                    }
                }
            },
        ),
    ) { mutableStateMapOf<String, String>() }
    val input = drafts[state.sessionId].orEmpty()
    var attachmentError by remember { mutableStateOf<String?>(null) }
    var showAttachmentPicker by rememberSaveable { mutableStateOf(false) }
    var approvalNoticeExpanded by rememberSaveable { mutableStateOf(false) }
    var showModelPicker by rememberSaveable { mutableStateOf(false) }
    var showPersonaPicker by rememberSaveable { mutableStateOf(false) }
    var showGroupMemberPicker by rememberSaveable { mutableStateOf(false) }
    var showGroupAnnouncement by rememberSaveable { mutableStateOf(false) }
    var showPersonaEditor by rememberSaveable { mutableStateOf(false) }
    var personaEditorDraft by remember { mutableStateOf<PersonaProfile?>(null) }
    var showReplySuggestions by rememberSaveable { mutableStateOf(false) }
    var replySuggestionsLoading by remember(state.sessionId) { mutableStateOf(false) }
    var editingUserMessage by remember { mutableStateOf<LocalHarnessMessage?>(null) }
    var renameSessionOpen by rememberSaveable(state.sessionId) { mutableStateOf(false) }
    val attachments = remember { mutableStateListOf<LocalImportedAttachment>() }
    val listState = rememberLazyListState()
    val (scrollHint, scrollConnection) = rememberConversationScrollHint(listState, reverseLayout = false)
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    var transcriptWindowSize by rememberSaveable(state.sessionId) {
        mutableStateOf(LOCAL_TRANSCRIPT_INITIAL_WINDOW_MESSAGES)
    }
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
    val groupChatReady = !state.groupChat.enabled || state.groupChat.members.size >= 2
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
                    runCatching { onImportAttachment(uri) }
                        .onSuccess { imported ->
                            val duplicate = imported.attachmentId != null &&
                                attachments.any { it.attachmentId == imported.attachmentId }
                            if (!duplicate) attachments += imported
                        }
                        .onFailure { error ->
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
                runCatching { onImportAttachment(uri) }
                    .onSuccess {
                        attachments += it
                        attachmentError = null
                    }
                    .onFailure { attachmentError = it.message ?: fileImportFailedMessage }
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
                val relationship = state.chatState.relationshipState.takeIf { it.isNotBlank() }
                val storyTitle = currentGalleryStory?.title?.takeIf { it.isNotBlank() }
                val contextSource = when (state.conversationMode) {
                    LocalConversationMode.INDEPENDENT -> null
                    else -> localConversationModeLabel(state.conversationMode)
                }
                ChatSurfaceHeader(
                    personaName = state.chatPersona.name,
                    portraitPath = currentGalleryEntry?.portraitPath.orEmpty(),
                    secondary = listOfNotNull(storyTitle, relationship, contextSource).joinToString(" · "),
                    groupEnabled = state.groupChat.enabled,
                    groupMembers = state.groupChat.members,
                    activeSpeakerName = state.groupActiveSpeakerName,
                    running = state.running || state.loading,
                    onOpenMenu = onOpenMenu,
                    onContextClick = {
                        if (state.groupChat.enabled) showGroupMemberPicker = true
                        else showPersonaPicker = true
                    },
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
                    onOpenMenu = onOpenMenu,
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
                    style = DsType.small13,
                    color = colors.labelSecondary,
                    modifier = Modifier.padding(horizontal = DsSpacing.medium, vertical = DsSpacing.small),
                )
            }
        }

        if (state.usageMode == LocalUsageMode.CHAT && state.groupChat.enabled) {
            Surface(
                color = colors.wallpaperSurface(WallpaperSurfaceLevel.CARD),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
                    .padding(horizontal = DsSpacing.medium, vertical = DsSpacing.tiny)
                    .clickable { showGroupAnnouncement = true },
            ) {
                Column(Modifier.padding(horizontal = DsSpacing.medium, vertical = DsSpacing.small)) {
                    Text(stringResource(R.string.local_group_announcement_title),
                        style = DsType.small13Strong, color = colors.labelPrimary)
                    Text(
                        state.groupChat.announcement.ifBlank {
                            stringResource(R.string.local_group_announcement_empty)
                        },
                        style = DsType.small13,
                        color = colors.labelSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
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
                        style = DsType.small13,
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
                            style = DsType.small13Strong,
                            color = colors.warnLabel,
                        )
                        Text(
                            if (approvalNoticeExpanded) stringResource(R.string.local_approval_scope_hide)
                            else stringResource(R.string.local_approval_scope_show),
                            style = DsType.caption11,
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
                        style = DsType.caption11,
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
                                drafts[state.sessionId] = suggestion
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
                                style = DsType.std14,
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
                        is LocalTranscriptItem.WorkProcess -> WorkProcessRow(transcriptItem.messages)
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
                streamingState = streamingState,
                surfaceColor = colors.wallpaperSurface(WallpaperSurfaceLevel.CARD),
            )
        }

        state.error?.let { error ->
            Surface(
                color = colors.warnTertiary,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = DsSpacing.medium),
            ) {
                Row(
                    Modifier.padding(horizontal = DsSpacing.medium, vertical = DsSpacing.small),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
                ) {
                    Text(
                        stringResource(R.string.agent_operation_generic) + " · " +
                            stringResource(R.string.agent_operation_status_failed),
                        style = DsType.small13,
                        color = colors.error,
                        modifier = Modifier.weight(1f),
                    )
                    state.messages.lastOrNull { message -> message.role == "user" }?.let { lastRequest ->
                        DsButton(
                            stringResource(R.string.local_restore_request),
                            { drafts[state.sessionId] = lastRequest.content },
                            variant = DsButtonVariant.Ghost,
                            size = DsButtonSize.Small,
                        )
                    }
                }
            }
        }
        attachmentError?.let {
            Text(
                it,
                style = DsType.small13,
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
                        onClick = { drafts[state.sessionId] = suggestion.text },
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
                            style = DsType.small13,
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

        val composerCanSend =
            groupChatReady && (input.isNotBlank() || attachments.isNotEmpty())

        fun submitComposerMessage() {
            if (!state.configured) {
                onConfigure()
                return
            }
            val selected = attachments.toList()
            onSend(input, selected)
            drafts[state.sessionId] = ""
            attachments.clear()
            focusManager.clearFocus(force = true)
            keyboardController?.hide()
        }

        DsConversationComposer(
            surfaceColor = composerSurfaceColor,
            shadowElevation = if (backgroundState.hasImage) 0.dp else 1.dp,
        ) {
            if (attachments.isNotEmpty()) {
                attachments.forEachIndexed { index, attachment ->
                    ImportedAttachmentRow(
                        attachment = attachment,
                        workspacePath = state.workspacePath,
                        onRemove = { attachments.removeAt(index) },
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(DsSpacing.tiny),
            ) {
                if (!state.running) {
                    DsComposerAction(
                        icon = Icons.Filled.Add,
                        contentDescription = stringResource(R.string.chat_composer_add_attachment),
                        onClick = { showAttachmentPicker = true },
                        tint = colors.labelPrimary,
                        containerColor = colors.hoverSolid,
                    )
                }

                if (state.usageMode == LocalUsageMode.WORK) {
                    DsComposerAction(
                        icon = FeatherIcons.CheckSquare,
                        contentDescription = stringResource(
                            if (state.planMode) R.string.local_plan_button_on
                            else R.string.local_plan_button_off,
                        ),
                        onClick = { onPlanModeChange(!state.planMode) },
                        enabled = !state.running,
                        tint = if (state.planMode) colors.accent else colors.labelSecondary,
                        containerColor = if (state.planMode) colors.accentTertiary else Color.Transparent,
                    )
                    DsComposerAction(
                        icon = Icons.Outlined.Shield,
                        contentDescription = stringResource(R.string.local_auto_approve_short),
                        onClick = if (state.safeAutoApprovalEnabled) {
                            onDisableAutoApprove
                        } else {
                            onAutoApprove
                        },
                        tint = if (state.safeAutoApprovalEnabled) colors.accent else colors.labelSecondary,
                        containerColor = if (state.safeAutoApprovalEnabled) {
                            colors.accentTertiary
                        } else {
                            Color.Transparent
                        },
                    )
                } else if (
                    !state.running &&
                    !state.groupChat.enabled &&
                    state.messages.any { message ->
                        message.role == "assistant" && message.content.isNotBlank()
                    }
                ) {
                    DsComposerAction(
                        icon = Icons.Outlined.AutoAwesome,
                        contentDescription = stringResource(R.string.local_reply_suggestions_open),
                        onClick = {
                            if (state.replySuggestions.any { it.text.isNotBlank() }) {
                                showReplySuggestions = true
                            } else if (!replySuggestionsLoading) {
                                replySuggestionsLoading = true
                                scope.launch {
                                    val generated = try {
                                        onGenerateReplySuggestions()
                                    } finally {
                                        replySuggestionsLoading = false
                                    }
                                    if (generated) showReplySuggestions = true
                                }
                            }
                        },
                        enabled = !state.running && !replySuggestionsLoading,
                        tint = if (state.replySuggestions.any { it.text.isNotBlank() }) {
                            colors.accent
                        } else {
                            colors.labelSecondary
                        },
                        containerColor = if (state.replySuggestions.any { it.text.isNotBlank() }) {
                            colors.accentTertiary
                        } else {
                            Color.Transparent
                        },
                    )
                }

                DsComposerField(
                    value = input,
                    onValueChange = { drafts[state.sessionId] = it },
                    placeholder = when {
                        state.usageMode == LocalUsageMode.WORK ->
                            stringResource(R.string.local_work_composer_hint)
                        state.groupChat.enabled ->
                            stringResource(R.string.local_group_chat_composer_hint)
                        else ->
                            stringResource(
                                R.string.local_chat_composer_persona_hint,
                                state.chatPersona.name,
                            )
                    },
                    modifier = Modifier.weight(1f),
                    maxLines = 5,
                )

                if (state.running) {
                    DsComposerAction(
                        icon = Icons.Filled.Stop,
                        contentDescription = stringResource(R.string.chat_composer_stop),
                        onClick = onStop,
                        tint = colors.onAccent,
                        containerColor = colors.error,
                        visualSize = DsComposerMetrics.primaryActionVisualSize,
                    )
                    if (state.usageMode == LocalUsageMode.WORK) {
                        DsComposerAction(
                            icon = Icons.Filled.ArrowUpward,
                            contentDescription = if (state.queuedInputCount > 0) {
                                stringResource(
                                    R.string.local_queue_message_count,
                                    state.queuedInputCount,
                                )
                            } else {
                                stringResource(R.string.local_queue_message)
                            },
                            onClick = ::submitComposerMessage,
                            enabled = composerCanSend,
                            tint = if (composerCanSend) colors.onAccent else colors.labelTertiary,
                            containerColor = if (composerCanSend) {
                                colors.buttonInfoFill
                            } else {
                                colors.buttonPrimaryDimmed
                            },
                            visualSize = DsComposerMetrics.primaryActionVisualSize,
                        )
                    }
                } else {
                    DsComposerAction(
                        icon = Icons.Filled.ArrowUpward,
                        contentDescription = stringResource(R.string.chat_composer_send),
                        onClick = ::submitComposerMessage,
                        enabled = composerCanSend,
                        tint = if (composerCanSend) colors.onAccent else colors.labelTertiary,
                        containerColor = if (composerCanSend) {
                            colors.buttonInfoFill
                        } else {
                            colors.buttonPrimaryDimmed
                        },
                        visualSize = DsComposerMetrics.primaryActionVisualSize,
                    )
                }
            }
        }

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
                style = DsType.small13,
                color = colors.labelSecondary,
            )
            state.replySuggestions.filter { it.text.isNotBlank() }.forEach { suggestion ->
                Surface(
                    onClick = {
                        drafts[state.sessionId] = suggestion.text
                        showReplySuggestions = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.running,
                    shape = DsShapes.row,
                    color = colors.bgLayer1,
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
                                style = DsType.std14Strong,
                                color = colors.labelPrimary,
                                modifier = Modifier.weight(1f),
                            )
                            if (suggestion.style.isNotBlank() || suggestion.bold) {
                                Text(
                                    if (suggestion.bold) stringResource(R.string.local_reply_suggestions_bold)
                                    else suggestion.style,
                                    style = DsType.caption11,
                                    color = if (suggestion.bold) colors.warnLabel else colors.labelTertiary,
                                )
                            }
                        }
                        Text(
                            suggestion.text,
                            style = DsType.small13,
                            color = colors.labelSecondary,
                        )
                    }
                }
            }
        }
    }
    if (showModelPicker) {
        DsBottomSheet(title = stringResource(R.string.models_title), onDismiss = { showModelPicker = false }) {
            state.modelProfiles.forEach { profile ->
                DsButton(
                    text = "${profile.model}  ·  ${profile.baseUrl.substringAfter("://").substringBefore('/')}" ,
                    onClick = {
                        onSelectModel(profile.id)
                        showModelPicker = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                    variant = if (profile.model == state.model && profile.baseUrl == state.baseUrl)
                        DsButtonVariant.Info else DsButtonVariant.Ghost,
                )
            }
            DsButton(
                text = stringResource(R.string.local_manage_model_config),
                onClick = {
                    showModelPicker = false
                    onConfigure()
                },
                modifier = Modifier.fillMaxWidth(),
                variant = DsButtonVariant.Outline,
            )
        }
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
private fun ImportedAttachmentRow(
    attachment: LocalImportedAttachment,
    workspacePath: String,
    onRemove: () -> Unit,
) {
    val colors = DsTheme.colors
    var thumbnail by remember(attachment.relativePath, workspacePath) {
        mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null)
    }
    LaunchedEffect(attachment.relativePath, workspacePath) {
        thumbnail = if (attachment.mediaType.startsWith("image/")) {
            withContext(Dispatchers.IO) {
                decodeLocalAttachmentThumbnail(workspacePath, attachment.relativePath)
            }
        } else {
            null
        }
    }
    DsCard {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            thumbnail?.let { image ->
                Image(
                    bitmap = image,
                    contentDescription = null,
                    modifier = Modifier.size(52.dp).clip(DsShapes.block),
                )
            }
            Column(Modifier.weight(1f)) {
                Text(attachment.name, style = DsType.small13Strong, color = colors.labelPrimary)
                val dimensions = if (attachment.width != null && attachment.height != null) {
                    " · ${attachment.width}×${attachment.height}"
                } else {
                    ""
                }
                Text(
                    "${attachment.mediaType}$dimensions · ${attachment.bytes} B",
                    style = DsType.caption11,
                    color = colors.labelTertiary,
                )
            }
            DsButton(stringResource(R.string.common_remove), onRemove, variant = DsButtonVariant.Ghost, size = DsButtonSize.Small)
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
        Text(stringResource(R.string.local_welcome_title), style = DsType.display24, color = colors.labelPrimary)
        Text(
            stringResource(R.string.local_welcome_hint),
            style = DsType.std14,
            color = colors.labelSecondary,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Text(stringResource(R.string.local_suggestions_title), style = DsType.small13Strong, color = colors.labelTertiary)
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

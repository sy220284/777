package com.labteto.dshmobile.ui.screens.local

import android.graphics.BitmapFactory
import java.io.File
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PersonSearch
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.ui.window.Dialog
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.DeepSeekUsageSnapshot
import com.labteto.dshmobile.local.LocalChatBranchInfo
import com.labteto.dshmobile.local.LocalHarnessMessage
import com.labteto.dshmobile.local.LocalHarnessState
import com.labteto.dshmobile.local.chatBranchInfo
import com.labteto.dshmobile.local.chatBranchingEligible
import com.labteto.dshmobile.local.chatMessageHasAttachmentContext
import com.labteto.dshmobile.local.LocalImportedAttachment
import com.labteto.dshmobile.local.LocalImageInputMode
import com.labteto.dshmobile.local.LocalSessionSummary
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.chat.PersonaGalleryEntry
import com.labteto.dshmobile.local.chat.PersonaProfile
import com.labteto.dshmobile.ui.agentOperationKind
import com.labteto.dshmobile.ui.agentOperationLabelRes
import com.labteto.dshmobile.ui.agentOperationStatusRes
import com.labteto.dshmobile.ui.components.DsBottomSheet
import com.labteto.dshmobile.ui.components.AppBrandIcon
import com.labteto.dshmobile.ui.components.ConversationScrollShortcut
import com.labteto.dshmobile.ui.components.ConversationScrollTarget
import com.labteto.dshmobile.ui.components.rememberConversationScrollHint
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonSize
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.components.DsCard
import com.labteto.dshmobile.ui.components.DsCategoryRow
import com.labteto.dshmobile.ui.components.DsGroupCard
import com.labteto.dshmobile.ui.components.DsIconButton
import com.labteto.dshmobile.ui.components.DsQuickActionTile
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.components.MarkdownText
import com.labteto.dshmobile.ui.components.StateDot
import com.labteto.dshmobile.ui.components.StateDotState
import com.labteto.dshmobile.ui.components.UserBubble
import com.labteto.dshmobile.ui.components.WhaleMark
import com.labteto.dshmobile.ui.theme.BackgroundRegion
import com.labteto.dshmobile.ui.theme.DsMetrics
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.LocalAppBackgroundState
import com.labteto.dshmobile.ui.theme.WallpaperSurfaceLevel
import com.labteto.dshmobile.ui.theme.wallpaperSurface
import java.util.Locale
import kotlin.math.roundToInt
import com.labteto.dshmobile.ui.theme.rootSurface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Default Android 16 home: local Harness first, remote transports live in the left drawer. */
@Composable
fun LocalHarnessScreen(
    requestedSessionId: String? = null,
    onSessionRequestConsumed: () -> Unit = {},
    onOpenRemote: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenTasks: () -> Unit,
    onOpenTools: () -> Unit,
    onCheckUpdate: () -> Unit,
    updateStatus: String?,
    viewModel: LocalHarnessViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val gallery by viewModel.gallery.collectAsStateWithLifecycle()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val modeIntroPreferences = remember(context) {
        context.getSharedPreferences("local_mode_intro", android.content.Context.MODE_PRIVATE)
    }
    var showNewSessionMode by rememberSaveable { mutableStateOf(false) }
    var filesMode by remember { mutableStateOf<LocalFilesMode?>(null) }
    var showPersonaGallery by rememberSaveable { mutableStateOf(false) }
    var showPersonaGallerySavePrompt by rememberSaveable { mutableStateOf(false) }
    var showRunCenter by rememberSaveable { mutableStateOf(false) }
    var modeIntro by remember { mutableStateOf<LocalUsageMode?>(null) }

    LaunchedEffect(modeIntro) {
        if (modeIntro != null) {
            delay(6_000)
            modeIntro = null
        }
    }

    fun switchUsageMode(target: LocalUsageMode) {
        if (target == state.usageMode) return
        val key = "shown_" + target.name.lowercase()
        if (!modeIntroPreferences.getBoolean(key, false)) {
            modeIntroPreferences.edit().putBoolean(key, true).apply()
            modeIntro = target
        }
        viewModel.switchUsageMode(target)
    }

    LaunchedEffect(requestedSessionId, state.sessions) {
        val target = requestedSessionId?.takeIf(String::isNotBlank) ?: return@LaunchedEffect
        if (target == state.sessionId || state.sessions.any { it.id == target }) {
            if (target != state.sessionId) viewModel.switchSession(target)
            onSessionRequestConsumed()
        }
    }

    BackHandler(enabled = drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }
    val pendingApprovalCallId = state.pendingApproval?.callId
    val pendingQuestionCallId = state.pendingQuestion?.callId
    BackHandler(enabled = pendingApprovalCallId != null) {
        pendingApprovalCallId?.let(viewModel::deny)
    }
    BackHandler(enabled = pendingQuestionCallId != null) {
        pendingQuestionCallId?.let(viewModel::cancelQuestion)
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            LocalModeDrawer(
                currentSessionId = state.sessionId,
                sessions = state.sessions,
                usageMode = state.usageMode,
                modeSwitchEnabled = !state.running,
                onUsageModeChange = ::switchUsageMode,
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
                galleryCount = gallery.size,
                groupMemberCount = state.groupChat.members.size,
                onOpenGroupChat = {
                    scope.launch { drawerState.close() }
                    if (!state.groupChat.enabled) viewModel.createGroupChatSession()
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
                onCheckUpdate = onCheckUpdate,
                updateStatus = updateStatus,
            )
        },
    ) {
        when {
            state.loading -> LoadingScreen()
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
                onDismiss = { showPersonaGallery = false },
            )
            else -> LocalChat(
                state = state,
                gallery = gallery,
                modeIntro = modeIntro,
                onOpenMenu = { scope.launch { drawerState.open() } },
                onConfigure = onOpenSettings,
                onSelectModel = viewModel::selectModel,
                onSend = viewModel::send,
                onEditAndResend = viewModel::editAndResendUserMessage,
                onSelectMessageVariant = viewModel::selectChatMessageVariant,
                onRegenerate = viewModel::regenerateReply,
                onImportAttachment = viewModel::importAttachment,
                onImageModeChange = viewModel::setImageInputMode,
                onStop = viewModel::stop,
                onNewSession = { showNewSessionMode = true },
                onOpenRunCenter = { showRunCenter = true },
                onConfigureChatPersona = viewModel::configureChatPersona,
                onConfigureGroupMembers = viewModel::configureGroupChatMembers,
                onSelectGalleryPersona = viewModel::selectGalleryPersonaForCurrentChat,
                onCreateChatPersona = viewModel::createPersonaForCurrentChat,
                onAutoFillChatPersona = viewModel::autoFillChatPersona,
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

    if (showNewSessionMode) {
        NewSessionModeDialog(
            onDismiss = { showNewSessionMode = false },
            onSelect = { mode ->
                showNewSessionMode = false
                viewModel.createSession(mode)
            },
        )
    }

    filesMode?.let { mode ->
        LocalWorkspaceFilesDialog(
            mode = mode,
            sessionId = state.sessionId,
            workspacePath = state.workspacePath,
            loadWorkspace = viewModel::workspaceFiles,
            loadConversation = viewModel::conversationFiles,
            loadPreview = viewModel::previewWorkspaceFile,
            onDismiss = { filesMode = null },
        )
    }

    if (showRunCenter && state.usageMode == LocalUsageMode.WORK) {
        Dialog(onDismissRequest = { showRunCenter = false }) {
            ExecutionStatusCard(
                state = state,
                onJobOutput = viewModel::backgroundJobOutput,
                onStopJob = viewModel::stopBackgroundJob,
            )
        }
    }

    if (showPersonaGallerySavePrompt && state.usageMode == LocalUsageMode.CHAT) {
        PersonaGallerySavePromptDialog(
            persona = state.chatPersona,
            isUpdate = viewModel.currentGalleryNeedsUpdate(),
            canSave = !state.loading && !state.running,
            onSaveCurrent = {
                viewModel.saveCurrentToGallery(
                    notes = "",
                    existingId = state.galleryId,
                    existingStoryId = state.galleryStoryId,
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
private fun LocalModeDrawer(
    currentSessionId: String,
    sessions: List<LocalSessionSummary>,
    usageMode: LocalUsageMode,
    modeSwitchEnabled: Boolean,
    onUsageModeChange: (LocalUsageMode) -> Unit,
    onNewSession: () -> Unit,
    onRemote: () -> Unit,
    onSwitchSession: (String) -> Unit,
    onDeleteSessions: (Set<String>) -> Unit,
    onWorkspaceFiles: () -> Unit,
    onOpenRunCenter: () -> Unit,
    galleryCount: Int,
    groupMemberCount: Int,
    onOpenGroupChat: () -> Unit,
    onOpenPersonaGallery: () -> Unit,
    onTasks: () -> Unit,
    onTools: () -> Unit,
    onSettings: () -> Unit,
    onCheckUpdate: () -> Unit,
    updateStatus: String?,
) {
    val colors = DsTheme.colors
    var historyQuery by rememberSaveable { mutableStateOf("") }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var selectionOpen by remember { mutableStateOf(false) }
    val selectedIds = remember { mutableStateListOf<String>() }
    val searchFocusRequester = remember { FocusRequester() }
    val visibleSessions = remember(sessions, usageMode) {
        sessions.filter { !it.blank && it.usageMode == usageMode }
            .sortedByDescending(LocalSessionSummary::updatedAt)
    }
    val filteredSessions = remember(visibleSessions, historyQuery) {
        val query = historyQuery.trim()
        if (query.isEmpty()) visibleSessions else visibleSessions.filter {
            it.title.contains(query, ignoreCase = true)
        }
    }

    LaunchedEffect(searchOpen) {
        if (searchOpen) searchFocusRequester.requestFocus()
    }

    ModalDrawerSheet(
        drawerContainerColor = colors.wallpaperSurface(WallpaperSurfaceLevel.DIALOG, base = colors.sidebar),
        modifier = Modifier.fillMaxHeight(),
    ) {
        Column(Modifier.fillMaxHeight().safeDrawingPadding()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = DsSpacing.medium, end = DsSpacing.medium, top = DsSpacing.large),
                verticalArrangement = Arrangement.spacedBy(DsSpacing.xsmall),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = DsSpacing.small),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppBrandIcon(Modifier.size(44.dp))
                    Spacer(Modifier.size(DsSpacing.medium))
                    Text(
                        stringResource(R.string.app_name),
                        style = DsType.large20,
                        color = colors.labelPrimary,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    DsIconButton(
                        icon = Icons.Outlined.Search,
                        contentDescription = stringResource(R.string.chatlist_search_hint),
                        onClick = {
                            searchOpen = !searchOpen
                            if (!searchOpen) historyQuery = ""
                        },
                        tint = if (searchOpen) colors.accent else colors.labelPrimary,
                    )
                    DsIconButton(
                        icon = Icons.Filled.Add,
                        contentDescription = stringResource(R.string.chatlist_new_session),
                        onClick = onNewSession,
                        tint = colors.labelPrimary,
                    )
                }
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    LocalUsageModePill(
                        selected = usageMode,
                        enabled = modeSwitchEnabled,
                        onSelect = onUsageModeChange,
                    )
                }

                if (searchOpen) {
                    OutlinedTextField(
                        value = historyQuery,
                        onValueChange = { historyQuery = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(searchFocusRequester)
                            .padding(bottom = DsSpacing.small),
                        placeholder = { Text(stringResource(R.string.chatlist_search_hint)) },
                        leadingIcon = {
                            Icon(
                                Icons.Outlined.Search,
                                contentDescription = null,
                                tint = colors.labelTertiary,
                            )
                        },
                        singleLine = true,
                        shape = DsShapes.block,
                    )
                }

                if (usageMode == LocalUsageMode.CHAT) {
                    DrawerPrimaryAction(
                        icon = Icons.Outlined.PersonSearch,
                        title = stringResource(R.string.local_group_chat),
                        trailing = groupMemberCount.takeIf { it > 0 }?.toString(),
                        onClick = onOpenGroupChat,
                    )
                    DrawerPrimaryAction(
                        icon = Icons.Outlined.Image,
                        title = stringResource(R.string.persona_gallery_title),
                        trailing = galleryCount.toString(),
                        onClick = onOpenPersonaGallery,
                    )
                } else {
                    DrawerPrimaryAction(
                        icon = FeatherIcons.FileText,
                        title = stringResource(R.string.chatlist_workspace_files),
                        onClick = onWorkspaceFiles,
                    )
                    DrawerPrimaryAction(
                        icon = FeatherIcons.CheckSquare,
                        title = stringResource(R.string.local_run_center),
                        onClick = onOpenRunCenter,
                    )
                    DrawerPrimaryAction(
                        icon = Icons.Outlined.Schedule,
                        title = stringResource(R.string.tasks_title),
                        onClick = onTasks,
                    )
                    DrawerPrimaryAction(
                        icon = Icons.Outlined.Extension,
                        title = stringResource(R.string.tools_title),
                        onClick = onTools,
                    )
                }
                DrawerSectionTitle(
                    stringResource(
                        if (usageMode == LocalUsageMode.CHAT) R.string.chatlist_title
                        else R.string.local_work_history_title,
                    ),
                )
            }

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = DsSpacing.medium),
                contentPadding = PaddingValues(bottom = DsSpacing.medium),
                verticalArrangement = Arrangement.spacedBy(DsSpacing.xsmall),
            ) {
                if (visibleSessions.isNotEmpty() && filteredSessions.isEmpty()) {
                    item(key = "drawer-no-sessions") {
                        Text(
                            stringResource(R.string.chatlist_search_empty),
                            style = DsType.small13,
                            color = colors.labelTertiary,
                            modifier = Modifier.padding(horizontal = DsSpacing.small, vertical = DsSpacing.small),
                        )
                    }
                }
                items(filteredSessions, key = { "session:${it.id}" }) { session ->
                    LocalSessionDrawerRow(
                        title = session.title,
                        current = session.id == currentSessionId,
                        selected = session.id in selectedIds,
                        selectionOpen = selectionOpen,
                        onClick = {
                            if (selectionOpen) {
                                if (session.id in selectedIds) selectedIds.remove(session.id)
                                else selectedIds.add(session.id)
                            } else onSwitchSession(session.id)
                        },
                        onLongClick = {
                            if (session.id !in selectedIds) selectedIds.add(session.id)
                            selectionOpen = true
                        },
                        onDelete = { onDeleteSessions(setOf(session.id)) },
                    )
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = DsSpacing.medium, vertical = DsSpacing.small),
                verticalArrangement = Arrangement.spacedBy(DsSpacing.tiny),
            ) {
                DrawerPrimaryAction(
                    icon = Icons.Outlined.QrCodeScanner,
                    title = stringResource(R.string.local_remote_control),
                    onClick = onRemote,
                )
                DrawerPrimaryAction(
                    icon = Icons.Outlined.Settings,
                    title = stringResource(R.string.settings_title),
                    onClick = onSettings,
                )
                DrawerPrimaryAction(
                    icon = Icons.Outlined.CloudDownload,
                    title = stringResource(R.string.settings_update_check),
                    onClick = onCheckUpdate,
                )
                updateStatus?.let { status ->
                    Text(
                        status,
                        style = DsType.caption11,
                        color = colors.labelSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = DsSpacing.xxlarge, end = DsSpacing.small),
                    )
                }
            }
        }
    }

    if (selectionOpen) {
        var position by remember { mutableStateOf(IntOffset(24, 160)) }
        Popup(
            alignment = Alignment.TopStart,
            offset = position,
            properties = PopupProperties(focusable = false, clippingEnabled = true),
        ) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = colors.wallpaperSurface(WallpaperSurfaceLevel.MENU),
                shadowElevation = 10.dp,
                modifier = Modifier.pointerInput(Unit) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        position = IntOffset(
                            (position.x + drag.x.roundToInt()).coerceAtLeast(0),
                            (position.y + drag.y.roundToInt()).coerceAtLeast(0),
                        )
                    }
                },
            ) {
                Row(
                    Modifier.padding(horizontal = DsSpacing.medium, vertical = DsSpacing.small),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
                ) {
                    Text(stringResource(R.string.local_selected_count, selectedIds.size), color = colors.labelPrimary)
                    DsButton(
                        text = stringResource(R.string.local_delete_session),
                        onClick = {
                            val ids = selectedIds.toSet()
                            selectionOpen = false
                            selectedIds.clear()
                            if (ids.isNotEmpty()) onDeleteSessions(ids)
                        },
                        enabled = selectedIds.isNotEmpty(),
                        variant = DsButtonVariant.Ghost,
                        size = DsButtonSize.Small,
                    )
                    DsIconButton(
                        icon = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.common_close),
                        onClick = {
                            selectionOpen = false
                            selectedIds.clear()
                        },
                        tint = colors.labelSecondary,
                    )
                }
            }
        }
    }
}

@Composable
private fun DrawerPrimaryAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    onClick: () -> Unit,
    trailing: String? = null,
) {
    val colors = DsTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = DsSpacing.touchTarget)
            .clip(DsShapes.row)
            .clickable(onClick = onClick)
            .padding(horizontal = DsSpacing.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = colors.labelSecondary,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(DsSpacing.medium))
        Text(
            title,
            style = DsType.base16,
            color = colors.labelPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        trailing?.let {
            Text(
                it,
                style = DsType.caption11,
                color = colors.labelTertiary,
                modifier = Modifier.padding(start = DsSpacing.small),
            )
        }
    }
}

@Composable
private fun DrawerSectionTitle(title: String) {
    Text(
        title,
        style = DsType.std14,
        color = DsTheme.colors.labelTertiary,
        modifier = Modifier.padding(start = DsSpacing.small, top = DsSpacing.medium, bottom = DsSpacing.tiny),
    )
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun LocalSessionDrawerRow(
    title: String,
    current: Boolean,
    selected: Boolean,
    selectionOpen: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = DsTheme.colors
    val density = LocalDensity.current
    val reveal = with(density) { 76.dp.toPx() }
    var swipe by remember { mutableStateOf(0f) }
    Box(Modifier.fillMaxWidth()) {
        DsIconButton(
            icon = Icons.Outlined.DeleteOutline,
            contentDescription = stringResource(R.string.local_delete_session),
            onClick = { swipe = 0f; onDelete() },
            tint = colors.error,
            modifier = Modifier.align(Alignment.CenterStart),
        )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .offset { IntOffset(swipe.roundToInt(), 0) }
            .pointerInput(reveal, selectionOpen) {
                detectHorizontalDragGestures(
                    onHorizontalDrag = { change, amount ->
                        change.consume()
                        swipe = (swipe + amount).coerceIn(0f, reveal)
                    },
                    onDragEnd = { swipe = if (swipe > reveal / 2) reveal else 0f },
                )
            }
            .heightIn(min = DsSpacing.touchTarget)
            .clip(DsShapes.row)
            .background(if (current || selected) colors.sidebarNavActive else colors.sidebar)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = DsSpacing.medium, vertical = DsSpacing.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectionOpen) Checkbox(checked = selected, onCheckedChange = { onClick() })
        Text(
            title,
            style = DsType.std14,
            color = colors.labelPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (current) {
            Spacer(Modifier.width(DsSpacing.small))
            Text("当前", style = DsType.caption11, color = colors.accent)
        }
    }
    }
}

@Composable
private fun LocalUsageFooter(usage: DeepSeekUsageSnapshot) {
    val colors = DsTheme.colors
    Column(
        modifier = Modifier.padding(
            start = DsSpacing.medium,
            end = DsSpacing.medium,
            bottom = DsSpacing.medium,
        ),
        verticalArrangement = Arrangement.spacedBy(DsSpacing.tiny),
    ) {
        Text(
            stringResource(R.string.local_usage_title),
            style = DsType.caption11,
            color = colors.labelTertiary,
        )
        DsCard(verticalArrangement = Arrangement.spacedBy(DsSpacing.tiny)) {
            UsageStatRow(
                label = stringResource(R.string.local_usage_tokens),
                value = formatTokenCount(usage.totalTokens),
            )
            UsageStatRow(
                label = stringResource(R.string.local_usage_cache_hit_rate),
                value = String.format(Locale.US, "%.1f%%", usage.cacheHitRate * 100.0),
            )
            UsageStatRow(
                label = stringResource(R.string.local_usage_estimated_cost),
                value = formatCny(usage.estimatedCostCny),
            )
            Text(
                stringResource(
                    R.string.local_usage_breakdown,
                    formatTokenCount(usage.inputTokens),
                    formatTokenCount(usage.outputTokens),
                    usage.totalRequestCount,
                ),
                style = DsType.caption11,
                color = colors.labelTertiary,
            )
            if (usage.unreportedRequestCount > 0L) {
                Text(
                    stringResource(R.string.local_usage_unreported, usage.unreportedRequestCount),
                    style = DsType.caption11,
                    color = colors.warnLabel,
                )
            }
            if (usage.unpricedTokens > 0L) {
                Text(
                    stringResource(R.string.local_usage_unpriced, formatTokenCount(usage.unpricedTokens)),
                    style = DsType.caption11,
                    color = colors.labelTertiary,
                )
            }
        }
    }
}

@Composable
private fun UsageStatRow(label: String, value: String) {
    val colors = DsTheme.colors
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = DsType.small13, color = colors.labelSecondary)
        Text(value, style = DsType.small13Strong, color = colors.labelPrimary)
    }
}

private fun formatTokenCount(value: Long): String = when {
    value >= 100_000_000L -> String.format(Locale.US, "%.2f亿", value / 100_000_000.0)
    value >= 10_000L -> String.format(Locale.US, "%.1f万", value / 10_000.0)
    else -> value.toString()
}

private fun formatCny(value: Double): String =
    if (value >= 1.0) String.format(Locale.US, "¥%.2f", value)
    else String.format(Locale.US, "¥%.4f", value)

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
    state: LocalHarnessState,
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
                contentDescription = "菜单",
                onClick = onOpenMenu,
                containerColor = colors.wallpaperSurface(WallpaperSurfaceLevel.FLOATING, BackgroundRegion.TOP),
                shadowElevation = 3.dp,
            )
            Text(
                "本机 Harness",
                style = DsType.large20,
                color = colors.labelPrimary,
                modifier = Modifier.weight(1f),
            )
            if (canCancel) {
                DsButton("取消", onCancel, variant = DsButtonVariant.Ghost)
            } else {
                Spacer(Modifier.size(56.dp))
            }
        }

        DsCard(verticalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
            Text("手机直接执行", style = DsType.base16Strong, color = colors.labelPrimary)
            Text(
                "模型、文件、网页、命令、技能和子代理都在手机侧组织执行。远程控制入口已统一放到侧边栏。",
                style = DsType.std14,
                color = colors.labelSecondary,
            )
        }

        Text("模型与接口", style = DsType.std14, color = colors.labelTertiary)
        DsGroupCard {
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(if (state.configured) "新的 API 密钥" else "DeepSeek API 密钥") },
                supportingText = {
                    Text(
                        if (state.configured) "留空可保留现有密钥；新密钥仍由安卓系统密钥库加密。"
                        else "密钥由安卓系统密钥库加密，不写入会话或工作区。",
                    )
                },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
            )
            Spacer(Modifier.height(DsSpacing.medium))
            Column(verticalArrangement = Arrangement.spacedBy(DsSpacing.tiny)) {
                Text("模型", style = DsType.std14Strong, color = colors.labelPrimary)
                ModelChoice("deepseek-flash", "DeepSeek Flash｜V4.1 · 思考默认开启", model) { model = it }
                ModelChoice("deepseek-v4-pro", "DeepSeek V4 Pro｜专家模型", model) { model = it }
            }
            Spacer(Modifier.height(DsSpacing.medium))
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("接口地址") },
                supportingText = { Text("兼容 OpenAI 聊天补全协议的服务也可使用。") },
                singleLine = true,
            )
        }

        state.error?.let { Text(it, style = DsType.small13, color = colors.error) }

        DsButton(
            text = "保存并进入本机 Harness",
            onClick = { onSave(apiKey, model, baseUrl) },
            modifier = Modifier.fillMaxWidth(),
            enabled = (state.configured || apiKey.isNotBlank()) && baseUrl.isNotBlank(),
        )
        if (state.configured) {
            DsButton(
                text = "清除本机模型密钥",
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
private fun LocalChat(
    state: LocalHarnessState,
    gallery: List<PersonaGalleryEntry>,
    modeIntro: LocalUsageMode?,
    onOpenMenu: () -> Unit,
    onConfigure: () -> Unit,
    onSelectModel: (String) -> Unit,
    onSend: (String, List<LocalImportedAttachment>) -> Unit,
    onEditAndResend: (String, String) -> Boolean,
    onSelectMessageVariant: (String, Int) -> Boolean,
    onRegenerate: (String) -> Boolean,
    onImportAttachment: suspend (android.net.Uri) -> LocalImportedAttachment,
    onImageModeChange: (LocalImageInputMode) -> Unit,
    onStop: () -> Unit,
    onNewSession: () -> Unit,
    onOpenRunCenter: () -> Unit,
    onConfigureChatPersona: (PersonaProfile) -> Unit,
    onConfigureGroupMembers: (List<String>) -> Boolean,
    onSelectGalleryPersona: (String) -> Boolean,
    onCreateChatPersona: (PersonaProfile) -> Boolean,
    onAutoFillChatPersona: suspend (String) -> Result<PersonaProfile>,
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
    val headerChipColor = if (backgroundState.hasImage) Color.Transparent else colors.bgModulePlatform
    val streamingSurfaceColor = backgroundState.wallpaperSurface(
        base = colors.bgModulePlatform,
        level = WallpaperSurfaceLevel.CARD,
        region = BackgroundRegion.MIDDLE,
    )
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
    var showImageModePicker by rememberSaveable { mutableStateOf(false) }
    var approvalNoticeExpanded by rememberSaveable { mutableStateOf(false) }
    var showModelPicker by rememberSaveable { mutableStateOf(false) }
    var showPersonaPicker by rememberSaveable { mutableStateOf(false) }
    var showGroupMemberPicker by rememberSaveable { mutableStateOf(false) }
    var showPersonaEditor by rememberSaveable { mutableStateOf(false) }
    var creatingPersona by rememberSaveable { mutableStateOf(false) }
    var personaEditorDraft by remember { mutableStateOf<PersonaProfile?>(null) }
    var showReplySuggestions by rememberSaveable { mutableStateOf(false) }
    var editingUserMessage by remember { mutableStateOf<LocalHarnessMessage?>(null) }
    var editingUserText by rememberSaveable { mutableStateOf("") }
    var editingUserError by remember { mutableStateOf<String?>(null) }
    val attachments = remember { mutableStateListOf<LocalImportedAttachment>() }
    val listState = rememberLazyListState()
    val (scrollHint, scrollConnection) = rememberConversationScrollHint(listState, reverseLayout = false)
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val transcriptItems = remember(state.messages) { buildLocalTranscript(state.messages) }
    val messageBranchingEnabled = state.usageMode == LocalUsageMode.CHAT &&
        !state.groupChat.enabled &&
        chatBranchingEligible(state.messages)
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
        editingUserText = ""
        editingUserError = null
    }

    val imageLimitMessage = stringResource(R.string.local_image_selection_limit, MAX_LOCAL_IMAGE_SELECTION)
    val imageImportFailedMessage = stringResource(R.string.local_image_import_failed)
    val editUserMessageFailed = stringResource(R.string.local_edit_user_message_failed)
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
                    .onFailure { attachmentError = it.message ?: "文件导入失败" }
            }
        }
    }

    LaunchedEffect(state.sessionId) {
        scrollHint.hide()
        attachments.clear()
        attachmentError = null
        showReplySuggestions = false
        if (transcriptItems.isNotEmpty()) {
            listState.scrollToItem(transcriptItems.lastIndex)
        }
    }

    LaunchedEffect(state.messages.size, transcriptItems.size) {
        if (transcriptItems.isNotEmpty()) {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            if (lastVisible >= transcriptItems.lastIndex - 2) {
                listState.animateScrollToItem(transcriptItems.lastIndex)
            }
        }
    }

    Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().background(rootSurfaceColor)) {
        Column(
            Modifier.fillMaxWidth().background(topSurfaceColor)
                .padding(horizontal = DsMetrics.screenHorizontal, vertical = DsSpacing.small),
        ) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = DsMetrics.topBarHeight),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DsIconButton(
                    icon = FeatherIcons.Menu,
                    contentDescription = stringResource(R.string.local_open_menu),
                    onClick = onOpenMenu,
                    tint = colors.labelSecondary,
                )
                Spacer(Modifier.width(DsSpacing.small))
                Row(
                    modifier = Modifier.weight(1f)
                        .heightIn(min = DsSpacing.touchTarget)
                        .clip(DsShapes.row)
                        .background(if (state.usageMode == LocalUsageMode.CHAT) Color.Transparent else headerChipColor)
                        .clickable(enabled = !state.running) {
                            if (state.usageMode == LocalUsageMode.CHAT) {
                                if (state.groupChat.enabled) showGroupMemberPicker = true
                                else showPersonaPicker = true
                            } else if (state.configured) {
                                showModelPicker = true
                            } else {
                                onConfigure()
                            }
                        }
                        .padding(horizontal = DsSpacing.small),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(DsSpacing.xsmall),
                ) {
                    if (state.usageMode == LocalUsageMode.CHAT) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text(
                                if (state.groupChat.enabled) {
                                    stringResource(R.string.local_group_chat_title)
                                } else {
                                    state.chatPersona.name
                                },
                                style = DsType.base16Strong,
                                color = colors.labelPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (state.groupChat.enabled) {
                                Text(
                                    state.groupActiveSpeakerName?.let { speaker ->
                                        stringResource(R.string.local_group_chat_active_speaker, speaker)
                                    } ?: stringResource(
                                        R.string.local_group_chat_member_count,
                                        state.groupChat.members.size,
                                    ),
                                    style = DsType.caption11,
                                    color = colors.labelSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            } else {
                                state.chatState.relationshipState.takeIf { it.isNotBlank() }?.let { relationship ->
                                    Text(
                                        relationship,
                                        style = DsType.caption11,
                                        color = colors.labelSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                        Icon(
                            Icons.Filled.KeyboardArrowDown,
                            contentDescription = null,
                            tint = colors.labelSecondary,
                            modifier = Modifier.size(16.dp),
                        )
                    } else {
                        Icon(
                            Icons.Outlined.Tune,
                            contentDescription = null,
                            tint = colors.labelSecondary,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            if (state.configured) state.model else stringResource(R.string.local_model_setup),
                            style = DsType.small13Strong,
                            color = colors.labelPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            Icons.Filled.KeyboardArrowDown,
                            contentDescription = null,
                            tint = colors.labelSecondary,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
                if (state.usageMode == LocalUsageMode.WORK) {
                    DsIconButton(
                        icon = FeatherIcons.CheckSquare,
                        contentDescription = stringResource(R.string.local_execution_console),
                        onClick = onOpenRunCenter,
                        tint = colors.labelSecondary,
                    )
                }
                DsIconButton(
                    icon = Icons.Filled.Add,
                    contentDescription = stringResource(R.string.chatlist_new_session),
                    onClick = onNewSession,
                    tint = colors.labelSecondary,
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

        state.personaCorrectionNotice?.takeIf {
            state.usageMode == LocalUsageMode.CHAT
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
                        "关闭",
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
                if (transcriptItems.isEmpty() && state.usageMode == LocalUsageMode.WORK) {
                    item {
                        Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                            EmptyLocalHarness { suggestion ->
                                drafts[state.sessionId] = suggestion
                            }
                        }
                    }
                }
                items(transcriptItems, key = { it.key }) { transcriptItem ->
                    when (transcriptItem) {
                        is LocalTranscriptItem.Message -> LocalMessageRow(
                            message = transcriptItem.message,
                            chatMode = state.usageMode == LocalUsageMode.CHAT,
                            canEdit = messageBranchingEnabled &&
                                !state.running &&
                                !chatMessageHasAttachmentContext(transcriptItem.message),
                            canRegenerate = !state.running && state.messages.lastOrNull()?.id == transcriptItem.message.id,
                            branchInfo = if (messageBranchingEnabled) {
                                chatBranchInfo(state.chatBranches, transcriptItem.message.id)
                            } else {
                                null
                            },
                            onEdit = { message ->
                                editingUserMessage = message
                                editingUserText = message.content
                                editingUserError = null
                            },
                            onSelectVariant = onSelectMessageVariant,
                            onRegenerate = onRegenerate,
                        )
                        is LocalTranscriptItem.WorkProcess -> WorkProcessRow(transcriptItem.messages)
                    }
                }
                if (state.running) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(DsSpacing.small))
                                Text(
                                    stringResource(R.string.local_streaming_status),
                                    style = DsType.small13,
                                    color = colors.labelTertiary,
                                )
                            }
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

        if (state.running && state.streamingAssistant.isNotBlank()) {
            Surface(
                modifier = Modifier.fillMaxWidth()
                    .padding(horizontal = DsSpacing.medium, vertical = DsSpacing.small),
                shape = RoundedCornerShape(18.dp),
                color = streamingSurfaceColor,
            ) {
                Column(
                    Modifier.padding(DsSpacing.medium),
                    verticalArrangement = Arrangement.spacedBy(DsSpacing.tiny),
                ) {
                    Text(
                        stringResource(R.string.local_streaming_status),
                        style = DsType.caption11,
                        color = colors.labelTertiary,
                    )
                    val preview = state.streamingAssistant
                    Text(
                        if (preview.length > 1_200) "…" + preview.takeLast(1_200) else preview,
                        style = DsType.std14,
                        color = colors.labelPrimary,
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
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
                            "恢复请求",
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

        Surface(
            modifier = Modifier.fillMaxWidth()
                .padding(horizontal = DsSpacing.medium, vertical = DsSpacing.small),
            shape = DsShapes.composer,
            color = composerSurfaceColor,
            shadowElevation = if (backgroundState.hasImage) 0.dp else 1.dp,
        ) {
            Column(
                Modifier.padding(horizontal = DsSpacing.medium, vertical = DsSpacing.tiny),
                verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
            ) {
                if (attachments.isNotEmpty()) {
                    attachments.forEachIndexed { index, attachment ->
                        ImportedAttachmentRow(
                            attachment = attachment,
                            workspacePath = state.workspacePath,
                            onRemove = { attachments.removeAt(index) },
                        )
                    }
                    if (attachments.any { it.mediaType.startsWith("image/") }) {
                        val imageModeLabel = stringResource(
                            when (state.imageInputMode) {
                                LocalImageInputMode.AUTO -> R.string.advanced_image_mode_auto
                                LocalImageInputMode.NATIVE -> R.string.advanced_image_mode_native
                                LocalImageInputMode.TOOL -> R.string.advanced_image_mode_tool
                            },
                        )
                        DsButton(
                            text = stringResource(R.string.local_image_mode_status, imageModeLabel),
                            onClick = { showImageModePicker = true },
                            variant = DsButtonVariant.Ghost,
                            size = DsButtonSize.Small,
                        )
                    }
                }
                OutlinedTextField(
                    value = input,
                    onValueChange = { drafts[state.sessionId] = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text(
                            if (state.usageMode == LocalUsageMode.CHAT) stringResource(R.string.local_chat_composer_hint)
                            else "问点什么，或直接交给 Harness 执行…",
                        )
                    },
                    shape = DsShapes.block,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                        disabledBorderColor = Color.Transparent,
                    ),
                    minLines = 1,
                    maxLines = 5,
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DsIconButton(
                        icon = Icons.Filled.Add,
                        contentDescription = "添加附件",
                        onClick = { showAttachmentPicker = true },
                        enabled = !state.running,
                        tint = colors.labelPrimary,
                        containerColor = colors.wallpaperSurface(WallpaperSurfaceLevel.FLOATING, BackgroundRegion.BOTTOM),
                    )
                    if (
                        state.usageMode == LocalUsageMode.CHAT &&
                        state.replySuggestions.any { it.text.isNotBlank() }
                    ) {
                        DsButton(
                            text = stringResource(R.string.local_reply_suggestions_open),
                            onClick = { showReplySuggestions = true },
                            variant = DsButtonVariant.Ghost,
                            size = DsButtonSize.Small,
                        )
                    }
                    if (state.usageMode == LocalUsageMode.WORK) {
                        DsButton(
                            if (state.planMode) "规划中" else "规划",
                            { onPlanModeChange(!state.planMode) },
                            variant = if (state.planMode) DsButtonVariant.Info else DsButtonVariant.Ghost,
                            size = DsButtonSize.Small,
                            enabled = !state.running,
                        )
                        DsButton(
                            "自动批准",
                            if (state.safeAutoApprovalEnabled) onDisableAutoApprove else onAutoApprove,
                            variant = if (state.safeAutoApprovalEnabled) DsButtonVariant.Info else DsButtonVariant.Ghost,
                            size = DsButtonSize.Small,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    if (!state.running) {
                        DsButton(
                            "发送",
                            onClick = {
                                if (!state.configured) {
                                    onConfigure()
                                } else {
                                    val selected = attachments.toList()
                                    onSend(input, selected)
                                    drafts[state.sessionId] = ""
                                    attachments.clear()
                                    focusManager.clearFocus()
                                    keyboardController?.hide()
                                }
                            },
                            enabled = input.isNotBlank() || attachments.isNotEmpty(),
                        )
                    }
                }
                if (state.running) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(DsSpacing.small, Alignment.End),
                    ) {
                        DsButton(
                            "停止",
                            onStop,
                            variant = DsButtonVariant.Danger,
                            size = DsButtonSize.Small,
                        )
                        DsButton(
                            stringResource(R.string.local_queue_message),
                            onClick = {
                                val selected = attachments.toList()
                                onSend(input, selected)
                                drafts[state.sessionId] = ""
                                attachments.clear()
                                focusManager.clearFocus()
                                keyboardController?.hide()
                            },
                            size = DsButtonSize.Small,
                            enabled = input.isNotBlank() || attachments.isNotEmpty(),
                        )
                    }
                }
            }
        }
    }

    state.pendingApproval?.let { approval ->
        ApprovalDialog(
            approval = approval,
            safeAutoApprovalEnabled = state.safeAutoApprovalEnabled,
            onApprove = { onApprove(approval.callId) },
            onDeny = { onDeny(approval.callId) },
            onAutoApprove = { onAutoApprovePending(approval.callId) },
            onApproveDeviceTurn = { onApproveDeviceTurn(approval.callId) },
        )
    }
    state.pendingQuestion?.let { question ->
        QuestionDialog(
            question = question.question,
            options = question.options,
            onAnswer = { answer -> onAnswerQuestion(question.callId, answer) },
            onDismiss = { onCancelQuestion(question.callId) },
        )
    }
    if (showPersonaPicker && state.usageMode == LocalUsageMode.CHAT) {
        ChatPersonaPickerDialog(
            entries = gallery,
            currentPersona = state.chatPersona,
            currentGalleryId = state.galleryId,
            canSwitchPersona = !state.running &&
                state.messages.none { it.role == "user" || it.role == "assistant" },
            onSelect = onSelectGalleryPersona,
            onCreate = {
                showPersonaPicker = false
                creatingPersona = true
                personaEditorDraft = PersonaProfile(name = "")
                showPersonaEditor = true
            },
            onEditCurrent = {
                showPersonaPicker = false
                creatingPersona = false
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
                if (creatingPersona) {
                    onCreateChatPersona(profile)
                } else {
                    onConfigureChatPersona(profile)
                }
                personaEditorDraft = null
                creatingPersona = false
            },
            onAutoFill = onAutoFillChatPersona,
            onDismiss = {
                showPersonaEditor = false
                personaEditorDraft = null
                creatingPersona = false
            },
        )
    }
    editingUserMessage?.let { message ->
        DsBottomSheet(
            title = stringResource(R.string.local_edit_user_message),
            onDismiss = {
                editingUserMessage = null
                editingUserText = ""
                editingUserError = null
            },
        ) {
            Text(
                stringResource(R.string.local_edit_user_message_hint),
                style = DsType.small13,
                color = colors.labelSecondary,
            )
            OutlinedTextField(
                value = editingUserText,
                onValueChange = {
                    editingUserText = it
                    editingUserError = null
                },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 8,
            )
            editingUserError?.let { error ->
                Text(error, style = DsType.small13, color = colors.error)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                DsButton(
                    text = stringResource(R.string.common_cancel),
                    onClick = {
                        editingUserMessage = null
                        editingUserText = ""
                        editingUserError = null
                    },
                    variant = DsButtonVariant.Ghost,
                    size = DsButtonSize.Small,
                )
                DsButton(
                    text = stringResource(R.string.local_edit_user_message_resend),
                    onClick = {
                        if (onEditAndResend(message.id, editingUserText)) {
                            editingUserMessage = null
                            editingUserText = ""
                            editingUserError = null
                        } else {
                            editingUserError = editUserMessageFailed
                        }
                    },
                    enabled = editingUserText.trim().isNotEmpty() &&
                        editingUserText.trim() != message.content.trim() &&
                        !state.running,
                    size = DsButtonSize.Small,
                )
            }
        }
    }

    if (showReplySuggestions && state.usageMode == LocalUsageMode.CHAT &&
        state.replySuggestions.any { it.text.isNotBlank() }) {
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
        DsBottomSheet(title = "选择模型", onDismiss = { showModelPicker = false }) {
            state.configuredModels.forEach { model ->
                DsButton(
                    text = model,
                    onClick = {
                        onSelectModel(model)
                        showModelPicker = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                    variant = if (model == state.model) DsButtonVariant.Info else DsButtonVariant.Ghost,
                )
            }
            DsButton(
                text = "管理模型配置",
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
        DsBottomSheet(title = "添加附件", onDismiss = { showAttachmentPicker = false }) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(DsSpacing.medium),
            ) {
                DsQuickActionTile(
                    icon = Icons.Outlined.Image,
                    label = "图片",
                    onClick = {
                        showAttachmentPicker = false
                        imagePicker.launch(arrayOf("image/*"))
                    },
                    modifier = Modifier.weight(1f),
                )
                DsQuickActionTile(
                    icon = Icons.Outlined.AttachFile,
                    label = "文件",
                    onClick = {
                        showAttachmentPicker = false
                        filePicker.launch(arrayOf("*/*"))
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
    if (showImageModePicker) {
        DsBottomSheet(
            title = stringResource(R.string.advanced_image_input_mode),
            onDismiss = { showImageModePicker = false },
        ) {
            Text(
                stringResource(R.string.advanced_image_input_hint),
                style = DsType.caption11,
                color = colors.labelTertiary,
            )
            Column(verticalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
                listOf(
                    LocalImageInputMode.AUTO to R.string.advanced_image_mode_auto,
                    LocalImageInputMode.NATIVE to R.string.advanced_image_mode_native,
                    LocalImageInputMode.TOOL to R.string.advanced_image_mode_tool,
                ).forEach { (mode, label) ->
                    DsButton(
                        text = stringResource(label),
                        onClick = {
                            onImageModeChange(mode)
                            showImageModePicker = false
                        },
                        variant = if (state.imageInputMode == mode) DsButtonVariant.Info else DsButtonVariant.Ghost,
                        size = DsButtonSize.Small,
                    )
                }
            }
        }
    }

}

@Composable
private fun LocalUsageModePill(
    selected: LocalUsageMode,
    enabled: Boolean,
    onSelect: (LocalUsageMode) -> Unit,
) {
    val colors = DsTheme.colors
    val backgroundState = LocalAppBackgroundState.current
    val containerColor = if (backgroundState.hasImage && backgroundState.adaptiveContrast) {
        backgroundState.surfaceColor(
            base = colors.bgModulePlatform,
            region = BackgroundRegion.TOP,
            minAlpha = 0.28f,
            maxAlpha = 0.48f,
        )
    } else if (backgroundState.hasImage) {
        colors.bgModulePlatform.copy(alpha = 0.28f)
    } else {
        colors.bgLayer1
    }
    Surface(
        shape = DsShapes.pillFull,
        color = containerColor,
        tonalElevation = 0.dp,
        shadowElevation = if (backgroundState.hasImage) 1.dp else 0.dp,
    ) {
        Row(
            Modifier.padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            listOf(
                LocalUsageMode.CHAT to R.string.local_usage_chat,
                LocalUsageMode.WORK to R.string.local_usage_work,
            ).forEach { (mode, labelRes) ->
                val active = selected == mode
                Box(
                    modifier = Modifier
                        .heightIn(min = DsSpacing.touchTarget)
                        .clip(DsShapes.pillFull)
                        .background(if (active) colors.labelPrimary else Color.Transparent)
                        .clickable(enabled = enabled) { onSelect(mode) }
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        stringResource(labelRes),
                        style = DsType.small13Strong,
                        color = if (active) colors.bgBase else colors.labelSecondary,
                    )
                }
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
            DsButton("移除", onRemove, variant = DsButtonVariant.Ghost, size = DsButtonSize.Small)
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
        WhaleMark(Modifier.size(40.dp))
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

@Composable
private fun ExecutionStatusCard(
    state: LocalHarnessState,
    onJobOutput: (String) -> String,
    onStopJob: (String) -> String,
    modifier: Modifier = Modifier,
) {
    val colors = DsTheme.colors
    var expandedJobId by remember(state.sessionId) { mutableStateOf<String?>(null) }
    var expandedJobOutput by remember(state.sessionId) { mutableStateOf("") }
    val completed = state.todos.count { it.status == "completed" }
    val total = state.todos.size
    val resourceSummary = stringResource(
        R.string.local_resource_summary,
        state.activeAgents,
        state.maxAgents,
        state.activeTerminals,
        state.maxTerminals,
        state.activeVirtualDisplays,
        state.maxVirtualDisplays,
        state.activeLanguageServers,
        state.maxLanguageServers,
    )
    val contextSummary = stringResource(
        R.string.local_context_summary,
        state.contextChars,
        state.contextBudgetChars,
    )

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = DsShapes.block,
        color = colors.wallpaperSurface(WallpaperSurfaceLevel.CARD),
    ) {
        Column(
            Modifier.padding(DsSpacing.comfortable),
            verticalArrangement = Arrangement.spacedBy(DsSpacing.medium),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
            ) {
                StateDot(if (state.running) StateDotState.Running else StateDotState.Idle)
                Text(
                    stringResource(R.string.local_run_center),
                    style = DsType.base16Strong,
                    color = colors.labelPrimary,
                )
            }

            state.goal?.let { goal ->
                Column(verticalArrangement = Arrangement.spacedBy(DsSpacing.tiny)) {
                    Text(stringResource(R.string.local_run_current_goal), style = DsType.caption11Strong, color = colors.labelTertiary)
                    Text(goal.description, style = DsType.std14Strong, color = colors.labelPrimary)
                    Text(goal.status, style = DsType.caption11, color = colors.labelSecondary)
                }
            }

            if (state.plan.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(DsSpacing.tiny)) {
                    Text(stringResource(R.string.local_run_plan), style = DsType.caption11Strong, color = colors.labelTertiary)
                    state.plan.take(5).forEachIndexed { index, step ->
                        Text(
                            (index + 1).toString().padStart(2, '0') + "  " + step,
                            style = DsType.small13,
                            color = colors.labelSecondary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            if (state.todos.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(DsSpacing.tiny)) {
                    Text(
                        stringResource(R.string.local_run_tasks_progress, completed, total),
                        style = DsType.caption11Strong,
                        color = colors.labelTertiary,
                    )
                    state.todos.take(5).forEach { todo ->
                        val marker = when (todo.status) {
                            "completed" -> "✓"
                            "in_progress", "running" -> "●"
                            else -> "○"
                        }
                        Text(
                            marker + "  " + todo.content,
                            style = DsType.small13,
                            color = if (todo.status == "completed") colors.labelTertiary else colors.labelSecondary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            if (state.jobs.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
                    Text(stringResource(R.string.local_run_background), style = DsType.caption11Strong, color = colors.labelTertiary)
                    state.jobs.take(4).forEach { job ->
                        val expanded = expandedJobId == job.id
                        Surface(
                            shape = DsShapes.row,
                            color = colors.wallpaperSurface(WallpaperSurfaceLevel.CARD),
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(DsSpacing.small),
                                verticalArrangement = Arrangement.spacedBy(DsSpacing.xsmall),
                            ) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
                                ) {
                                    Text(
                                        job.label,
                                        style = DsType.small13,
                                        color = colors.labelSecondary,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        localJobStatusLabel(job.status),
                                        style = DsType.caption11,
                                        color = colors.labelTertiary,
                                    )
                                    DsButton(
                                        text = stringResource(
                                            if (expanded) R.string.local_run_job_hide
                                            else R.string.local_run_job_view,
                                        ),
                                        onClick = {
                                            if (expanded) {
                                                expandedJobId = null
                                                expandedJobOutput = ""
                                            } else {
                                                expandedJobId = job.id
                                                expandedJobOutput = onJobOutput(job.id)
                                            }
                                        },
                                        variant = DsButtonVariant.Ghost,
                                        size = DsButtonSize.Small,
                                    )
                                }
                                if (expanded) {
                                    Text(
                                        stringResource(R.string.local_run_job_output),
                                        style = DsType.caption11Strong,
                                        color = colors.labelTertiary,
                                    )
                                    Text(
                                        expandedJobOutput.ifBlank {
                                            stringResource(R.string.local_run_job_output_empty)
                                        },
                                        style = DsType.caption11,
                                        color = colors.labelSecondary,
                                        maxLines = 12,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.End,
                                    ) {
                                        DsButton(
                                            text = stringResource(R.string.local_run_job_refresh),
                                            onClick = { expandedJobOutput = onJobOutput(job.id) },
                                            variant = DsButtonVariant.Ghost,
                                            size = DsButtonSize.Small,
                                        )
                                        if (job.status == "running") {
                                            DsButton(
                                                text = stringResource(R.string.local_run_job_stop),
                                                onClick = {
                                                    onStopJob(job.id)
                                                    expandedJobOutput = onJobOutput(job.id)
                                                },
                                                variant = DsButtonVariant.Danger,
                                                size = DsButtonSize.Small,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(DsSpacing.tiny)) {
                Text(stringResource(R.string.local_run_resources), style = DsType.caption11Strong, color = colors.labelTertiary)
                Text(resourceSummary, style = DsType.caption11, color = colors.labelSecondary)
                Text(contextSummary, style = DsType.caption11, color = colors.labelTertiary)
            }
        }
    }
}

@Composable
private fun localJobStatusLabel(status: String): String = when (status) {
    "running" -> stringResource(R.string.jobs_running)
    "completed" -> stringResource(R.string.jobs_completed)
    "killed", "cancelled" -> stringResource(R.string.jobs_killed)
    "failed" -> stringResource(R.string.jobs_failed)
    "interrupted" -> stringResource(R.string.local_run_job_interrupted)
    else -> status
}

@Composable
private fun LocalMessageRow(
    message: LocalHarnessMessage,
    chatMode: Boolean,
    canEdit: Boolean,
    canRegenerate: Boolean,
    branchInfo: LocalChatBranchInfo?,
    onEdit: (LocalHarnessMessage) -> Unit,
    onSelectVariant: (String, Int) -> Boolean,
    onRegenerate: (String) -> Boolean,
) {
    val colors = DsTheme.colors
    val backgroundState = LocalAppBackgroundState.current
    val clipboard = LocalClipboardManager.current

    when (message.role) {
        "user" -> Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(DsSpacing.tiny),
        ) {
            UserBubble(message.content)
            if (chatMode && (canEdit || branchInfo != null)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End,
                ) {
                    MessageVariantControls(
                        messageId = message.id,
                        branchInfo = branchInfo,
                        onSelectVariant = onSelectVariant,
                    )
                    if (canEdit) {
                        DsIconButton(
                            icon = FeatherIcons.Edit3,
                            contentDescription = stringResource(R.string.local_edit_user_message),
                            onClick = { onEdit(message) },
                            tint = colors.labelTertiary.copy(alpha = 0.78f),
                        )
                    }
                }
            }
        }

        "system" -> Unit

        "reasoning", "tool", "progress" -> WorkProcessRow(listOf(message))

        else -> {
            val readingLayer = chatMode && backgroundState.hasImage
            val readingModifier = if (readingLayer) {
                Modifier
                    .fillMaxWidth()
                    .background(
                        backgroundState.wallpaperSurface(
                            base = colors.bgBase,
                            level = WallpaperSurfaceLevel.CARD,
                            region = BackgroundRegion.MIDDLE,
                        ),
                        RoundedCornerShape(18.dp),
                    )
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            } else {
                Modifier.fillMaxWidth()
            }
            Column(
                readingModifier,
                verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
            ) {
                MarkdownText(message.content)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (chatMode) {
                        MessageVariantControls(
                            messageId = message.id,
                            branchInfo = branchInfo,
                            onSelectVariant = onSelectVariant,
                        )
                    }
                    DsIconButton(
                        icon = Icons.Outlined.ContentCopy,
                        contentDescription = stringResource(R.string.chat_copy_answer),
                        onClick = { clipboard.setText(AnnotatedString(message.content)) },
                        tint = colors.labelTertiary.copy(alpha = 0.78f),
                    )
                    if (canRegenerate) DsIconButton(
                        icon = Icons.Outlined.Refresh,
                        contentDescription = stringResource(R.string.local_regenerate_reply),
                        onClick = { onRegenerate(message.id) },
                        tint = colors.labelTertiary.copy(alpha = 0.78f),
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageVariantControls(
    messageId: String,
    branchInfo: LocalChatBranchInfo?,
    onSelectVariant: (String, Int) -> Boolean,
) {
    val info = branchInfo ?: return
    val colors = DsTheme.colors
    DsIconButton(
        icon = Icons.Filled.KeyboardArrowLeft,
        contentDescription = stringResource(R.string.local_previous_variant),
        onClick = { onSelectVariant(messageId, info.index - 1) },
        enabled = info.hasPrevious,
        tint = colors.labelTertiary.copy(alpha = 0.78f),
    )
    Text(
        text = stringResource(R.string.local_variant_position, info.index + 1, info.count),
        style = DsType.caption11,
        color = colors.labelTertiary,
    )
    DsIconButton(
        icon = Icons.Filled.KeyboardArrowRight,
        contentDescription = stringResource(R.string.local_next_variant),
        onClick = { onSelectVariant(messageId, info.index + 1) },
        enabled = info.hasNext,
        tint = colors.labelTertiary.copy(alpha = 0.78f),
    )
}

@Composable
private fun WorkProcessRow(messages: List<LocalHarnessMessage>) {
    if (messages.isEmpty()) return

    val colors = DsTheme.colors
    val toolMessages = messages.filter { it.role == "tool" }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = colors.wallpaperSurface(WallpaperSurfaceLevel.CARD),
    ) {
        Column(
            Modifier.padding(horizontal = DsSpacing.medium, vertical = DsSpacing.small),
            verticalArrangement = Arrangement.spacedBy(DsSpacing.tiny),
        ) {
            Text("工作过程", style = DsType.small13Strong, color = colors.labelSecondary)
            if (toolMessages.isEmpty()) {
                Text(
                    stringResource(R.string.agent_operation_generic) + " · " +
                        stringResource(R.string.agent_operation_status_running),
                    style = DsType.small13,
                    color = colors.labelTertiary,
                )
            } else {
                val grouped = linkedMapOf<com.labteto.dshmobile.ui.AgentOperationKind, MutableList<LocalHarnessMessage>>()
                toolMessages.forEach { message ->
                    grouped.getOrPut(agentOperationKind(message.toolName)) { mutableListOf() }.add(message)
                }
                grouped.values.forEach { group ->
                    val failed = group.any { toolResultFailed(it.content) }
                    val exemplar = group.first()
                    Text(
                        stringResource(agentOperationLabelRes(exemplar.toolName)) + " · " +
                            stringResource(agentOperationStatusRes(running = false, failed = failed)),
                        style = DsType.small13,
                        color = if (failed) colors.error else colors.labelTertiary,
                    )
                }
            }
        }
    }
}

private fun toolResultFailed(content: String): Boolean =
    "工具执行失败" in content || "[TOOL_TIMEOUT]" in content || "[MODEL_TIMEOUT]" in content ||
        "[NETWORK_ERROR]" in content || "[DNS_FAILED]" in content || "[SSRF_BLOCKED]" in content

private const val MAX_LOCAL_IMAGE_SELECTION = 20


private fun decodeLocalAttachmentThumbnail(
    workspacePath: String,
    relativePath: String,
): androidx.compose.ui.graphics.ImageBitmap? = runCatching {
    val root = File(workspacePath).canonicalFile
    val file = File(root, relativePath).canonicalFile
    require(file.toPath().startsWith(root.toPath()) && file.isFile)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= 160 || bounds.outHeight / (sample * 2) >= 160) {
        sample *= 2
    }
    BitmapFactory.decodeFile(
        file.absolutePath,
        BitmapFactory.Options().apply { inSampleSize = sample },
    )?.asImageBitmap()
}.getOrNull()

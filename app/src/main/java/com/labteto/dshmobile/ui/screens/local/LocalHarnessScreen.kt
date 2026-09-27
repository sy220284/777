package com.labteto.dshmobile.ui.screens.local

import android.graphics.BitmapFactory
import java.io.File
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Image
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
import androidx.compose.runtime.produceState
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.LocalConversationMode
import com.labteto.dshmobile.local.LocalGroupChatMember
import com.labteto.dshmobile.local.LocalHarnessMessage
import com.labteto.dshmobile.local.LocalHarnessState
import com.labteto.dshmobile.local.chatBranchInfo
import com.labteto.dshmobile.local.chatMessageHasAttachmentContext
import com.labteto.dshmobile.local.editableChatUserText
import com.labteto.dshmobile.local.LocalImportedAttachment
import com.labteto.dshmobile.local.LocalImageInputMode
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
import com.labteto.dshmobile.ui.components.DsCard
import com.labteto.dshmobile.ui.components.DsCategoryRow
import com.labteto.dshmobile.ui.components.DsGroupCard
import com.labteto.dshmobile.ui.components.DsIconButton
import com.labteto.dshmobile.ui.components.DsPill
import com.labteto.dshmobile.ui.components.DsQuickActionTile
import com.labteto.dshmobile.ui.components.DsStatus
import com.labteto.dshmobile.ui.components.DsStatusPill
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.components.StateDot
import com.labteto.dshmobile.ui.components.StateDotState
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
    onCheckUpdate: () -> Unit,
    updateStatus: String?,
    viewModel: LocalHarnessViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val gallery by viewModel.gallery.collectAsStateWithLifecycle()
    val transcriptHistory by viewModel.transcriptHistory.collectAsStateWithLifecycle()
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

    LaunchedEffect(state.loading) {
        if (!state.loading) hasRenderedHarnessSurface = true
    }

    LaunchedEffect(pendingUsageMode, state.loading, state.usageMode) {
        val pending = pendingUsageMode ?: return@LaunchedEffect
        when {
            state.usageMode == pending -> pendingUsageMode = null
            !state.loading -> {
                // A rejected/no-op transition should not leave the sidebar showing a phantom mode.
                // Give the engine one frame window to publish loading=true before rolling back.
                delay(250)
                if (!state.loading && state.usageMode != pending) pendingUsageMode = null
            }
        }
    }

    LaunchedEffect(state.sessionId) {
        viewModel.prepareTranscriptHistory(state.sessionId)
    }

    fun switchUsageMode(target: LocalUsageMode) {
        val returningFromGroupToSingle =
            target == LocalUsageMode.CHAT &&
                state.usageMode == LocalUsageMode.CHAT &&
                state.groupChat.enabled
        if (target == state.usageMode && !returningFromGroupToSingle) return
        if (target != state.usageMode) {
            val key = "shown_" + target.name.lowercase()
            if (!modeIntroPreferences.getBoolean(key, false)) {
                modeIntroPreferences.edit().putBoolean(key, true).apply()
                modeIntro = target
            }
        }
        pendingUsageMode = target
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

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            LocalModeDrawer(
                currentSessionId = state.sessionId,
                sessions = state.sessions,
                gallery = gallery,
                usageMode = localHarnessDrawerUsageMode(state.usageMode, pendingUsageMode),
                modeSwitchEnabled = !state.running && !state.loading,
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
                onNewPersona = {
                    scope.launch { drawerState.close() }
                    showNewPersona = true
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
                onDismiss = { showPersonaGallery = false },
            )
            else -> LocalChat(
                state = state,
                gallery = gallery,
                transcriptHistory = transcriptHistory,
                modeIntro = modeIntro,
                onOpenMenu = { scope.launch { drawerState.open() } },
                onConfigure = onOpenSettings,
                onSelectModel = viewModel::selectModel,
                onSend = viewModel::send,
                onEditAndResend = viewModel::editAndResendUserMessage,
                onSelectMessageVariant = viewModel::selectChatMessageVariant,
                onRegenerate = viewModel::regenerateReply,
                onGenerateReplySuggestions = viewModel::generateReplySuggestions,
                onLoadOlderTranscript = viewModel::loadOlderTranscript,
                onImportAttachment = viewModel::importAttachment,
                onImageModeChange = viewModel::setImageInputMode,
                onStop = viewModel::stop,
                onNewSession = { showNewSessionMode = true },
                onOpenRunCenter = { showRunCenter = true },
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

    if (showNewSessionMode) {
        NewSessionModeDialog(
            onDismiss = { showNewSessionMode = false },
            onSelect = { mode ->
                showNewSessionMode = false
                viewModel.createSession(mode)
            },
        )
    }

    if (showNewPersona && state.usageMode == LocalUsageMode.CHAT) {
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
                onOpenResults = {
                    showRunCenter = false
                    filesMode = LocalFilesMode.CONVERSATION
                },
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
private fun GroupChatMemberAvatar(
    member: LocalGroupChatMember,
    active: Boolean,
) {
    val colors = DsTheme.colors
    val portrait by produceState<ImageBitmap?>(
        initialValue = null,
        key1 = member.portraitPath,
    ) {
        value = withContext(Dispatchers.IO) {
            decodeLocalPersonaHeaderPortrait(member.portraitPath)
        }
    }

    Surface(
        modifier = Modifier.size(24.dp),
        shape = CircleShape,
        color = if (active) colors.accent.copy(alpha = 0.16f)
        else colors.wallpaperSurface(WallpaperSurfaceLevel.FLOATING),
        border = if (active) BorderStroke(1.dp, colors.accent) else null,
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (portrait != null) {
                Image(
                    bitmap = portrait!!,
                    contentDescription = member.displayName,
                    modifier = Modifier.fillMaxSize().clip(CircleShape),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Text(
                    member.displayName.trim().take(1).ifBlank { "·" },
                    style = DsType.caption11,
                    color = if (active) colors.accent else colors.labelSecondary,
                    maxLines = 1,
                )
            }
        }
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
private fun LocalChat(
    state: LocalHarnessState,
    gallery: List<PersonaGalleryEntry>,
    transcriptHistory: LocalTranscriptHistoryState,
    modeIntro: LocalUsageMode?,
    onOpenMenu: () -> Unit,
    onConfigure: () -> Unit,
    onSelectModel: (String) -> Unit,
    onSend: (String, List<LocalImportedAttachment>) -> Unit,
    onEditAndResend: (String, String) -> Boolean,
    onSelectMessageVariant: (String, Int) -> Boolean,
    onRegenerate: (String) -> Boolean,
    onGenerateReplySuggestions: suspend () -> Boolean,
    onLoadOlderTranscript: suspend (String) -> Result<Int>,
    onImportAttachment: suspend (android.net.Uri) -> LocalImportedAttachment,
    onImageModeChange: (LocalImageInputMode) -> Unit,
    onStop: () -> Unit,
    onNewSession: () -> Unit,
    onOpenRunCenter: () -> Unit,
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
    var showGroupAnnouncement by rememberSaveable { mutableStateOf(false) }
    var showPersonaEditor by rememberSaveable { mutableStateOf(false) }
    var personaEditorDraft by remember { mutableStateOf<PersonaProfile?>(null) }
    var showReplySuggestions by rememberSaveable { mutableStateOf(false) }
    var replySuggestionsLoading by remember(state.sessionId) { mutableStateOf(false) }
    var editingUserMessage by remember { mutableStateOf<LocalHarnessMessage?>(null) }
    var editingUserText by rememberSaveable { mutableStateOf("") }
    var editingUserError by remember { mutableStateOf<String?>(null) }
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
    val messageEditingEnabled = state.usageMode == LocalUsageMode.CHAT
    val messageBranchingEnabled = messageEditingEnabled
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
        editingUserText = ""
        editingUserError = null
    }

    val imageLimitMessage = stringResource(R.string.local_image_selection_limit, MAX_LOCAL_IMAGE_SELECTION)
    val imageImportFailedMessage = stringResource(R.string.local_image_import_failed)
    val fileImportFailedMessage = stringResource(R.string.local_file_import_failed)
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
                        if (!state.groupChat.enabled) {
                            LocalPersonaHeaderAvatar(
                                name = state.chatPersona.name,
                                portraitPath = currentGalleryEntry?.portraitPath.orEmpty(),
                            )
                        }
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
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    state.groupChat.members.take(6).forEach { member ->
                                        GroupChatMemberAvatar(
                                            member = member,
                                            active = state.groupActiveSpeakerName == member.displayName,
                                        )
                                    }
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
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            } else {
                                val relationship = state.chatState.relationshipState.takeIf { it.isNotBlank() }
                                val storyTitle = currentGalleryStory?.title?.takeIf { it.isNotBlank() }
                                val contextSource = when (state.conversationMode) {
                                    LocalConversationMode.INDEPENDENT -> null
                                    else -> localConversationModeLabel(state.conversationMode)
                                }
                                val secondary = listOfNotNull(storyTitle, relationship, contextSource).joinToString(" · ")
                                if (secondary.isNotBlank()) {
                                    Text(
                                        secondary,
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

        if (state.usageMode == LocalUsageMode.WORK) {
            WorkSessionStatusStrip(
                state = state,
                onClick = onOpenRunCenter,
            )
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
                            onEdit = { message ->
                                editingUserMessage = message
                                editingUserText = editableChatUserText(message)
                                editingUserError = null
                            },
                            onSelectVariant = onSelectMessageVariant,
                            onRegenerate = onRegenerate,
                        )
                        is LocalTranscriptItem.WorkProcess -> WorkProcessRow(transcriptItem.messages)
                    }
                }
                if (
                    state.usageMode == LocalUsageMode.CHAT &&
                    state.running &&
                    state.streamingAssistant.isNotBlank()
                ) {
                    item(key = "streaming:${state.sessionId}") {
                        LocalMessageRow(
                            message = LocalHarnessMessage(
                                id = "streaming:${state.sessionId}",
                                role = "assistant",
                                content = state.streamingAssistant,
                                createdAt = 0L,
                            ),
                            chatMode = true,
                            groupMode = false,
                            canEdit = false,
                            canRegenerate = false,
                            branchInfo = null,
                            onEdit = { },
                            onSelectVariant = { _, _ -> false },
                            onRegenerate = { false },
                        )
                    }
                }
                if (
                    state.running &&
                    (state.usageMode == LocalUsageMode.WORK || state.streamingAssistant.isBlank())
                ) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(DsSpacing.small))
                                Text(
                                    stringResource(
                                        if (state.usageMode == LocalUsageMode.CHAT) {
                                            R.string.local_chat_replying
                                        } else {
                                            R.string.local_streaming_status
                                        },
                                    ),
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

        if (
            state.usageMode == LocalUsageMode.WORK &&
            state.running &&
            state.streamingAssistant.isNotBlank()
        ) {
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
                    if (
                        state.usageMode == LocalUsageMode.WORK &&
                        attachments.any { it.mediaType.startsWith("image/") }
                    ) {
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
                            when {
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
                if (state.usageMode == LocalUsageMode.WORK) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
                    ) {
                        DsButton(
                            text = stringResource(
                                if (state.planMode) R.string.local_plan_button_on
                                else R.string.local_plan_button_off,
                            ),
                            onClick = { onPlanModeChange(!state.planMode) },
                            modifier = Modifier.weight(1f),
                            variant = if (state.planMode) DsButtonVariant.Info else DsButtonVariant.Ghost,
                            size = DsButtonSize.Small,
                            enabled = !state.running,
                        )
                        DsButton(
                            text = stringResource(R.string.local_auto_approve_short),
                            onClick = if (state.safeAutoApprovalEnabled) onDisableAutoApprove else onAutoApprove,
                            modifier = Modifier.weight(1f),
                            variant = if (state.safeAutoApprovalEnabled) DsButtonVariant.Info else DsButtonVariant.Ghost,
                            size = DsButtonSize.Small,
                        )
                    }
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DsIconButton(
                        icon = Icons.Filled.Add,
                        contentDescription = stringResource(R.string.chat_composer_add_attachment),
                        onClick = { showAttachmentPicker = true },
                        enabled = !state.running,
                        tint = colors.labelPrimary,
                        containerColor = colors.wallpaperSurface(WallpaperSurfaceLevel.FLOATING, BackgroundRegion.BOTTOM),
                    )
                    if (
                        state.usageMode == LocalUsageMode.CHAT &&
                        !state.groupChat.enabled
                    ) {
                        DsButton(
                            text = stringResource(
                                if (replySuggestionsLoading) R.string.common_loading
                                else R.string.local_reply_suggestions_open,
                            ),
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
                            variant = DsButtonVariant.Ghost,
                            size = DsButtonSize.Small,
                            enabled = !state.running &&
                                !replySuggestionsLoading &&
                                state.messages.any { message ->
                                    message.role == "assistant" && message.content.isNotBlank()
                                },
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    if (!state.running) {
                        DsButton(
                            stringResource(R.string.chat_composer_send),
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
                            enabled = groupChatReady && (input.isNotBlank() || attachments.isNotEmpty()),
                        )
                    }
                }
                if (state.running) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(DsSpacing.small, Alignment.End),
                    ) {
                        DsButton(
                            stringResource(R.string.chat_composer_stop),
                            onStop,
                            variant = DsButtonVariant.Danger,
                            size = DsButtonSize.Small,
                        )
                        if (state.usageMode == LocalUsageMode.WORK) {
                            DsButton(
                                if (state.queuedInputCount > 0) {
                                    stringResource(R.string.local_queue_message_count, state.queuedInputCount)
                                } else {
                                    stringResource(R.string.local_queue_message)
                                },
                                onClick = {
                                    val selected = attachments.toList()
                                    onSend(input, selected)
                                    drafts[state.sessionId] = ""
                                    attachments.clear()
                                    focusManager.clearFocus()
                                    keyboardController?.hide()
                                },
                                size = DsButtonSize.Small,
                                enabled = groupChatReady && (input.isNotBlank() || attachments.isNotEmpty()),
                            )
                        }
                    }
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
                    enabled = (
                        editingUserText.trim().isNotEmpty() ||
                            chatMessageHasAttachmentContext(message)
                        ) &&
                        editingUserText.trim() != editableChatUserText(message).trim() &&
                        !state.running,
                    size = DsButtonSize.Small,
                )
            }
        }
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
    if (showImageModePicker && state.usageMode == LocalUsageMode.WORK) {
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
internal fun LocalUsageModePill(
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
internal fun LocalPersonaHeaderAvatar(
    name: String,
    portraitPath: String,
) {
    val colors = DsTheme.colors
    val portrait by produceState<ImageBitmap?>(
        initialValue = null,
        key1 = portraitPath,
    ) {
        value = withContext(Dispatchers.IO) {
            decodeLocalPersonaHeaderPortrait(portraitPath)
        }
    }
    Surface(
        modifier = Modifier.size(34.dp),
        shape = CircleShape,
        color = colors.accentTertiary,
        border = BorderStroke(1.dp, colors.borderL1),
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (portrait != null) {
                Image(
                    bitmap = portrait!!,
                    contentDescription = name,
                    modifier = Modifier.fillMaxSize().clip(CircleShape),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Text(
                    name.trim().take(1).ifBlank { "·" },
                    style = DsType.small13Strong,
                    color = colors.accent,
                )
            }
        }
    }
}

private fun decodeLocalPersonaHeaderPortrait(path: String): ImageBitmap? {
    if (path.isBlank()) return null
    val file = File(path)
    if (!file.isFile) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    val longest = maxOf(bounds.outWidth, bounds.outHeight)
    while (longest / sample > 256) sample *= 2
    return BitmapFactory.decodeFile(
        file.absolutePath,
        BitmapFactory.Options().apply { inSampleSize = sample },
    )?.asImageBitmap()
}

@Composable
private fun WorkSessionStatusStrip(
    state: LocalHarnessState,
    onClick: () -> Unit,
) {
    val colors = DsTheme.colors
    val hasStatus = state.running ||
        state.workflowProgress?.sessionId == state.sessionId ||
        state.goal != null ||
        state.todos.isNotEmpty() ||
        state.activeAgents > 0 ||
        state.jobs.isNotEmpty()
    if (!hasStatus) return

    val completedTasks = state.todos.count { it.status == "completed" }
    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = DsSpacing.medium, vertical = DsSpacing.tiny),
        shape = DsShapes.block,
        color = colors.wallpaperSurface(WallpaperSurfaceLevel.CARD),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = DsSpacing.medium, vertical = DsSpacing.small),
            verticalArrangement = Arrangement.spacedBy(DsSpacing.xsmall),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
            ) {
                DsStatusPill(
                    state = if (state.running) DsStatus.Running else DsStatus.Neutral,
                    label = stringResource(
                        if (state.running) R.string.local_execution_notification_running
                        else R.string.local_run_center,
                    ),
                )
                state.goal?.description?.takeIf(String::isNotBlank)?.let { goal ->
                    Text(
                        goal,
                        style = DsType.small13Strong,
                        color = colors.labelPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                } ?: Spacer(Modifier.weight(1f))
                Icon(
                    Icons.Filled.KeyboardArrowRight,
                    contentDescription = stringResource(R.string.local_run_center),
                    tint = colors.labelTertiary,
                    modifier = Modifier.size(18.dp),
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(DsSpacing.xsmall),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                state.workflowProgress?.takeIf { it.sessionId == state.sessionId }?.let { progress ->
                    DsPill(text = if (progress.needsUserAction) stringResource(R.string.local_workflow_waiting_user) else workflowStageLabel(progress.stage))
                    DsPill(text = stringResource(R.string.local_workflow_processed, progress.completed, progress.total))
                }
                if (state.todos.isNotEmpty()) {
                    DsPill(
                        text = stringResource(
                            R.string.local_run_tasks_progress,
                            completedTasks,
                            state.todos.size,
                        ),
                    )
                }
                if (state.activeAgents > 0) {
                    DsPill(text = stringResource(R.string.local_run_agents, state.activeAgents))
                }
                if (state.jobs.isNotEmpty()) {
                    DsPill(text = stringResource(R.string.local_run_background) + " " + state.jobs.size)
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

@Composable
private fun ExecutionStatusCard(
    state: LocalHarnessState,
    onJobOutput: (String) -> String,
    onStopJob: (String) -> String,
    onOpenResults: () -> Unit,
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
    val pressureSummary = stringResource(
        R.string.local_resource_pressure,
        localResourcePressureLabel(state.resourcePressure),
    )
    val contextSourceSummary = stringResource(
        R.string.local_context_source,
        localConversationModeLabel(state.conversationMode),
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

            state.workflowProgress?.takeIf { it.sessionId == state.sessionId }?.let { progress ->
                WorkflowProgressSection(progress)
            }

            if (state.pendingApproval != null || state.pendingQuestion != null) {
                Text(
                    stringResource(R.string.local_workflow_waiting_user),
                    style = DsType.small13Strong,
                    color = colors.error,
                )
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

            DsButton(
                text = stringResource(R.string.local_run_open_results),
                onClick = onOpenResults,
                variant = DsButtonVariant.Outline,
                size = DsButtonSize.Small,
                modifier = Modifier.fillMaxWidth(),
            )

            Column(verticalArrangement = Arrangement.spacedBy(DsSpacing.tiny)) {
                Text(stringResource(R.string.local_run_resources), style = DsType.caption11Strong, color = colors.labelTertiary)
                Text(resourceSummary, style = DsType.caption11, color = colors.labelSecondary)
                Text(pressureSummary, style = DsType.caption11, color = colors.labelSecondary)
                if (state.queuedInputCount > 0) {
                    Text(
                        stringResource(R.string.local_queue_count, state.queuedInputCount),
                        style = DsType.caption11,
                        color = colors.labelSecondary,
                    )
                }
                Text(contextSummary, style = DsType.caption11, color = colors.labelTertiary)
                Text(contextSourceSummary, style = DsType.caption11, color = colors.labelTertiary)
                if (
                    state.conversationMode == LocalConversationMode.CONTINUATION &&
                    !state.handoffSummary.isNullOrBlank()
                ) {
                    Text(
                        stringResource(R.string.local_context_handoff),
                        style = DsType.caption11Strong,
                        color = colors.labelTertiary,
                    )
                    Text(
                        state.handoffSummary.orEmpty(),
                        style = DsType.caption11,
                        color = colors.labelSecondary,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

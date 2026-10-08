package com.labteto.dshmobile.ui.screens.local

import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labteto.dshmobile.R
import com.labteto.dshmobile.automation.AutomationMode
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.attachment.LocalImportedAttachment
import com.labteto.dshmobile.local.chat.LocalChatUserEditResult
import com.labteto.dshmobile.local.chat.PersonaGalleryEntry
import com.labteto.dshmobile.local.chat.PersonaProfile
import com.labteto.dshmobile.local.presentation.chatBranchInfo
import com.labteto.dshmobile.local.presentation.isUnboundChatPersona
import com.labteto.dshmobile.local.feature.LocalFeatureCatalog
import com.labteto.dshmobile.local.model.LocalHarnessStreamingState
import com.labteto.dshmobile.local.model.LocalModelProfile
import com.labteto.dshmobile.local.presentation.LocalConversationSurfaceState
import com.labteto.dshmobile.local.send.LocalSendFeedbackState
import com.labteto.dshmobile.local.send.LocalSendRejectReason
import com.labteto.dshmobile.local.send.LocalSendResult
import com.labteto.dshmobile.local.session.LocalConversationMode
import com.labteto.dshmobile.local.session.LocalHarnessMessage
import com.labteto.dshmobile.ui.components.ConversationScrollShortcut
import com.labteto.dshmobile.ui.components.ConversationScrollTarget
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
import com.labteto.dshmobile.ui.components.DsQuickActionTile
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.components.MenuItem
import com.labteto.dshmobile.ui.components.rememberConversationScrollHint
import com.labteto.dshmobile.ui.screens.main.RenameDialog
import com.labteto.dshmobile.ui.screens.settings.SettingsDestination
import com.labteto.dshmobile.ui.theme.BackgroundRegion
import com.labteto.dshmobile.ui.theme.DsAnimations
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    onCheckUpdate: () -> Unit = {},
    updateStatus: String? = null,
    viewModel: LocalHarnessViewModel = hiltViewModel(),
) {
    val shell by viewModel.shellState.collectAsStateWithLifecycle()
    val approvalMode by viewModel.approvalMode.collectAsStateWithLifecycle()
    val networkSearchEnabled by viewModel.networkSearchEnabled.collectAsStateWithLifecycle()
    val activeModelProfile by viewModel.activeModelProfile.collectAsStateWithLifecycle()
    val sendFeedback by viewModel.sendFeedbackState.collectAsStateWithLifecycle()
    val gallery by viewModel.gallery.collectAsStateWithLifecycle()
    val transcriptHistory by viewModel.transcriptHistory.collectAsStateWithLifecycle()
    val pinnedSessionIds by viewModel.pinnedSessionIds.collectAsStateWithLifecycle()
    val sessionTitleOverrides by viewModel.sessionTitleOverrides.collectAsStateWithLifecycle()
    val projectRecoveryNotice by viewModel.projectRecoveryNotice.collectAsStateWithLifecycle()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val drawerFocusManager = LocalFocusManager.current
    val drawerKeyboard = LocalSoftwareKeyboardController.current
    val modeIntroPreferences = remember(context) { context.getSharedPreferences("local_mode_intro", android.content.Context.MODE_PRIVATE) }
    var showNewSessionMode by rememberSaveable { mutableStateOf(false) }
    var featureStack by rememberSaveable { mutableStateOf(localFeatureHome()) }
    var drawerFeatureOriginStack by rememberSaveable { mutableStateOf<List<String>?>(null) }
    var filesMode by rememberSaveable { mutableStateOf(LocalFilesMode.WORKSPACE) }
    var settingsDestination by rememberSaveable { mutableStateOf(SettingsDestination.ROOT) }
    var taskMode by rememberSaveable { mutableStateOf<AutomationMode?>(null) }
    var composerHandoff by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    var pendingWorkCapability by rememberSaveable { mutableStateOf<String?>(null) }
    var workCapabilityConfirmed by rememberSaveable { mutableStateOf(false) }
    var workCapabilityFailed by rememberSaveable { mutableStateOf(false) }
    var toolsStartAtPlugins by rememberSaveable { mutableStateOf(false) }
    var toolsStartAtSkills by rememberSaveable { mutableStateOf(false) }
    var showGroupSetup by rememberSaveable { mutableStateOf(false) }
    var showPersonaGallerySavePrompt by rememberSaveable { mutableStateOf(false) }
    var showNewPersona by rememberSaveable { mutableStateOf(false) }
    var modeIntro by remember { mutableStateOf<LocalUsageMode?>(null) }
    var pendingUsageMode by remember { mutableStateOf<LocalUsageMode?>(null) }
    val featurePage = localFeatureCurrent(featureStack)
    val featureStateHolder = rememberSaveableStateHolder()
    val predictiveBackProgress = remember { Animatable(0f) }

    fun pushFeature(page: LocalFeaturePage) {
        drawerFeatureOriginStack = null
        featureStack = localFeaturePush(featureStack, page)
    }

    fun openFeatureFromDrawer(page: LocalFeaturePage) {
        val navigation = localFeatureOpenFromDrawer(
            stack = featureStack,
            originStack = drawerFeatureOriginStack,
            page = page,
        )
        drawerFeatureOriginStack = navigation.originStack
        featureStack = navigation.stack
    }

    fun popFeature() {
        drawerFeatureOriginStack = null
        featureStack = localFeaturePop(featureStack)
    }

    fun resetFeatureNavigation() {
        drawerFeatureOriginStack = null
        LocalFeatureCatalog.routes
            .filterNot { it == LocalFeaturePage.HOME }
            .forEach { featureStateHolder.removeState(it.name) }
        featureStack = localFeatureHome()
    }

    fun handoffWorkCapability(prompt: String) {
        composerHandoff = listOf(shell.sessionId, prompt)
        taskMode = null
        toolsStartAtPlugins = false
        toolsStartAtSkills = false
        resetFeatureNavigation()
    }

    fun useWorkCapability(prompt: String) {
        if (shell.usageMode == LocalUsageMode.WORK && !shell.loading) {
            handoffWorkCapability(prompt)
        } else {
            pendingWorkCapability = prompt
            workCapabilityConfirmed = false
            workCapabilityFailed = false
        }
    }

    val shellActions = LocalShellFeatureUiActions(
        streamingState = viewModel.streamingState,
        selectModel = viewModel::selectModel,
        send = viewModel::send,
        sendWithTeam = viewModel::sendWithTeam,
        teamMemberOutput = viewModel::teamMemberOutput,
        sendTeamMemberMessage = viewModel::sendTeamMemberMessage,
        stopTeamMember = viewModel::stopTeamMember,
        stopTeam = viewModel::stopTeam,
        editAndResend = viewModel::editAndResendUserMessage,
        selectMessageVariant = viewModel::selectChatMessageVariant,
        regenerate = viewModel::regenerateReply,
        generateReplySuggestions = viewModel::generateReplySuggestions,
        loadOlderTranscript = viewModel::loadOlderTranscript,
        importAttachment = viewModel::importAttachment,
        stop = viewModel::stop,
        exitGroupChat = viewModel::leaveGroupChatMode,
        toggleSessionPinned = viewModel::toggleSessionPinned,
        renameSession = viewModel::renameSession,
        deleteSessions = viewModel::deleteSessions,
        configureChatPersona = viewModel::configureChatPersona,
        configureGroupMembers = viewModel::configureGroupChatMembers,
        selectGalleryPersona = viewModel::selectGalleryPersonaForCurrentChat,
        autoFillChatPersona = viewModel::autoFillChatPersona,
        saveGroupAnnouncement = viewModel::setGroupChatAnnouncement,
        generateGroupAnnouncement = viewModel::generateGroupChatAnnouncement,
        undoPersonaCorrection = viewModel::undoChatPersonaCorrection,
        setPlanMode = viewModel::setPlanMode,
        approve = viewModel::approve,
        deny = viewModel::deny,
        enableAutoApproval = viewModel::enableAutoApproval,
        useDefaultApproval = viewModel::useDefaultApproval,
        setNetworkSearchEnabled = viewModel::setNetworkSearchEnabled,
        enableAutoApprovalForPending = viewModel::enableAutoApprovalForPending,
        enableDeviceApprovalLease = viewModel::enableDeviceApprovalLease,
        disableDeviceApprovalLease = viewModel::disableDeviceApprovalLease,
        disableAutoApproval = viewModel::disableAutoApproval,
        answerQuestion = viewModel::answerQuestion,
        cancelQuestion = viewModel::cancelQuestion,
    )
    val chatActions = LocalChatFeatureUiActions(
        personaPresets = viewModel.personaPresets,
        diaryEntries = viewModel::diaryEntries,
        hasUnsavedCurrentPersona = viewModel::hasUnsavedCurrentPersona,
        currentGalleryHasUnsavedChanges = viewModel::currentGalleryHasUnsavedChanges,
        saveCurrentToGallery = viewModel::saveCurrentToGallery,
        editGalleryNotes = viewModel::editGalleryNotes,
        renameGalleryStory = viewModel::renameGalleryStory,
        inspectGalleryPersona = viewModel::inspectGalleryPersona,
        applyGallerySuggestions = viewModel::applyGallerySuggestions,
        deleteGalleryEntry = viewModel::deleteGalleryEntry,
        deleteGalleryStory = viewModel::deleteGalleryStory,
        deleteGalleryHistoryMessage = viewModel::deleteGalleryHistoryMessage,
        exportGalleryPersona = viewModel::exportGalleryPersona,
        importGalleryPersona = viewModel::importGalleryPersona,
        installPersonaPreset = viewModel::installPersonaPreset,
        setGalleryPortrait = viewModel::setGalleryPortrait,
        removeGalleryPortrait = viewModel::removeGalleryPortrait,
        startFromGallery = viewModel::startFromGallery,
    )
    val workActions = LocalWorkFeatureUiActions(
        workState = viewModel.workState,
        workspaceFiles = viewModel::workspaceFiles,
        conversationFiles = viewModel::conversationFiles,
        previewWorkspaceFile = viewModel::previewWorkspaceFile,
        backgroundJobOutput = viewModel::backgroundJobOutput,
        artifactsForUi = viewModel::artifactsForUi,
        toolActivitiesForUi = viewModel::toolActivitiesForUi,
        eventSequenceForUi = viewModel::eventSequenceForUi,
        toolEvidenceForUi = viewModel::toolEvidenceForUi,
        stopBackgroundJob = viewModel::stopBackgroundJob,
        startBackgroundAgent = viewModel::startBackgroundAgent,
        startResearchAgent = viewModel::startResearchAgent,
        sendBackgroundAgentMessage = viewModel::sendBackgroundAgentMessage,
    )
    val projectActions = LocalProjectUiActions(
        catalog = viewModel.projectCatalog,
        recoveryNotice = viewModel.projectRecoveryNotice,
        backupAndReset = viewModel::backupAndResetProjectCatalog,
        createProjectWorkSession = viewModel::createProjectWorkSession,
        onProjectSessionAccepted = ::resetFeatureNavigation,
        create = viewModel::createProject,
        select = viewModel::selectProject,
        rename = viewModel::renameProject,
        updateInstructions = viewModel::updateProjectInstructions,
    )
    val automationActions = LocalAutomationFeatureUiActions(
        switchSession = viewModel::switchSession,
    )
    val activeConversationState = when (shell.usageMode) {
        LocalUsageMode.CHAT -> viewModel.chatSurfaceState
        LocalUsageMode.WORK -> viewModel.workSurfaceState
    }

    val featureContributions = listOf(
        localShellFeatureUiContribution(
            shell = shell,
            state = activeConversationState,
            activeModelProfile = activeModelProfile,
            sendFeedback = sendFeedback,
            approvalMode = approvalMode,
            networkSearchEnabled = networkSearchEnabled,
            gallery = gallery,
            transcriptHistory = transcriptHistory,
            modeIntro = modeIntro,
            pinnedSessionIds = pinnedSessionIds,
            sessionTitleOverrides = sessionTitleOverrides,
            actions = shellActions,
            onSettingsDestinationChange = { settingsDestination = it },
            onPushFeature = ::pushFeature,
            onNewSession = { showNewSessionMode = true },
            onOpenDrawer = { scope.launch { drawerState.open() } },
            onUseWorkCapability = ::useWorkCapability,
            composerHandoff = composerHandoff,
            onConsumeComposerHandoff = { composerHandoff = emptyList() },
        ),
        localChatFeatureUiContribution(
            gallery = gallery,
            state = viewModel.chatSurfaceState,
            actions = chatActions,
            onResetNavigation = ::resetFeatureNavigation,
            onNewPersona = { showNewPersona = true },
            onPopFeature = ::popFeature,
            sessions = shell.sessions,
            onSwitchSession = viewModel::switchSession,
            onOpenGroupSetup = { showGroupSetup = true },
            onPromptPersonaSave = { showPersonaGallerySavePrompt = true },
            onOpenFromDrawer = ::openFeatureFromDrawer,
            onCloseDrawer = { scope.launch { drawerState.close() } },
        ),
        localWorkFeatureUiContribution(
            filesMode = filesMode,
            shell = shell,
            actions = workActions,
            onFilesModeChange = { filesMode = it },
            onPushFeature = ::pushFeature,
            onPopFeature = ::popFeature,
            onOpenFromDrawer = ::openFeatureFromDrawer,
            onCloseDrawer = { scope.launch { drawerState.close() } },
        ),
        localProjectFeatureUiContribution(
            actions = projectActions,
            onPopFeature = ::popFeature,
            onOpenFromDrawer = ::openFeatureFromDrawer,
            onCloseDrawer = { scope.launch { drawerState.close() } },
        ),
        localAutomationFeatureUiContribution(
            taskMode = taskMode,
            onCreateViaChat = ::useWorkCapability,
            actions = automationActions,
            onTaskModeChange = { taskMode = it },
            onResetNavigation = ::resetFeatureNavigation,
            onPopFeature = ::popFeature,
            onOpenFromDrawer = ::openFeatureFromDrawer,
            onCloseDrawer = { scope.launch { drawerState.close() } },
        ),
        localToolsFeatureUiContribution(
            startAtPlugins = toolsStartAtPlugins,
            startAtSkills = toolsStartAtSkills,
            onUseCapability = ::useWorkCapability,
            onTaskModeChange = { taskMode = it },
            onSettingsDestinationChange = { settingsDestination = it },
            onPushFeature = ::pushFeature,
            onPopFeature = { toolsStartAtPlugins = false; toolsStartAtSkills = false; popFeature() },
            onOpenFromDrawer = ::openFeatureFromDrawer,
            onCloseDrawer = { scope.launch { drawerState.close() } },
        ),
        localSettingsFeatureUiContribution(
            settingsDestination = settingsDestination,
            updateStatus = updateStatus,
            onCheckUpdate = onCheckUpdate,
            onOpenRemote = {
                resetFeatureNavigation()
                onOpenRemote()
            },
            onSettingsDestinationChange = { settingsDestination = it },
            onPopFeature = ::popFeature,
            onOpenFromDrawer = ::openFeatureFromDrawer,
            onCloseDrawer = { scope.launch { drawerState.close() } },
        ),
    )

    fun openDrawerEntry(entry: LocalFeatureDrawerEntry) {
        val action = localFeatureDrawerAction(entry, featureContributions)
            ?: error("Missing Feature drawer contribution: $entry")
        action()
    }

    LaunchedEffect(
        featureStack,
        drawerFeatureOriginStack,
        featureContributions.map(LocalFeatureUiContribution::moduleId),
    ) {
        val restoredStack = localFeatureRestoreStack(featureStack, featureContributions)
        val restoredOrigin = drawerFeatureOriginStack?.let {
            localFeatureRestoreStack(it, featureContributions)
        }
        if (restoredStack != featureStack) featureStack = restoredStack
        if (restoredOrigin != drawerFeatureOriginStack) drawerFeatureOriginStack = restoredOrigin
    }

    LaunchedEffect(modeIntro) { if (modeIntro != null) { delay(6_000); modeIntro = null } }

    LaunchedEffect(pendingUsageMode, shell.loading, shell.usageMode) {
        val pending = pendingUsageMode ?: return@LaunchedEffect
        when {
            shell.usageMode == pending && !shell.loading -> pendingUsageMode = null
            !shell.loading -> {
                // A rejected/no-op transition should not leave the sidebar showing a phantom mode.
                // Give the runtime transition one frame window to publish loading=true before rolling back.
                delay(250)
                if (!shell.loading && shell.usageMode != pending) pendingUsageMode = null
            }
        }
    }

    LaunchedEffect(workCapabilityConfirmed, pendingUsageMode, shell.loading, shell.usageMode, shell.sessionId) {
        val prompt = pendingWorkCapability ?: return@LaunchedEffect
        if (!workCapabilityConfirmed) return@LaunchedEffect
        if (shell.usageMode == LocalUsageMode.WORK && !shell.loading) {
            handoffWorkCapability(prompt)
            pendingWorkCapability = null
            workCapabilityConfirmed = false
        } else if (pendingUsageMode == null && !shell.loading) {
            workCapabilityConfirmed = false
            workCapabilityFailed = true
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

    LaunchedEffect(requestedSessionId, shell.sessions, shell.loading, shell.sessionId) {
        val target = requestedSessionId?.takeIf(String::isNotBlank) ?: return@LaunchedEffect
        val accepted = acceptLocalSessionNavigation(
            currentSessionId = shell.sessionId,
            targetSessionId = target,
            sessions = shell.sessions,
            switchSession = viewModel::switchSession,
        )
        if (accepted) {
            resetFeatureNavigation()
            onSessionRequestConsumed()
        }
    }

    BackHandler(enabled = drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }

    PredictiveBackHandler(
        enabled = !drawerState.isOpen && featurePage != LocalFeaturePage.HOME,
    ) { progress ->
        var swipeEdge: Int? = null
        try {
            progress.collect { event ->
                if (swipeEdge == null) swipeEdge = event.swipeEdge
                when (localFeatureOwnedBackAction(featurePage, event.swipeEdge, featureContributions)) {
                    LocalFeatureBackAction.POP_FEATURE ->
                        predictiveBackProgress.snapTo(event.progress.coerceIn(0f, 1f))

                    null ->
                        if (predictiveBackProgress.value != 0f) predictiveBackProgress.snapTo(0f)
                }
            }

            when (localFeatureOwnedBackAction(featurePage, swipeEdge, featureContributions)) {
                LocalFeatureBackAction.POP_FEATURE -> {
                    popFeature()
                    predictiveBackProgress.snapTo(0f)
                }

                null -> predictiveBackProgress.snapTo(0f)
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                predictiveBackProgress.animateTo(
                    targetValue = 0f,
                    animationSpec = DsAnimations.predictiveBackSettle,
                )
            }
            throw cancelled
        }
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
                drawerOpen = drawerState.isOpen,
                pinnedSessionIds = pinnedSessionIds,
                sessionTitleOverrides = sessionTitleOverrides,
                currentGalleryId = shell.galleryId,
                currentGalleryStoryId = shell.galleryStoryId,
                currentPersonaName = shell.chatPersona.name,
                currentPersonaIdentity = shell.chatPersona.portrait,
                workModelLabel = activeModelProfile?.displayName
                    ?: activeModelProfile?.model
                    ?: stringResource(R.string.local_model_setup),
                groupChatEnabled = shell.groupChat.enabled,
                running = shell.running,
                onUsageModeChange = {
                    switchUsageMode(it)
                },
                onNewSession = {
                    scope.launch { drawerState.close() }
                    showNewSessionMode = true
                },
                onSwitchSession = { sessionId ->
                    if (viewModel.switchSession(sessionId)) {
                        resetFeatureNavigation()
                        scope.launch { drawerState.close() }
                    }
                },
                onDeleteSessions = viewModel::deleteSessions,
                onRenameSession = viewModel::renameSession,
                onTogglePinSession = viewModel::toggleSessionPinned,
                onWorkspaceFiles = { openDrawerEntry(LocalFeatureDrawerEntry.WORKSPACE) },
                onProjects = { openDrawerEntry(LocalFeatureDrawerEntry.PROJECT) },
                onOpenRunCenter = { openDrawerEntry(LocalFeatureDrawerEntry.RUN_CENTER) },
                groupMemberCount = shell.groupChat.members.size,
                onOpenGroupChat = { openDrawerEntry(LocalFeatureDrawerEntry.GROUP_CHAT) },
                onOpenPersonaGallery = { openDrawerEntry(LocalFeatureDrawerEntry.PERSONA_GALLERY) },
                onOpenDiary = { openDrawerEntry(LocalFeatureDrawerEntry.DIARY) },
                onTasks = { openDrawerEntry(LocalFeatureDrawerEntry.TASKS) },
                onTools = { openDrawerEntry(LocalFeatureDrawerEntry.TOOLS) },
                onSettings = { openDrawerEntry(LocalFeatureDrawerEntry.SETTINGS) },
            )
        },
    ) {
        LocalFeatureAnimatedHost(
            stack = featureStack,
            predictiveBackProgress = predictiveBackProgress.value,
            modifier = Modifier.fillMaxSize(),
        ) { renderedPage ->
            featureStateHolder.SaveableStateProvider(renderedPage.name) {
                LocalFeaturePageContent(renderedPage, featureContributions)
            }
        }
    }

    if (pendingWorkCapability != null) {
        LocalWorkCapabilitySheet(
            switching = workCapabilityConfirmed,
            failed = workCapabilityFailed,
            enabled = !shell.loading && localHarnessModeSwitchEnabled(shell.usageMode, shell.running),
            onContinue = {
                workCapabilityConfirmed = true
                workCapabilityFailed = false
                switchUsageMode(LocalUsageMode.WORK)
            },
            onDismiss = {
                pendingWorkCapability = null
                workCapabilityConfirmed = false
            },
        )
    }

    if (showNewSessionMode) {
        if (shell.usageMode == LocalUsageMode.CHAT && shell.groupChat.enabled) {
            GroupNewSessionDialog(
                onDismiss = { showNewSessionMode = false },
                onNewGroup = {
                    showNewSessionMode = false
                    showGroupSetup = true
                },
                onNewSingle = {
                    if (viewModel.createSingleChatSession()) {
                        showNewSessionMode = false
                        resetFeatureNavigation()
                    }
                },
            )
        } else {
            NewSessionModeDialog(
                usageMode = shell.usageMode,
                projectAvailable = projectRecoveryNotice == null,
                onDismiss = { showNewSessionMode = false },
                onSelect = { mode ->
                    if (viewModel.createSession(mode)) {
                        showNewSessionMode = false
                        resetFeatureNavigation()
                    }
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

    if (showGroupSetup) {
        GroupChatMemberPickerSheet(
            entries = gallery,
            currentIds = emptyList(),
            enabled = !shell.loading && !shell.running,
            onSave = { ids ->
                val created = viewModel.createGroupChatSession(ids)
                if (created) {
                    showGroupSetup = false
                    resetFeatureNavigation()
                }
                created
            },
            onDismiss = { showGroupSetup = false },
        )
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
                openDrawerEntry(LocalFeatureDrawerEntry.PERSONA_GALLERY_CONTINUE)
            },
            onDismiss = { showPersonaGallerySavePrompt = false },
        )
    }

}

package com.labteto.dshmobile.ui.screens.local


import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.labteto.dshmobile.ui.screens.tasks.TasksViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
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
import com.labteto.dshmobile.local.session.workHandoffDialogueCandidates
import com.labteto.dshmobile.local.session.workHandoffSummaryWithSelectedMessages
import com.labteto.dshmobile.ui.components.ConversationScrollShortcut
import com.labteto.dshmobile.ui.components.ConversationScrollTarget
import com.labteto.dshmobile.ui.shenyan.SyButton
import com.labteto.dshmobile.ui.shenyan.SyButtonVariant
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.components.rememberConversationScrollHint
import com.labteto.dshmobile.local.model.canQueryMiniMaxQuota
import com.labteto.dshmobile.ui.screens.settings.SettingsDestination
import com.labteto.dshmobile.ui.theme.BackgroundRegion
import com.labteto.dshmobile.ui.theme.DsAnimations
import com.shenyan.designsystem.foundation.LocalSyMotionTempo
import com.shenyan.designsystem.foundation.LocalSyReduceMotion
import com.shenyan.designsystem.foundation.SyMotion
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.shenyan.LocalSyPalette
import com.labteto.dshmobile.ui.shenyan.SyDimensions
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

/** Only a committed session can move the selected tab. */
@Suppress("UNUSED_PARAMETER")
internal fun localHarnessDrawerUsageMode(
    current: LocalUsageMode,
    pending: LocalUsageMode?,
): LocalUsageMode = current
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

/** Local Android home and feature host. */
@Composable
fun LocalHarnessScreen(
    requestedSessionId: String? = null,
    onSessionRequestConsumed: () -> Unit = {},
    onCheckUpdate: () -> Unit = {},
    updateStatus: String? = null,
    viewModel: LocalHarnessViewModel = hiltViewModel(),
) {
    val searchTaskViewModel: TasksViewModel = hiltViewModel()
    val searchTaskState by searchTaskViewModel.state.collectAsStateWithLifecycle()
    val shell by viewModel.shellState.collectAsStateWithLifecycle()
    val chatSurface by viewModel.chatSurfaceState.collectAsStateWithLifecycle()
    val activeModelProfile by viewModel.activeModelProfile.collectAsStateWithLifecycle()
    val networkSearchEnabled by viewModel.networkSearchEnabled.collectAsStateWithLifecycle()
    val sendFeedback by viewModel.sendFeedbackState.collectAsStateWithLifecycle()
    val gallery by viewModel.gallery.collectAsStateWithLifecycle()
    val transcriptHistory by viewModel.transcriptHistory.collectAsStateWithLifecycle()
    val pinnedSessionIds by viewModel.pinnedSessionIds.collectAsStateWithLifecycle()
    val sessionTitleOverrides by viewModel.sessionTitleOverrides.collectAsStateWithLifecycle()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    // Native drags remain finger-tracked; short, explicit animations handle
    // toolbar taps, completed navigation and returning from drawer pages.
    val drawerMotionSpec = tween<Float>(
        durationMillis = SyMotion.duration(
            SyMotion.sidebarMs, LocalSyReduceMotion.current, LocalSyMotionTempo.current,
        ),
        easing = SyMotion.ease,
    )
    @Suppress("DEPRECATION")
    suspend fun openDrawerPromptly() {
        drawerState.animateTo(DrawerValue.Open, drawerMotionSpec)
    }
    @Suppress("DEPRECATION")
    suspend fun closeDrawerPromptly() {
        drawerState.animateTo(DrawerValue.Closed, drawerMotionSpec)
    }
    var openGlobalSearchRequest by rememberSaveable { mutableIntStateOf(0) }
    val syWideViewport = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 1024
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val resources = LocalResources.current
    val drawerFocusManager = LocalFocusManager.current
    val drawerKeyboard = LocalSoftwareKeyboardController.current
    val modeIntroPreferences = remember(context) { context.getSharedPreferences("local_mode_intro", android.content.Context.MODE_PRIVATE) }
    val onboardingPreferences = remember(context) {
        context.getSharedPreferences("shenyan_first_launch", android.content.Context.MODE_PRIVATE)
    }
    var onboardingDecided by rememberSaveable { mutableStateOf(false) }
    var presentFirstLaunch by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(shell.loading, shell.sessions, activeModelProfile) {
        if (!onboardingDecided && !shell.loading) {
            presentFirstLaunch = shouldPresentFirstLaunch(
                dismissed = onboardingPreferences.getBoolean("completed", false),
                hasUserContent = shell.sessions.any { !it.blank },
                hasConfiguredModel = activeModelProfile != null,
            )
            onboardingDecided = true
        }
    }
    fun finishFirstLaunch() {
        onboardingPreferences.edit().putBoolean("completed", true).apply()
        presentFirstLaunch = false
    }
    var showNewSessionMode by rememberSaveable { mutableStateOf(false) }
    var newSessionOpenedFromDrawer by rememberSaveable { mutableStateOf(false) }
    var groupSetupOpenedFromDrawer by rememberSaveable { mutableStateOf(false) }
    var featureStack by rememberSaveable { mutableStateOf(localFeatureHome()) }
    var settledSessionId by rememberSaveable { mutableStateOf("") }
    var settledUsageMode by rememberSaveable { mutableStateOf("") }
    // Only explicit mode-tab selections keep the drawer and underlying feature in place.
    var drawerModeSwitchTarget by remember { mutableStateOf<LocalUsageMode?>(null) }
    // Foreground navigation is visual immediately; the heavy runtime restore may follow.
    // Never render the previously active transcript under the newly chosen session title.
    var pendingDrawerSessionId by remember { mutableStateOf<String?>(null) }
    var pendingDrawerPreview by remember { mutableStateOf<List<LocalHarnessMessage>>(emptyList()) }
    LaunchedEffect(pendingDrawerSessionId) {
        val target = pendingDrawerSessionId
        pendingDrawerPreview = emptyList()
        if (target != null) {
            val preview = runCatching { viewModel.previewSession(target) }.getOrDefault(emptyList())
            // Generation is checked by LaunchedEffect cancellation and exact identity.
            if (pendingDrawerSessionId == target) pendingDrawerPreview = preview
        }
    }
    var drawerFeatureOriginStack by rememberSaveable { mutableStateOf<List<String>?>(null) }
    var conversationOpenedFromDrawer by rememberSaveable { mutableStateOf(false) }
    var filesMode by rememberSaveable { mutableStateOf(LocalFilesMode.WORKSPACE) }
    var filesEntryRequest by rememberSaveable { mutableIntStateOf(0) }
    var requestedFilePath by rememberSaveable(shell.sessionId) { mutableStateOf<String?>(null) }
    var settingsDestination by rememberSaveable { mutableStateOf(SettingsDestination.ROOT) }
    var settingsEntryRequest by rememberSaveable { mutableIntStateOf(0) }
    var memorySourceSessionId by rememberSaveable { mutableStateOf<String?>(null) }
    var memorySourceMessageId by rememberSaveable { mutableStateOf<String?>(null) }
    var diarySourceSessionId by rememberSaveable { mutableStateOf("") }
    var diarySourceMessageId by rememberSaveable { mutableStateOf("") }
    var drawerGalleryTargetId by rememberSaveable { mutableStateOf<String?>(null) }
    var drawerGalleryEntryVersion by rememberSaveable { mutableIntStateOf(0) }
    var taskMode by rememberSaveable { mutableStateOf<AutomationMode?>(null) }
    var searchedTaskId by rememberSaveable { mutableStateOf<String?>(null) }
    var taskEntryRequest by rememberSaveable { mutableIntStateOf(0) }
    var composerHandoff by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    var showNativeDocuments by rememberSaveable(shell.sessionId) { mutableStateOf(false) }
    var installedSkillDisplayNames by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var pendingWorkCapability by rememberSaveable { mutableStateOf<String?>(null) }
    var workHandoffSourceSessionId by rememberSaveable { mutableStateOf("") }
    var workHandoffSummary by rememberSaveable { mutableStateOf("") }
    var workHandoffMessageIds by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    var workHandoffSearch by rememberSaveable { mutableStateOf("") }
    var workCapabilityConfirmed by rememberSaveable { mutableStateOf(false) }
    var workCapabilityFailed by rememberSaveable { mutableStateOf(false) }
    var workCapabilityOpenVersion by remember { mutableIntStateOf(0) }
    var workCapabilityConfiguring by rememberSaveable { mutableStateOf(false) }
    var githubConfiguredForHandoff by remember { mutableStateOf<Boolean?>(null) }
    var externalReadinessForHandoff by remember { mutableStateOf<Pair<Boolean?, Boolean?>>(null to null) }
    var deviceReadinessForHandoff by remember { mutableStateOf<Pair<Boolean?, Boolean?>>(null to null) }
    LaunchedEffect(workCapabilityOpenVersion, pendingWorkCapability != null, workCapabilityConfiguring) {
        if (pendingWorkCapability != null && !workCapabilityConfiguring) {
            githubConfiguredForHandoff = null
            externalReadinessForHandoff = null to null
            deviceReadinessForHandoff = viewModel.deviceReadinessForHandoff()
            githubConfiguredForHandoff = viewModel.githubConfiguredForHandoff()
            externalReadinessForHandoff = viewModel.externalReadinessForHandoff()
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (pendingWorkCapability != null && !workCapabilityConfiguring) workCapabilityOpenVersion += 1
    }
    var toolsStartAtPlugins by rememberSaveable { mutableStateOf(false) }
    var toolsStartAtSkills by rememberSaveable { mutableStateOf(false) }
    var showGroupSetup by rememberSaveable { mutableStateOf(false) }
    var showPersonaGallerySavePrompt by rememberSaveable { mutableStateOf(false) }
    var showNewPersona by rememberSaveable { mutableStateOf(false) }
    var modeIntro by remember { mutableStateOf<LocalUsageMode?>(null) }
    var pendingUsageMode by remember { mutableStateOf<LocalUsageMode?>(null) }
    val featurePage = localFeatureCurrent(featureStack)
    LaunchedEffect(featurePage, workCapabilityConfiguring) {
        if (workCapabilityConfiguring &&
            featurePage != LocalFeaturePage.TOOLS && featurePage != LocalFeaturePage.SETTINGS) {
            workCapabilityConfiguring = false
        }
    }
    LaunchedEffect(featurePage, shell.sessionId) {
        if (featurePage == LocalFeaturePage.HOME) {
            installedSkillDisplayNames = runCatching { viewModel.installedSkillDisplayNames() }
                .getOrDefault(emptyMap())
        }
    }
    val featureStateHolder = rememberSaveableStateHolder()
    val predictiveBackProgress = remember { Animatable(0f) }

    fun pushFeature(page: LocalFeaturePage) {
        if (page == LocalFeaturePage.WORKSPACE) filesEntryRequest++
        // Keep the drawer origin when opening nested feature pages.
        featureStack = localFeaturePush(featureStack, page)
    }

    val requestedDocumentRoute by viewModel.documentRoute.collectAsStateWithLifecycle()
    LaunchedEffect(requestedDocumentRoute) {
        val route = requestedDocumentRoute ?: return@LaunchedEffect
        val destination = when (route) {
            "route.home" -> LocalFeaturePage.HOME
            "route.persona_gallery" -> LocalFeaturePage.PERSONA_GALLERY
            "route.diary" -> LocalFeaturePage.DIARY
            "route.workspace" -> LocalFeaturePage.WORKSPACE
            "route.tasks" -> LocalFeaturePage.TASKS
            "route.tools" -> LocalFeaturePage.TOOLS
            "route.settings" -> LocalFeaturePage.SETTINGS
            else -> error("文档请求的页面不存在: $route")
        }
        showNativeDocuments = false
        if (destination == LocalFeaturePage.SETTINGS) {
            settingsDestination = SettingsDestination.ROOT
            settingsEntryRequest++
        }
        pushFeature(destination)
        viewModel.consumeDocumentRoute(route)
    }

    fun prepareSettingsEntry(destination: SettingsDestination) {
        memorySourceSessionId = null
        memorySourceMessageId = null
        settingsDestination = destination
        settingsEntryRequest++
    }

    fun openFeatureFromDrawer(page: LocalFeaturePage) {
        if (page == LocalFeaturePage.WORKSPACE) filesEntryRequest++
        conversationOpenedFromDrawer = false
        val navigation = localFeatureOpenFromDrawer(
            stack = featureStack,
            originStack = drawerFeatureOriginStack,
            page = page,
        )
        drawerFeatureOriginStack = navigation.originStack
        featureStack = navigation.stack
    }

    fun popFeature() {
        val back = localFeatureBack(featureStack, drawerFeatureOriginStack)
        featureStack = back.stack
        drawerFeatureOriginStack = back.drawerOriginStack
        if (back.reopenDrawer && !syWideViewport) scope.launch { openDrawerPromptly() }
    }

    fun reopenDrawerWhenModalReturns(fromDrawer: Boolean) {
        if (fromDrawer && !syWideViewport) scope.launch { openDrawerPromptly() }
    }

    fun resetFeatureNavigation() {
        drawerFeatureOriginStack = null
        LocalFeatureCatalog.routes
            .filterNot { it == LocalFeaturePage.HOME }
            .forEach { featureStateHolder.removeState(it.name) }
        featureStack = localFeatureHome()
    }

    fun switchToSessionAndShowConversation(
        sessionId: String,
        fromDrawer: Boolean = false,
        sourceMessageId: String = "",
    ): Boolean {
        // Selecting a concrete conversation always navigates to that conversation.
        // Drawer entries must still belong to the current session list.
        if (fromDrawer && shell.sessions.none { it.id == sessionId }) return false
        drawerModeSwitchTarget = null
        val accepted = viewModel.switchSession(sessionId)
        if (accepted) {
            conversationOpenedFromDrawer = fromDrawer
            diarySourceSessionId = if (sourceMessageId.isNotBlank()) sessionId else ""
            diarySourceMessageId = sourceMessageId
        }
        if (accepted && fromDrawer) {
            // Do not delay the drawer animation on Session IO, model-history replay,
            // control projections, or the next queued navigation request.
            pendingDrawerSessionId = sessionId.takeIf { it != shell.sessionId || shell.loading }
            resetFeatureNavigation()
            scope.launch { closeDrawerPromptly() }
        } else if (accepted && sessionId == shell.sessionId) {
            // Tapping the current conversation must return to its conversation surface.
            resetFeatureNavigation()
            scope.launch { closeDrawerPromptly() }
        }
        return accepted
    }

    LaunchedEffect(shell.sessionId, shell.loading, pendingDrawerSessionId, shell.sessions) {
        val pending = pendingDrawerSessionId ?: return@LaunchedEffect
        if ((shell.sessionId == pending && !shell.loading) ||
            (shell.sessions.none { it.id == pending } && !shell.loading)
        ) pendingDrawerSessionId = null
    }

    // Only a settled session/mode transition commits navigation. This covers every entry point,
    // including tasks, gallery, notifications and drawer history.
    LaunchedEffect(shell.sessionId, shell.usageMode, shell.loading) {
        if (!shell.loading && shell.sessionId.isNotBlank()) {
            if (settledSessionId.isNotBlank() &&
                (settledSessionId != shell.sessionId || settledUsageMode != shell.usageMode.name)
            ) {
                val keepDrawer = localModeTabPreservesNavigation(
                    requestedMode = drawerModeSwitchTarget,
                    previousModeName = settledUsageMode,
                    activeMode = shell.usageMode,
                )
                if (!keepDrawer) {
                    resetFeatureNavigation()
                    memorySourceSessionId = null
                    memorySourceMessageId = null
                    settingsDestination = SettingsDestination.ROOT
                    taskMode = null
                    searchedTaskId = null
                    requestedFilePath = null
                    filesMode = LocalFilesMode.WORKSPACE
                    composerHandoff = emptyList()
                    if (drawerState.isOpen) closeDrawerPromptly()
                }
                // Intermediate queued mode changes must not close the drawer either.
                if (!keepDrawer || drawerModeSwitchTarget == shell.usageMode) {
                    drawerModeSwitchTarget = null
                }
            }
            settledSessionId = shell.sessionId
            settledUsageMode = shell.usageMode.name
        }
    }

    suspend fun deleteSessionsAndNavigate(ids: Set<String>): Int {
        val deletingCurrent = shell.sessionId in ids
        return try {
            val deleted = viewModel.deleteSessions(ids)
            if (deleted > 0 && deletingCurrent) {
                resetFeatureNavigation()
                memorySourceSessionId = null
                memorySourceMessageId = null
                settingsDestination = SettingsDestination.ROOT
                taskMode = null
                filesMode = LocalFilesMode.WORKSPACE
                requestedFilePath = null
                composerHandoff = emptyList()
                pendingWorkCapability = null
                workCapabilityConfirmed = false
                workCapabilityConfiguring = false
                pendingUsageMode = null
                showGroupSetup = false
                showNewSessionMode = false
                closeDrawerPromptly()
            } else if (deleted == 0) {
                android.widget.Toast.makeText(
                    context, R.string.local_delete_session_failed, android.widget.Toast.LENGTH_SHORT,
                ).show()
            }
            deleted
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            android.widget.Toast.makeText(
                context, R.string.local_delete_session_failed, android.widget.Toast.LENGTH_SHORT,
            ).show()
            0
        }
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
            workHandoffSourceSessionId = shell.sessionId
            workHandoffSummary = viewModel.workHandoffSummary()
            workHandoffMessageIds = emptyList()
            workHandoffSearch = ""
            workCapabilityConfirmed = false
            workCapabilityFailed = false
            workCapabilityConfiguring = false
            githubConfiguredForHandoff = null
            workCapabilityOpenVersion += 1
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
        deleteSessions = ::deleteSessionsAndNavigate,
        configureChatPersona = viewModel::configureChatPersona,
        configureGroupMembers = viewModel::configureGroupChatMembers,
        selectGalleryPersona = viewModel::selectGalleryPersonaForCurrentChat,
        autoFillChatPersona = viewModel::autoFillChatPersona,
        saveGroupAnnouncement = viewModel::setGroupChatAnnouncement,
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
        usageRevision = viewModel.usageRevision,
        turnUsageSummaries = viewModel::turnUsageSummaries,
        turnUsage = viewModel::turnUsage,
        requestUsage = viewModel::requestUsage,
    )
    val chatActions = LocalChatFeatureUiActions(
        personaPresets = viewModel.personaPresets,
        diaryEntries = viewModel::diaryEntries,
        correctDiaryEntry = viewModel::correctDiaryEntry,
        deactivateDiaryEntry = viewModel::deactivateDiaryEntry,
        hasUnsavedCurrentPersona = viewModel::hasUnsavedCurrentPersona,
        currentGalleryHasUnsavedChanges = viewModel::currentGalleryHasUnsavedChanges,
        saveCurrentToGallery = viewModel::saveCurrentToGallery,
        editGalleryStoryDetails = viewModel::editGalleryStoryDetails,
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
        workspaceDirectory = viewModel::workspaceDirectory,
        workspaceDirectoryPage = viewModel::workspaceDirectoryPage,
        searchWorkspaceFiles = viewModel::searchWorkspaceFiles,
        searchWorkspacePage = viewModel::searchWorkspacePage,
        conversationFiles = viewModel::conversationFiles,
        previewWorkspaceFile = viewModel::previewWorkspaceFile,
        previewWorkspaceFilePage = viewModel::previewWorkspaceFilePage,
        artifactsForUi = viewModel::artifactsForUi,
        eventSequenceForUi = viewModel::eventSequenceForUi,
        artifactRevision = viewModel::artifactRevisionForUi,
        refreshArtifactFiles = viewModel::refreshArtifactFilesForUi,
        inspectArtifact = viewModel::inspectArtifactForUi,
        historyPage = viewModel::historyPageForUi,
        toolActivities = viewModel::toolActivitiesForUi,
        toolEvidence = viewModel::toolEvidenceForUi,
        requirementEvidence = viewModel::requirementEvidenceForUi,
        linkRequirementEvidence = viewModel::linkRequirementEvidenceForUi,
    )
    val automationActions = LocalAutomationFeatureUiActions(
        switchSession = { switchToSessionAndShowConversation(it) },
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
            gallery = gallery,
            transcriptHistory = transcriptHistory,
            transcriptJumpTarget = diarySourceMessageId.takeIf(String::isNotBlank)?.let { messageId ->
                LocalTranscriptJumpTarget(diarySourceSessionId, messageId)
            },
            onTranscriptJumpConsumed = { target ->
                if (diarySourceSessionId == target.sessionId && diarySourceMessageId == target.messageId) {
                    diarySourceSessionId = ""
                    diarySourceMessageId = ""
                }
            },
            modeIntro = modeIntro?.let(::LocalModeIntro),
            pinnedSessionIds = pinnedSessionIds,
            sessionTitleOverrides = sessionTitleOverrides,
            actions = shellActions,
            workActions = workActions,
            nativeDocuments = viewModel.nativeDocuments,
            onOpenWorkArtifact = { path ->
                requestedFilePath = path
                filesMode = LocalFilesMode.CONVERSATION
                pushFeature(LocalFeaturePage.WORKSPACE)
            },
            onSettingsDestinationChange = ::prepareSettingsEntry,
            onPushFeature = ::pushFeature,
            onNewSession = {
                newSessionOpenedFromDrawer = false
                showNewSessionMode = true
            },
            onOpenDrawer = { scope.launch { openDrawerPromptly() } },
            onOpenGlobalSearch = {
                openGlobalSearchRequest++
                scope.launch { openDrawerPromptly() }
            },
            onOpenPersonaGallery = {
                // Preserve the same unsaved-persona guard used by the drawer.
                if (viewModel.hasUnsavedCurrentPersona()) {
                    showPersonaGallerySavePrompt = true
                } else {
                    drawerGalleryTargetId = shell.galleryId
                    drawerGalleryEntryVersion++
                    pushFeature(LocalFeaturePage.PERSONA_GALLERY)
                }
            },
            onOpenNativeDocuments = { showNativeDocuments = true },
            onOpenWorkspaceFiles = {
                filesMode = LocalFilesMode.WORKSPACE
                requestedFilePath = null
                pushFeature(LocalFeaturePage.WORKSPACE)
            },
            onUseWorkCapability = ::useWorkCapability,
            onLocateMemoryForMessage = { messageId ->
                memorySourceSessionId = shell.sessionId
                memorySourceMessageId = messageId
                settingsDestination = SettingsDestination.MEMORY_MANAGEMENT
                settingsEntryRequest++
                pushFeature(LocalFeaturePage.SETTINGS)
            },
            composerHandoff = composerHandoff,
            skillDisplayNames = installedSkillDisplayNames,
            onConsumeComposerHandoff = { composerHandoff = emptyList() },
        ),
        localChatFeatureUiContribution(
            gallery = gallery,
            initialGalleryEntryId = drawerGalleryTargetId,
            galleryEntryOpenVersion = drawerGalleryEntryVersion,
            state = viewModel.chatSurfaceState,
            actions = chatActions,
            onNewPersona = { showNewPersona = true },
            onPopFeature = ::popFeature,
            sessions = shell.sessions,
            onSwitchSession = { sessionId, messageId ->
                switchToSessionAndShowConversation(sessionId, sourceMessageId = messageId)
            },
            onSwitchSessionFromDrawer = { switchToSessionAndShowConversation(it, fromDrawer = true) },
            onOpenGroupSetup = {
                groupSetupOpenedFromDrawer = true
                showGroupSetup = true
            },
            onPromptPersonaSave = { showPersonaGallerySavePrompt = true },
            onOpenFromDrawer = ::openFeatureFromDrawer,
            onCloseDrawer = { scope.launch { closeDrawerPromptly() } },
        ),
        localWorkFeatureUiContribution(
            filesMode = filesMode,
            requestedFilePath = requestedFilePath,
            entryRequest = filesEntryRequest,
            onRequestedFilePathChange = { requestedFilePath = it },
            onRequestedFilePathConsumed = { path ->
                if (requestedFilePath == path) requestedFilePath = null
            },
            shell = shell,
            actions = workActions,
            onFilesModeChange = { filesMode = it },
            onPopFeature = {
                memorySourceSessionId = null
                memorySourceMessageId = null
                popFeature()
            },
            onOpenFromDrawer = ::openFeatureFromDrawer,
            onCloseDrawer = { scope.launch { closeDrawerPromptly() } },
        ),
        localAutomationFeatureUiContribution(
            taskMode = taskMode,
            searchedTaskId = searchedTaskId,
            entryRequest = taskEntryRequest,
            onCreateViaChat = ::useWorkCapability,
            actions = automationActions,
            onTaskModeChange = { taskMode = it },
            onPopFeature = ::popFeature,
            onOpenFromDrawer = ::openFeatureFromDrawer,
            onCloseDrawer = { scope.launch { closeDrawerPromptly() } },
        ),
        localToolsFeatureUiContribution(
            startAtPlugins = toolsStartAtPlugins,
            startAtSkills = toolsStartAtSkills,
            onUseCapability = ::useWorkCapability,
            onSettingsDestinationChange = ::prepareSettingsEntry,
            onPushFeature = ::pushFeature,
            onPopFeature = { toolsStartAtPlugins = false; toolsStartAtSkills = false; popFeature() },
            onOpenFromDrawer = ::openFeatureFromDrawer,
            onCloseDrawer = { scope.launch { closeDrawerPromptly() } },
        ),
        localSettingsFeatureUiContribution(
            settingsDestination = settingsDestination,
            settingsEntryRequest = settingsEntryRequest,
            memorySourceSessionId = memorySourceSessionId,
            memorySourceMessageId = memorySourceMessageId,
            updateStatus = updateStatus,
            onCheckUpdate = onCheckUpdate,
            onSettingsDestinationChange = ::prepareSettingsEntry,
            onPopFeature = ::popFeature,
            onOpenFromDrawer = ::openFeatureFromDrawer,
            onCloseDrawer = { scope.launch { closeDrawerPromptly() } },
        ),
    )

    fun openDrawerEntry(entry: LocalFeatureDrawerEntry, galleryTargetId: String? = null) {
        if (entry == LocalFeatureDrawerEntry.TASKS) {
            searchedTaskId = null
            taskEntryRequest++
        }
        if (entry == LocalFeatureDrawerEntry.PERSONA_GALLERY ||
            entry == LocalFeatureDrawerEntry.PERSONA_GALLERY_CONTINUE
        ) {
            // All ordinary gallery entries must clear a previous search's selected persona.
            // Only the explicit search-result entry carries its own target.
            drawerGalleryTargetId = galleryTargetId
            drawerGalleryEntryVersion++
        }
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
    LaunchedEffect(drawerState.isOpen) {
        if (drawerState.isOpen) {
            drawerFocusManager.clearFocus(force = true)
            drawerKeyboard?.hide()
        }
    }

    fun switchUsageMode(target: LocalUsageMode) {
        val returningFromGroupToSingle =
            target == LocalUsageMode.CHAT &&
                shell.usageMode == LocalUsageMode.CHAT &&
                shell.groupChat.enabled
        if (target == shell.usageMode && !returningFromGroupToSingle && !shell.loading) {
            // This tap must cancel a previously queued switch back to the other mode.
            drawerModeSwitchTarget = null
            viewModel.switchUsageMode(target)
            return
        }
        if (target != shell.usageMode) {
            val key = "shown_" + target.name.lowercase()
            if (!modeIntroPreferences.getBoolean(key, false)) {
                modeIntroPreferences.edit().putBoolean(key, true).apply()
                modeIntro = target
            }
        }
        // Changing a mode tab filters the drawer's content, not the current feature page.
        // The session behind the drawer still changes, but the drawer stays visible.
        drawerModeSwitchTarget = target
        // Work capability handoff owns pendingUsageMode; ordinary taps use the
        // runtime navigation inbox, keeping the drawer tied to the real session.
        viewModel.switchUsageMode(target)
    }

    LaunchedEffect(requestedSessionId, shell.sessions, shell.loading, shell.sessionId) {
        val target = requestedSessionId?.takeIf(String::isNotBlank) ?: return@LaunchedEffect
        if (shell.loading || shell.sessionId.isBlank()) return@LaunchedEffect
        if (target == shell.sessionId) {
            resetFeatureNavigation()
            if (drawerState.isOpen) closeDrawerPromptly()
            onSessionRequestConsumed()
        } else if (shell.sessions.none { it.id == target }) {
            onSessionRequestConsumed()
            android.widget.Toast.makeText(
                context, R.string.local_session_target_missing, android.widget.Toast.LENGTH_SHORT,
            ).show()
        } else {
            acceptLocalSessionNavigation(
                currentSessionId = shell.sessionId,
                targetSessionId = target,
                sessions = shell.sessions,
                switchSession = viewModel::switchSession,
            )
        }
    }

    BackHandler(enabled = !syWideViewport && drawerState.isOpen) {
        scope.launch { closeDrawerPromptly() }
    }
    BackHandler(
        enabled = localConversationShouldReturnToDrawer(
            featurePage, drawerState.isOpen, syWideViewport, conversationOpenedFromDrawer,
        ),
    ) {
        conversationOpenedFromDrawer = false
        scope.launch { openDrawerPromptly() }
    }

    val reduceMotionForBackGesture = com.shenyan.designsystem.foundation.LocalSyReduceMotion.current
    PredictiveBackHandler(
        enabled = (syWideViewport || !drawerState.isOpen) && featurePage != LocalFeaturePage.HOME,
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
                if (reduceMotionForBackGesture) {
                    predictiveBackProgress.snapTo(0f)
                } else {
                    predictiveBackProgress.animateTo(
                        targetValue = 0f,
                        animationSpec = DsAnimations.predictiveBackSettle,
                    )
                }
            }
            throw cancelled
        }
    }

    // V5: only the mobile/tablet presentation is modal. At >=1024dp, real
    // sessions and business routes remain reachable through a persistent 258dp rail.
    val syDrawerContent: @Composable () -> Unit = {
            LocalModeDrawer(
                currentSessionId = shell.sessionId,
                sessions = shell.sessions,
                gallery = gallery,
                usageMode = localHarnessDrawerUsageMode(shell.usageMode, pendingUsageMode),
                modeSwitchEnabled = shell.sessionId.isNotBlank() &&
                    localHarnessModeSwitchEnabled(shell.usageMode, shell.running),
                drawerOpen = syWideViewport || drawerState.targetValue == DrawerValue.Open || drawerState.isOpen,
                globalSearchOpenRequest = openGlobalSearchRequest,
                pinnedSessionIds = pinnedSessionIds,
                sessionTitleOverrides = sessionTitleOverrides,
                currentGalleryId = shell.galleryId,
                currentGalleryStoryId = shell.galleryStoryId,
                currentPersonaName = shell.chatPersona.name,
                currentPersonaIdentity = shell.chatPersona.coreIdentity,
                workModelLabel = activeModelProfile?.displayName
                    ?: activeModelProfile?.model
                    ?: stringResource(R.string.local_model_setup),
                groupChatEnabled = shell.groupChat.enabled,
                running = shell.running,
                onUsageModeChange = {
                    switchUsageMode(it)
                },
                onNewSession = {
                    scope.launch { closeDrawerPromptly() }
                    newSessionOpenedFromDrawer = true
                    showNewSessionMode = true
                },
                onSwitchSession = { sessionId ->
                    switchToSessionAndShowConversation(sessionId, fromDrawer = true)
                },
                onDeleteSessions = ::deleteSessionsAndNavigate,
                onRenameSession = viewModel::renameSession,
                onTogglePinSession = viewModel::toggleSessionPinned,
                onWorkspaceFiles = { openDrawerEntry(LocalFeatureDrawerEntry.WORKSPACE) },
                searchTasks = searchTaskState.tasks,
                loadSearchFiles = viewModel::workspaceFiles,
                onSearchTask = { task ->
                    openDrawerEntry(LocalFeatureDrawerEntry.TASKS)
                    taskMode = task.mode
                    searchedTaskId = task.id
                },
                onSearchFile = { path ->
                    // The ordinary drawer action clears pending file selection. Set the exact
                    // search target AFTER that action so the workspace preview opens it.
                    openDrawerEntry(LocalFeatureDrawerEntry.WORKSPACE)
                    filesMode = LocalFilesMode.WORKSPACE
                    requestedFilePath = path
                },
                onOpenGroupChat = { openDrawerEntry(LocalFeatureDrawerEntry.GROUP_CHAT) },
                onOpenPersonaGallery = {
                    openDrawerEntry(LocalFeatureDrawerEntry.PERSONA_GALLERY)
                },
                onOpenPersonaSearchResult = { galleryId ->
                    openDrawerEntry(LocalFeatureDrawerEntry.PERSONA_GALLERY, galleryId)
                },
                onOpenDiary = { openDrawerEntry(LocalFeatureDrawerEntry.DIARY) },
                onTasks = { openDrawerEntry(LocalFeatureDrawerEntry.TASKS) },
                onTools = { openDrawerEntry(LocalFeatureDrawerEntry.TOOLS) },
                onSettings = { openDrawerEntry(LocalFeatureDrawerEntry.SETTINGS) },
                onUsage = { openDrawerEntry(LocalFeatureDrawerEntry.USAGE) },
                onMiniMaxQuota = if (activeModelProfile?.canQueryMiniMaxQuota() == true) {
                    { openDrawerEntry(LocalFeatureDrawerEntry.MINIMAX_QUOTA) }
                } else null,
            )
    }
    val sySceneContent: @Composable () -> Unit = {
        val waitingForSession = pendingDrawerSessionId?.takeIf {
            featurePage == LocalFeaturePage.HOME &&
                (shell.sessionId != it || shell.loading)
        }
        if (waitingForSession != null) {
            // Cheap, read-only rendering of the selected session's SQLite tail.
            // This page is never connected to the outgoing Chat/Work send handlers.
            Surface(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Text(
                        text = shell.sessions.firstOrNull { it.id == waitingForSession }?.title
                            ?.ifBlank { "会话" } ?: "会话",
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        style = DsType.headline17,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val previewItems = pendingDrawerPreview
                        .filter { it.role == "user" || it.role == "assistant" }
                        .takeLast(12)
                    val previewScroll = rememberLazyListState()
                    LaunchedEffect(waitingForSession, previewItems.lastOrNull()?.id) {
                        if (previewItems.isNotEmpty()) {
                            previewScroll.scrollToItem(previewItems.lastIndex)
                        }
                    }
                    LazyColumn(
                        state = previewScroll,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (previewItems.isEmpty()) {
                            item(key = "loading-conversation") {
                                Text("正在读取最近消息…", style = DsType.std14)
                            }
                        }
                        items(previewItems, key = { it.id }) { message ->
                            val fromUser = message.role == "user"
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = if (fromUser)
                                    Arrangement.End else Arrangement.Start,
                            ) {
                                Surface(
                                    modifier = Modifier.fillMaxWidth(0.92f),
                                    shape = RoundedCornerShape(12.dp),
                                    tonalElevation = if (fromUser) 2.dp else 0.dp,
                                ) {
                                    Text(
                                        text = message.content.take(1_500),
                                        modifier = Modifier.padding(12.dp),
                                        style = DsType.std14,
                                    )
                                }
                            }
                        }
                    }
                    Text(
                        text = "正在恢复会话运行状态…",
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        style = DsType.std14,
                    )
                }
            }
        } else {
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
    }
    if (syWideViewport) {
        Row(Modifier.fillMaxSize()) {
            syDrawerContent()
            Box(Modifier.weight(1f)) { sySceneContent() }
            // V5: native 318dp inspector reflects ACTUAL selected session and model.
            // Hidden on narrow widths and non-home routes to protect work/editor space.
            if (androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 1440 &&
                featurePage == LocalFeaturePage.HOME) {
                val syInk = LocalSyPalette.current
                Column(
                    modifier = Modifier.width(SyDimensions.inspector).fillMaxHeight()
                        .padding(12.dp),
                ) {
                    Text(stringResource(R.string.sy_v5_session_current), color = syInk.text, style = DsType.headline17)
                    Text(
                        text = shell.chatPersona.name.ifBlank { stringResource(R.string.sy_v5_persona_unbound) },
                        color = syInk.secondaryText,
                        style = DsType.std14,
                    )
                    Text(
                        text = activeModelProfile?.displayName
                            ?: activeModelProfile?.model
                            ?: stringResource(R.string.sy_v5_model_unconfigured),
                        color = syInk.tertiaryText,
                        style = DsType.small13,
                    )
                    Text(
                        text = if (shell.usageMode == LocalUsageMode.CHAT) stringResource(R.string.sy_v5_chat_mode) else stringResource(R.string.sy_v5_work_mode),
                        color = syInk.secondaryText,
                        style = DsType.small13,
                    )
                    SyButton(
                        text = stringResource(R.string.sy_v5_persona_data),
                        onClick = { openDrawerEntry(LocalFeatureDrawerEntry.PERSONA_GALLERY) },
                        variant = SyButtonVariant.Ghost,
                    )
                }
            }
        }
    } else {
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = syDrawerContent,
            scrimColor = LocalSyPalette.current.scrim,
        ) {
            sySceneContent()
        }
    }

    if (pendingWorkCapability != null && !workCapabilityConfiguring) {
        val handoffMessages = if (workHandoffSourceSessionId == shell.sessionId &&
            transcriptHistory.sessionId == shell.sessionId
        ) workHandoffDialogueCandidates(transcriptHistory.olderMessages, chatSurface.messages)
        else if (workHandoffSourceSessionId == shell.sessionId)
            workHandoffDialogueCandidates(emptyList(), chatSurface.messages)
        else emptyList()
        LocalWorkCapabilitySheet(
            switching = workCapabilityConfirmed,
            failed = workCapabilityFailed,
            enabled = !shell.loading && localHarnessModeSwitchEnabled(shell.usageMode, shell.running),
            prompt = pendingWorkCapability.orEmpty(),
            summary = workHandoffSummary,
            selectableMessages = handoffMessages,
            selectedMessageIds = workHandoffMessageIds,
            onSelectedMessageIdsChange = { workHandoffMessageIds = it },
            messageSearch = workHandoffSearch,
            onMessageSearchChange = { workHandoffSearch = it },
            hasEarlierMessages = workHandoffSourceSessionId == shell.sessionId &&
                transcriptHistory.sessionId == shell.sessionId && transcriptHistory.hasMore,
            loadingEarlierMessages = transcriptHistory.loading,
            onLoadEarlierMessages = {
                if (workHandoffSourceSessionId == shell.sessionId) {
                    scope.launch { viewModel.loadOlderTranscript(workHandoffSourceSessionId) }
                }
            },
            capabilities = viewModel.workHandoffCapabilityReadiness(
                task = pendingWorkCapability.orEmpty(),
                githubConfigured = githubConfiguredForHandoff,
                networkSearchEnabled = networkSearchEnabled,
                modelConfigured = chatSurface.configured,
                mcpToolsAvailable = externalReadinessForHandoff.first,
                pluginsInstalled = externalReadinessForHandoff.second,
                accessibilityActive = deviceReadinessForHandoff.first,
                notificationAccessActive = deviceReadinessForHandoff.second,
            ),
            onPromptChange = { pendingWorkCapability = it },
            onSummaryChange = { workHandoffSummary = it },
            onConfigureCapabilities = {
                workCapabilityConfiguring = true
                toolsStartAtPlugins = false
                toolsStartAtSkills = false
                pushFeature(LocalFeaturePage.TOOLS)
            },
            onRefreshCapabilities = { workCapabilityOpenVersion += 1 },
            onConfigureModel = {
                workCapabilityConfiguring = true
                prepareSettingsEntry(SettingsDestination.MODELS)
                pushFeature(LocalFeaturePage.SETTINGS)
            },
            onConfigureDevice = {
                workCapabilityConfiguring = true
                prepareSettingsEntry(SettingsDestination.PERMISSIONS)
                pushFeature(LocalFeaturePage.SETTINGS)
            },
            onContinue = {
                workCapabilityFailed = false
                workCapabilityConfirmed = viewModel.createWorkContinuation(
                    workHandoffSourceSessionId,
                    workHandoffSummaryWithSelectedMessages(
                        workHandoffSummary, workHandoffSourceSessionId, handoffMessages, workHandoffMessageIds,
                    ),
                )
                if (workCapabilityConfirmed) pendingUsageMode = LocalUsageMode.WORK
                else workCapabilityFailed = true
            },
            onDismiss = {
                pendingWorkCapability = null
                workCapabilityConfirmed = false
            },
        )
    }

    if (presentFirstLaunch && !shell.loading) {
        FirstLaunchOnboarding(
            onSkip = ::finishFirstLaunch,
            onStart = { selectedMode ->
                finishFirstLaunch()
                if (selectedMode != shell.usageMode) viewModel.switchUsageMode(selectedMode)
            },
            onConfigureModel = { _ ->
                finishFirstLaunch()
                // Model settings are global. Switching sessions here would complete later
                // and reset navigation after the user has already entered configuration.
                prepareSettingsEntry(SettingsDestination.MODELS)
                pushFeature(LocalFeaturePage.SETTINGS)
            },
        )
    }

    fun dismissNewSessionMode() {
        showNewSessionMode = false
        reopenDrawerWhenModalReturns(newSessionOpenedFromDrawer)
        newSessionOpenedFromDrawer = false
    }

    if (showNewSessionMode) {
        if (shell.usageMode == LocalUsageMode.CHAT && shell.groupChat.enabled) {
            GroupNewSessionDialog(
                onDismiss = ::dismissNewSessionMode,
                onNewGroup = {
                    showNewSessionMode = false
                    groupSetupOpenedFromDrawer = newSessionOpenedFromDrawer
                    newSessionOpenedFromDrawer = false
                    showGroupSetup = true
                },
                onNewSingle = {
                    if (viewModel.createSingleChatSession()) {
                        showNewSessionMode = false
                        conversationOpenedFromDrawer = newSessionOpenedFromDrawer
                        newSessionOpenedFromDrawer = false
                        // The settled session observer owns navigation reset.
                    }
                },
            )
        } else {
            NewSessionModeDialog(
                usageMode = shell.usageMode,
                onDismiss = ::dismissNewSessionMode,
                onSelect = { mode ->
                    if (viewModel.createSession(mode)) {
                        showNewSessionMode = false
                        conversationOpenedFromDrawer = newSessionOpenedFromDrawer
                        newSessionOpenedFromDrawer = false
                        // The settled session observer owns navigation reset.
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
                    conversationOpenedFromDrawer = groupSetupOpenedFromDrawer
                    groupSetupOpenedFromDrawer = false
                    // Wait for the committed group session before resetting feature navigation.
                }
                created
            },
            onDismiss = {
                showGroupSetup = false
                reopenDrawerWhenModalReturns(groupSetupOpenedFromDrawer)
                groupSetupOpenedFromDrawer = false
            },
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
            onDismiss = {
                showPersonaGallerySavePrompt = false
                reopenDrawerWhenModalReturns(true)
            },
        )
    }

    if (showNativeDocuments && shell.usageMode == LocalUsageMode.WORK && shell.sessionId.isNotBlank()) {
        NativeV6DocumentsSheet(
            port = viewModel.interactiveDocuments,
            forms = viewModel.documentForms,
            media = viewModel.nativeDocuments.media,
            identity = viewModel::documentIdentity,
            currentMessageId = viewModel::activeDocumentMessageId,
            workMode = shell.usageMode == LocalUsageMode.WORK,
            onPreparedDraft = {
                viewModel.consumePreparedDocumentDraft(shell.sessionId)?.let { text ->
                    composerHandoff = listOf(shell.sessionId, text)
                }
                showNativeDocuments = false
            },
            onDismiss = { showNativeDocuments = false },
        )
    }

}

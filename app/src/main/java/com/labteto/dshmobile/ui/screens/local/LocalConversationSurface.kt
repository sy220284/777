package com.labteto.dshmobile.ui.screens.local

import com.shenyan.designsystem.foundation.SyMetric

import com.shenyan.designsystem.components.SyMaterialTier

import com.shenyan.designsystem.components.SyMaterialSurface


import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
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
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.labteto.dshmobile.local.presentation.mediaCreativeDraft
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.attachment.LocalImportedAttachment
import com.labteto.dshmobile.local.chat.LocalChatUserEditResult
import com.labteto.dshmobile.local.chat.PersonaGalleryEntry
import com.labteto.dshmobile.local.chat.PersonaProfile
import com.labteto.dshmobile.local.presentation.chatBranchInfoIndex
import com.labteto.dshmobile.local.presentation.isUnboundChatPersona
import com.labteto.dshmobile.local.model.LocalHarnessStreamingState
import com.labteto.dshmobile.local.model.LocalMediaRevisionDraft
import com.labteto.dshmobile.local.model.visibleAssistantText
import com.labteto.dshmobile.local.model.LocalModelProfile
import com.labteto.dshmobile.local.model.LocalModelPresets
import com.labteto.dshmobile.local.presentation.LocalWorkTemperatureControls
import com.labteto.dshmobile.local.presentation.LocalModelPerformanceControls
import com.labteto.dshmobile.local.presentation.LocalReasoningUiMode
import com.labteto.dshmobile.local.presentation.LocalReasoningControls
import com.labteto.dshmobile.local.presentation.LocalConversationSurfaceState
import com.labteto.dshmobile.local.send.LocalSendFeedbackState
import com.labteto.dshmobile.local.send.LocalSendRejectReason
import com.labteto.dshmobile.local.send.LocalSendResult
import com.labteto.dshmobile.local.session.LocalConversationMode
import com.labteto.dshmobile.local.session.LocalHarnessMessage
import com.labteto.dshmobile.local.session.LocalMessageBlock
import com.labteto.dshmobile.local.session.visibleBlocks
import com.labteto.dshmobile.ui.components.ConversationScrollShortcut
import com.labteto.dshmobile.ui.components.ConversationScrollTarget
import com.labteto.dshmobile.ui.shenyan.SyResponsiveSheet
import com.labteto.dshmobile.ui.shenyan.SyMenuItem
import com.labteto.dshmobile.ui.shenyan.SyButton
import com.labteto.dshmobile.ui.shenyan.SyButtonSize
import com.labteto.dshmobile.ui.shenyan.SyButtonVariant
import com.labteto.dshmobile.ui.shenyan.SyGlassCard
import com.shenyan.designsystem.components.SyIconButton
import com.labteto.dshmobile.ui.shenyan.SyPopupMenu
import com.shenyan.designsystem.components.SySheetChoiceRow
import com.shenyan.designsystem.components.SyQuickActionTile
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.components.SyEntryIcons
import com.labteto.dshmobile.ui.components.rememberConversationScrollHint
import com.labteto.dshmobile.ui.theme.DsMetrics
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.LocalAppBackgroundState
import com.labteto.dshmobile.ui.shenyan.LocalSyPalette
import com.labteto.dshmobile.ui.theme.rootSurface
import com.labteto.dshmobile.ui.theme.withReadingWeight
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Hide platform filesystem paths while preserving actionable validation errors. */
internal fun localComposerAttachmentFailureMessage(error: Exception, fallback: String): String =
    if (error is java.io.IOException || error is SecurityException) fallback
    else error.message ?: fallback

/** Optional introduction is wrapped so Compose never tracks a nullable enum via ordinal(). */
internal data class LocalModeIntro(val usageMode: LocalUsageMode)
internal data class LocalTranscriptJumpTarget(val sessionId: String, val messageId: String)

/** Parameter boundary for the 61-argument Compose shell. Keeps the original public
 * callsite intact while cutting the renderer's DEX register pressure on API 36/37.
 * No mutable business state, run scheduling, permissions or model calls are duplicated.
 */
internal class LocalConversationSurfaceArgs(
    val state: LocalConversationSurfaceState,
    val workActions: LocalWorkFeatureUiActions?,
    val nativeDocuments: NativeV6Documents?,
    val onOpenWorkArtifact: (String) -> Unit,
    val activeModelProfile: LocalModelProfile?,
    val sendFeedback: LocalSendFeedbackState,
    val streamingState: StateFlow<LocalHarnessStreamingState>,
    val gallery: List<PersonaGalleryEntry>,
    val transcriptHistory: LocalTranscriptHistoryState,
    val transcriptJumpTarget: LocalTranscriptJumpTarget?,
    val onTranscriptJumpConsumed: (LocalTranscriptJumpTarget) -> Unit,
    val modeIntro: LocalModeIntro?,
    val onConfigure: () -> Unit,
    val onSelectModel: (String) -> Unit,
    val onSend: (String, List<LocalImportedAttachment>) -> LocalSendResult,
    val onSendTeam: (String, List<LocalImportedAttachment>) -> LocalSendResult,
    val onTeamMemberOutput: (String) -> String,
    val onSendTeamMemberMessage: suspend (String, String) -> LocalWorkUiActionResult,
    val onStopTeamMember: suspend (String) -> LocalWorkUiActionResult,
    val onStopTeam: suspend () -> LocalWorkUiActionResult,
    val onEditAndResend: suspend (String, String) -> LocalChatUserEditResult,
    val onLocateMemoryForMessage: (String) -> Unit,
    val onSelectMessageVariant: suspend (String, Int) -> Boolean,
    val onRegenerate: (String) -> Boolean,
    val onGenerateReplySuggestions: suspend () -> Boolean,
    val onLoadOlderTranscript: suspend (String) -> Result<Int>,
    val onImportAttachment: suspend (android.net.Uri) -> LocalImportedAttachment,
    val onStop: () -> Unit,
    val onNewSession: () -> Unit,
    val onExitGroupChat: () -> Unit,
    val sessionTitle: String,
    val sessionPinned: Boolean,
    val onTogglePinSession: () -> Unit,
    val onRenameSession: (String) -> Boolean,
    val onDeleteSession: () -> Unit,
    val onConfigureChatPersona: suspend (PersonaProfile) -> Result<Unit>,
    val onConfigureGroupMembers: (List<String>) -> Boolean,
    val onSelectGalleryPersona: (String) -> Boolean,
    val onAutoFillChatPersona: suspend (PersonaProfile, String) -> Result<PersonaProfile>,
    val onSaveGroupAnnouncement: suspend (String) -> Result<Unit>,
    val onUndoPersonaCorrection: (Long, String, String) -> Unit,
    val onPlanModeChange: (Boolean) -> Unit,
    val onApprove: (String) -> Unit,
    val onDeny: (String) -> Unit,
    val onAutoApprovePending: (String) -> Unit,
    val onApproveDeviceTurn: (String) -> Unit,
    val onDisableDeviceTurn: () -> Unit,
    val onAnswerQuestion: (String, String) -> Boolean,
    val onCancelQuestion: (String) -> Unit,
    val composerHandoff: List<String>,
    val skillDisplayNames: Map<String, String>,
    val onConsumeComposerHandoff: () -> Unit,
    val onOpenDrawer: (() -> Unit)?,
    val onOpenGlobalSearch: () -> Unit,
    val onOpenPersonaGallery: () -> Unit,
    val onOpenWorkspaceFiles: () -> Unit,
    val onOpenNativeDocuments: () -> Unit,
    val onUseWorkCapability: ((String) -> Unit)?,
    val usageRevision: StateFlow<Long>?,
    val loadTurnSummaries: (String) -> Map<String, com.labteto.dshmobile.local.TokenUsageAggregate>,
    val loadTurnUsage: (String, String) -> com.labteto.dshmobile.local.TokenUsageGroupDetail?,
    val loadUsageRequest: (String) -> com.labteto.dshmobile.local.TokenUsageRecord?,
)

@Composable
internal fun LocalConversationSurface(
    state: LocalConversationSurfaceState,
    workActions: LocalWorkFeatureUiActions? = null,
    nativeDocuments: NativeV6Documents? = null,
    onOpenWorkArtifact: (String) -> Unit = {},
    activeModelProfile: LocalModelProfile?,
    sendFeedback: LocalSendFeedbackState,
    streamingState: StateFlow<LocalHarnessStreamingState>,
    gallery: List<PersonaGalleryEntry>,
    transcriptHistory: LocalTranscriptHistoryState,
    transcriptJumpTarget: LocalTranscriptJumpTarget? = null,
    onTranscriptJumpConsumed: (LocalTranscriptJumpTarget) -> Unit = {},
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
    onLocateMemoryForMessage: (String) -> Unit = {},
    onSelectMessageVariant: suspend (String, Int) -> Boolean,
    onRegenerate: (String) -> Boolean,
    onGenerateReplySuggestions: suspend () -> Boolean,
    onLoadOlderTranscript: suspend (String) -> Result<Int>,
    onImportAttachment: suspend (android.net.Uri) -> LocalImportedAttachment,
    onStop: () -> Unit,
    onNewSession: () -> Unit,
    onExitGroupChat: () -> Unit,
    sessionTitle: String,
    sessionPinned: Boolean,
    onTogglePinSession: () -> Unit,
    onRenameSession: (String) -> Boolean,
    onDeleteSession: () -> Unit,
    onConfigureChatPersona: suspend (PersonaProfile) -> Result<Unit>,
    onConfigureGroupMembers: (List<String>) -> Boolean,
    onSelectGalleryPersona: (String) -> Boolean,
    onAutoFillChatPersona: suspend (PersonaProfile, String) -> Result<PersonaProfile>,
    onSaveGroupAnnouncement: suspend (String) -> Result<Unit>,
    onUndoPersonaCorrection: (Long, String, String) -> Unit,
    onPlanModeChange: (Boolean) -> Unit,
    onApprove: (String) -> Unit,
    onDeny: (String) -> Unit,
    onAutoApprovePending: (String) -> Unit,
    onApproveDeviceTurn: (String) -> Unit,
    onDisableDeviceTurn: () -> Unit,
    onAnswerQuestion: (String, String) -> Boolean,
    onCancelQuestion: (String) -> Unit,
    composerHandoff: List<String> = emptyList(),
    skillDisplayNames: Map<String, String> = emptyMap(),
    onConsumeComposerHandoff: () -> Unit = {},
    onOpenDrawer: (() -> Unit)? = null,
    onOpenGlobalSearch: () -> Unit = {},
    onOpenPersonaGallery: () -> Unit = {},
    onOpenWorkspaceFiles: () -> Unit = {},
    onOpenNativeDocuments: () -> Unit = {},
    onUseWorkCapability: ((String) -> Unit)? = null,
    usageRevision: StateFlow<Long>? = null,
    loadTurnSummaries: (String) -> Map<String, com.labteto.dshmobile.local.TokenUsageAggregate> = { emptyMap() },
    loadTurnUsage: (String, String) -> com.labteto.dshmobile.local.TokenUsageGroupDetail? = { _, _ -> null },
    loadUsageRequest: (String) -> com.labteto.dshmobile.local.TokenUsageRecord? = { null },
) {
    val args = LocalConversationSurfaceArgs(
        state = state,
        workActions = workActions,
        nativeDocuments = nativeDocuments,
        onOpenWorkArtifact = onOpenWorkArtifact,
        activeModelProfile = activeModelProfile,
        sendFeedback = sendFeedback,
        streamingState = streamingState,
        gallery = gallery,
        transcriptHistory = transcriptHistory,
        transcriptJumpTarget = transcriptJumpTarget,
        onTranscriptJumpConsumed = onTranscriptJumpConsumed,
        modeIntro = modeIntro,
        onConfigure = onConfigure,
        onSelectModel = onSelectModel,
        onSend = onSend,
        onSendTeam = onSendTeam,
        onTeamMemberOutput = onTeamMemberOutput,
        onSendTeamMemberMessage = onSendTeamMemberMessage,
        onStopTeamMember = onStopTeamMember,
        onStopTeam = onStopTeam,
        onEditAndResend = onEditAndResend,
        onLocateMemoryForMessage = onLocateMemoryForMessage,
        onSelectMessageVariant = onSelectMessageVariant,
        onRegenerate = onRegenerate,
        onGenerateReplySuggestions = onGenerateReplySuggestions,
        onLoadOlderTranscript = onLoadOlderTranscript,
        onImportAttachment = onImportAttachment,
        onStop = onStop,
        onNewSession = onNewSession,
        onExitGroupChat = onExitGroupChat,
        sessionTitle = sessionTitle,
        sessionPinned = sessionPinned,
        onTogglePinSession = onTogglePinSession,
        onRenameSession = onRenameSession,
        onDeleteSession = onDeleteSession,
        onConfigureChatPersona = onConfigureChatPersona,
        onConfigureGroupMembers = onConfigureGroupMembers,
        onSelectGalleryPersona = onSelectGalleryPersona,
        onAutoFillChatPersona = onAutoFillChatPersona,
        onSaveGroupAnnouncement = onSaveGroupAnnouncement,
        onUndoPersonaCorrection = onUndoPersonaCorrection,
        onPlanModeChange = onPlanModeChange,
        onApprove = onApprove,
        onDeny = onDeny,
        onAutoApprovePending = onAutoApprovePending,
        onApproveDeviceTurn = onApproveDeviceTurn,
        onDisableDeviceTurn = onDisableDeviceTurn,
        onAnswerQuestion = onAnswerQuestion,
        onCancelQuestion = onCancelQuestion,
        composerHandoff = composerHandoff,
        skillDisplayNames = skillDisplayNames,
        onConsumeComposerHandoff = onConsumeComposerHandoff,
        onOpenDrawer = onOpenDrawer,
        onOpenGlobalSearch = onOpenGlobalSearch,
        onOpenPersonaGallery = onOpenPersonaGallery,
        onOpenWorkspaceFiles = onOpenWorkspaceFiles,
        onOpenNativeDocuments = onOpenNativeDocuments,
        onUseWorkCapability = onUseWorkCapability,
        usageRevision = usageRevision,
        loadTurnSummaries = loadTurnSummaries,
        loadTurnUsage = loadTurnUsage,
        loadUsageRequest = loadUsageRequest,
    )
    LocalConversationSurfaceBody(args)
}

@Composable
private fun LocalConversationSurfaceBody(args: LocalConversationSurfaceArgs) {
    val state = args.state
    val workActions = args.workActions
    val nativeDocuments = args.nativeDocuments
    val nativeDocumentSnapshot by (nativeDocuments?.snapshots ?: kotlinx.coroutines.flow.flowOf(
        com.labteto.dshmobile.uicontract.UiDocumentsSceneSnapshot(null, emptyList(), false, null)
    )).collectAsStateWithLifecycle(initialValue = com.labteto.dshmobile.uicontract.UiDocumentsSceneSnapshot(null, emptyList(), false, null))
    val onOpenWorkArtifact = args.onOpenWorkArtifact
    val activeModelProfile = args.activeModelProfile
    val sendFeedback = args.sendFeedback
    val streamingState = args.streamingState
    val gallery = args.gallery
    val transcriptHistory = args.transcriptHistory
    val transcriptJumpTarget = args.transcriptJumpTarget
    val onTranscriptJumpConsumed = args.onTranscriptJumpConsumed
    val modeIntro = args.modeIntro
    val onConfigure = args.onConfigure
    val onSelectModel = args.onSelectModel
    val onSend = args.onSend
    val onSendTeam = args.onSendTeam
    val onTeamMemberOutput = args.onTeamMemberOutput
    val onSendTeamMemberMessage = args.onSendTeamMemberMessage
    val onStopTeamMember = args.onStopTeamMember
    val onStopTeam = args.onStopTeam
    val onEditAndResend = args.onEditAndResend
    val onLocateMemoryForMessage = args.onLocateMemoryForMessage
    val onSelectMessageVariant = args.onSelectMessageVariant
    val onRegenerate = args.onRegenerate
    val onGenerateReplySuggestions = args.onGenerateReplySuggestions
    val onLoadOlderTranscript = args.onLoadOlderTranscript
    val onImportAttachment = args.onImportAttachment
    val onStop = args.onStop
    val onNewSession = args.onNewSession
    val onExitGroupChat = args.onExitGroupChat
    val sessionTitle = args.sessionTitle
    val sessionPinned = args.sessionPinned
    val onTogglePinSession = args.onTogglePinSession
    val onRenameSession = args.onRenameSession
    val onDeleteSession = args.onDeleteSession
    val onConfigureChatPersona = args.onConfigureChatPersona
    val onConfigureGroupMembers = args.onConfigureGroupMembers
    val onSelectGalleryPersona = args.onSelectGalleryPersona
    val onAutoFillChatPersona = args.onAutoFillChatPersona
    val onSaveGroupAnnouncement = args.onSaveGroupAnnouncement
    val onUndoPersonaCorrection = args.onUndoPersonaCorrection
    val onPlanModeChange = args.onPlanModeChange
    val onApprove = args.onApprove
    val onDeny = args.onDeny
    val onAutoApprovePending = args.onAutoApprovePending
    val onApproveDeviceTurn = args.onApproveDeviceTurn
    val onDisableDeviceTurn = args.onDisableDeviceTurn
    val onAnswerQuestion = args.onAnswerQuestion
    val onCancelQuestion = args.onCancelQuestion
    val composerHandoff = args.composerHandoff
    val skillDisplayNames = args.skillDisplayNames
    val onConsumeComposerHandoff = args.onConsumeComposerHandoff
    val onOpenDrawer = args.onOpenDrawer
    val onOpenGlobalSearch = args.onOpenGlobalSearch
    val onOpenPersonaGallery = args.onOpenPersonaGallery
    val onOpenWorkspaceFiles = args.onOpenWorkspaceFiles
    val onOpenNativeDocuments = args.onOpenNativeDocuments
    val onUseWorkCapability = args.onUseWorkCapability
    val usageRevision = args.usageRevision
    val loadTurnSummaries = args.loadTurnSummaries
    val loadTurnUsage = args.loadTurnUsage
    val loadUsageRequest = args.loadUsageRequest

    val turnUsage = LocalChatTurnUsageController(
        sessionId = state.sessionId,
        enabled = state.usageMode == LocalUsageMode.CHAT,
        revision = usageRevision,
        loadSummaries = loadTurnSummaries,
        loadTurn = loadTurnUsage,
        loadRequest = loadUsageRequest,
    )
    val colors = com.shenyan.designsystem.foundation.LocalSyColors.current
    val rootSurfaceColor = colors.rootSurface()
    // Custom wallpapers remain visible behind the chat toolbar; work mode still gets its
    // stable root work surface from rootSurfaceColor above.
    // Background is painted once by the app scene, not in a separate header slab.
    val scope = rememberCoroutineScope()
    val appContext = LocalContext.current
    // Global ceiling changes must also refresh the exact per-model persona/Work projection.
    val performanceCeiling = LocalModelPerformanceControls.state.collectAsStateWithLifecycle().value.temperatureCeiling
    var reasoningMode by remember(state.sessionId, state.usageMode) {
        LocalReasoningControls.attach(appContext)
        mutableStateOf(LocalReasoningControls.mode(state.sessionId, state.usageMode))
    }
    val temperatureUi = localConversationTemperatureUi(activeModelProfile, reasoningMode == LocalReasoningUiMode.FAST)
    val workTemperatureRange = temperatureUi.range
    var workTemperatureLevel by remember(state.sessionId, activeModelProfile?.id, workTemperatureRange, performanceCeiling) {
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
    var attachmentImportProgress by remember(state.sessionId) { mutableStateOf<Pair<Int, Int>?>(null) }
    var showAttachmentPicker by rememberSaveable { mutableStateOf(false) }
    var showMediaStudio by rememberSaveable(state.sessionId) { mutableStateOf(false) }
    var documentGenerationMessageId by remember(state.sessionId) { mutableStateOf<String?>(null) }
    var mediaRevision by rememberSaveable(state.sessionId) { mutableStateOf<LocalMediaRevisionDraft?>(null) }
    var mediaInitialPrompt by rememberSaveable(state.sessionId) { mutableStateOf("") }
    var mediaSourceMessageId by rememberSaveable(state.sessionId) { mutableStateOf<String?>(null) }
    var mediaInitialKind by rememberSaveable(state.sessionId) {
        mutableStateOf(com.labteto.dshmobile.local.model.LocalMediaKind.IMAGE.name)
    }
    var teamDispatchSelected by rememberSaveable(state.sessionId) { mutableStateOf(false) }
    var teamLaunchPending by rememberSaveable(state.sessionId) { mutableStateOf(false) }
    var teamLaunchSawRunning by rememberSaveable(state.sessionId) { mutableStateOf(false) }
    var showTeamPanel by rememberSaveable(state.sessionId) { mutableStateOf(false) }
    var teamCardDismissed by rememberSaveable(state.sessionId) { mutableStateOf(false) }
    var approvalNoticeExpanded by rememberSaveable { mutableStateOf(false) }
    var showModelPicker by rememberSaveable { mutableStateOf(false) }
    val loadedSearchState = rememberV6LoadedConversationSearchState(state.sessionId)
    var showPersonaPicker by rememberSaveable { mutableStateOf(false) }
    var showWorkEvidence by rememberSaveable(state.sessionId) { mutableStateOf(false) }
    var showCharacterTuning by rememberSaveable(state.sessionId) { mutableStateOf(false) }
    var showGroupMemberPicker by rememberSaveable { mutableStateOf(false) }
    var showGroupAnnouncement by rememberSaveable { mutableStateOf(false) }
    var showPersonaEditor by rememberSaveable { mutableStateOf(false) }
    var personaEditorDraft by remember { mutableStateOf<PersonaProfile?>(null) }
    var showReplySuggestions by rememberSaveable { mutableStateOf(false) }
    var editingUserMessage by remember { mutableStateOf<LocalHarnessMessage?>(null) }
    var renameSessionOpen by rememberSaveable(state.sessionId) { mutableStateOf(false) }
    val attachmentDrafts = rememberSaveable(
        saver = listSaver(
            save = { cache: LocalSessionAttachmentDraftCache -> cache.save() },
            restore = { saved -> LocalSessionAttachmentDraftCache.restore(saved) },
        ),
    ) { LocalSessionAttachmentDraftCache() }
    val attachments = attachmentDrafts.forSession(state.sessionId)
    // Each conversation owns its scroll position. A shared list state can
    // flash the prior session's index before the new tail is positioned.
    val listState = androidx.compose.runtime.key(state.sessionId) { rememberLazyListState() }
    val workTailFollowing = remember(state.sessionId) { mutableStateOf(false) }
    val workReaderPaused = remember(state.sessionId) { mutableStateOf(false) }
    var composerTailViewportAnchor by remember(state.sessionId) { mutableStateOf<Int?>(null) }
    val (scrollHint, scrollConnection) = rememberConversationScrollHint(listState, reverseLayout = false)
    var transcriptInitialPositionReady by rememberSaveable(state.sessionId) { mutableStateOf(false) }
    var planReviewBusy by remember(state.pendingQuestion?.callId) { mutableStateOf(false) }
    val pagedOlderMessages = if (transcriptHistory.sessionId == state.sessionId) {
        transcriptHistory.olderMessages
    } else {
        emptyList()
    }
    // Preserve the initial visible-tail anchor while new streaming rows arrive.
    // The model/runtime can retain a larger hot window without composing it all.
    var visibleTailAnchorId by remember(state.sessionId) { mutableStateOf<String?>(null) }
    LaunchedEffect(state.sessionId, state.messages) {
        if (state.messages.isNotEmpty() &&
            (visibleTailAnchorId == null || state.messages.none { it.id == visibleTailAnchorId })
        ) {
            visibleTailAnchorId = state.messages
                .takeLast(LOCAL_TRANSCRIPT_INITIAL_VISIBLE_DIALOGUE_MESSAGES)
                .first().id
        }
    }
    val visibleLiveMessages = remember(state.messages, visibleTailAnchorId) {
        localTranscriptVisibleLiveWindow(
            state.messages, visibleTailAnchorId,
            LOCAL_TRANSCRIPT_INITIAL_VISIBLE_DIALOGUE_MESSAGES,
        )
    }
    val transcriptMessages = remember(pagedOlderMessages, visibleLiveMessages) {
        mergeLocalTranscriptHistory(pagedOlderMessages, visibleLiveMessages)
    }
    // Cold historical cards are read-only. They are not part of the live Owner snapshot.
    var olderDocumentSnapshots by remember(state.sessionId) {
        mutableStateOf<List<com.labteto.dshmobile.uicontract.UiDocumentSnapshot>>(emptyList())
    }
    LaunchedEffect(state.sessionId, state.usageMode, pagedOlderMessages, nativeDocuments) {
        val ids = pagedOlderMessages.filter { it.role == "assistant" ||
            state.usageMode == LocalUsageMode.WORK && it.role == "user" }.map { it.id }.toSet()
        olderDocumentSnapshots = if (state.usageMode != LocalUsageMode.WORK ||
            nativeDocuments == null || ids.isEmpty()) emptyList()
            else nativeDocuments.olderDocuments(state.sessionId, ids)
    }
    // Media is a persistent chat/Work timeline projection, not an assistant text message.
    val mediaStudioViewModel: LocalMediaStudioViewModel = hiltViewModel()
    var mediaLimit by rememberSaveable(state.sessionId) { mutableStateOf(com.labteto.dshmobile.local.presentation.LocalMediaCreationRuntime.PAGE_SIZE) }
    val mediaFlow = remember(state.sessionId, mediaLimit) { mediaStudioViewModel.sessionPage(state.sessionId, mediaLimit) }
    val mediaPage by mediaFlow.collectAsStateWithLifecycle(initialValue = com.labteto.dshmobile.local.model.LocalMediaPage())
    val generatedMedia = mediaPage.results
    val videoMediaTasks = mediaPage.videos
    val pendingMediaJobs = mediaPage.jobs
    var workToolActivities by remember(state.sessionId) {
        mutableStateOf<List<com.labteto.dshmobile.local.presentation.LocalToolActivityUiItem>>(emptyList())
    }
    var workArtifacts by remember(state.sessionId) {
        mutableStateOf<List<com.labteto.dshmobile.local.presentation.LocalArtifactUiItem>>(emptyList())
    }
    LaunchedEffect(state.sessionId, state.usageMode, workActions) {
        workArtifacts = emptyList()
        if (state.usageMode == LocalUsageMode.WORK && workActions != null) {
            var lastRevision: com.labteto.dshmobile.local.presentation.LocalWorkHistoryRevision? = null
            while (true) {
                try {
                    val (revision, next) = withContext(Dispatchers.IO) {
                        val current = workActions.artifactRevision(state.sessionId)
                        val items = if (current != lastRevision) workActions.artifactsForUi(state.sessionId) else workArtifacts
                        current to workActions.refreshArtifactFiles(state.sessionId, items)
                    }
                    workArtifacts = next
                    lastRevision = revision
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Keep already-visible deliverables; a transient read failure is not deletion.
                }
                delay(2500)
            }
        }
    }
    var workToolReadFailed by remember(state.sessionId, state.usageMode) { mutableStateOf(false) }
    var workToolRetry by remember(state.sessionId) { mutableStateOf(0) }
    val timelineLifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(state.sessionId, state.usageMode, workActions, state.running, workToolRetry, timelineLifecycle) {
        workToolReadFailed = false
        if (state.usageMode != LocalUsageMode.WORK || workActions == null) {
            workToolActivities = emptyList()
        } else timelineLifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val reader = LocalWorkToolActivityReader(
                sequence = { workActions.eventSequenceForUi(state.sessionId) },
                activities = { workActions.toolActivities(state.sessionId) },
            )
            do {
                when (val read = withContext(Dispatchers.IO) { reader.read() }) {
                    is LocalWorkToolActivityRead.Available -> {
                        workToolActivities = read.activities
                        workToolReadFailed = false
                    }
                    is LocalWorkToolActivityRead.Failed -> {
                        workToolActivities = emptyList()
                        workToolReadFailed = true
                        com.labteto.dshmobile.observability.AppLog.failure(
                            "WorkTimeline", "read_tool_activity", read.cause, sessionId = state.sessionId)
                        break // Retry is explicit; never replay tools or present stale live state.
                    }
                    null -> Unit
                }
                if (!state.running) break
                delay(600)
            } while (true)
        }
    }
    // Cache each projection stage independently: an active tool tick must not parse the
    // historical transcript or sort the entire media timeline again.
    val baseTranscript = remember(transcriptMessages, state.usageMode) {
        buildLocalTranscript(transcriptMessages, includeWorkProcess = state.usageMode == LocalUsageMode.WORK)
    }
    val mediaTimeline = remember(baseTranscript, state.sessionId, generatedMedia, videoMediaTasks, pendingMediaJobs) {
        mergeLocalMediaTimeline(baseTranscript, state.sessionId, generatedMedia, videoMediaTasks, pendingMediaJobs)
    }
    val withArtifacts = remember(mediaTimeline, state.usageMode, workArtifacts) {
        if (state.usageMode == LocalUsageMode.WORK) appendWorkArtifactsToTranscript(mediaTimeline, workArtifacts)
        else mediaTimeline
    }
    val withActivities = remember(withArtifacts, state.usageMode, workToolActivities, state.running) {
        if (state.usageMode == LocalUsageMode.WORK)
            appendPendingWorkToolActivities(withArtifacts, workToolActivities, state.running)
        else withArtifacts
    }
    val transcriptItems = remember(withActivities) { composeLocalReplyAttachments(withActivities) }
    val hasOlderTranscript = transcriptHistory.sessionId == state.sessionId &&
        transcriptHistory.hasMore
    val loadingOlderTranscript = transcriptHistory.sessionId == state.sessionId &&
        transcriptHistory.loading
    val transcriptHistoryError = transcriptHistory.error
        ?.takeIf { transcriptHistory.sessionId == state.sessionId }
    val showTranscriptPagingRow = transcriptHistoryError != null || hasOlderTranscript
    // A diary source may be in cold historical pages. Advance one durable page at a time
    // until the message is present, then scroll to the exact LazyColumn row.
    val locatingDiarySource = transcriptJumpTarget?.sessionId == state.sessionId &&
        !transcriptJumpTarget.messageId.isBlank()
    LaunchedEffect(transcriptJumpTarget, state.sessionId, transcriptItems, hasOlderTranscript,
        loadingOlderTranscript, transcriptHistoryError) {
        val target = transcriptJumpTarget?.takeIf { it.sessionId == state.sessionId }
            ?: return@LaunchedEffect
        if (state.usageMode == LocalUsageMode.WORK) workReaderPaused.value = true
        val messageIndex = transcriptItems.indexOfFirst { item ->
            item is LocalTranscriptItem.Message && item.message.id == target.messageId
        }
        when {
            messageIndex >= 0 -> {
                val leading = listOf(mediaPage.unavailable, mediaPage.hasMore,
                    showTranscriptPagingRow, false, false, false).count { it }
                // Empty-state rows and the plan review card cannot coexist with found messages.
                listState.scrollToItem(leading + messageIndex)
                transcriptInitialPositionReady = true
                onTranscriptJumpConsumed(target)
            }
            loadingOlderTranscript -> Unit
            hasOlderTranscript && transcriptHistoryError == null -> {
                val result = onLoadOlderTranscript(state.sessionId)
                if (result.isFailure || result.getOrNull() == 0) {
                    transcriptInitialPositionReady = true
                    onTranscriptJumpConsumed(target)
                }
            }
            else -> {
                transcriptInitialPositionReady = true
                onTranscriptJumpConsumed(target)
            }
        }
    }
    val showEmptyWorkbench = shouldShowEmptyLocalHarness(
        usageMode = state.usageMode,
        transcriptEmpty = transcriptItems.isEmpty(),
    )
    val showChatPersonaWelcome = shouldShowChatPersonaWelcome(
        usageMode = state.usageMode,
        transcriptEmpty = transcriptItems.isEmpty(),
        groupChatEnabled = state.groupChat.enabled,
        personaUnbound = state.chatPersona.isUnboundChatPersona(),
    )
    val showEmptyGuide = showEmptyWorkbench || showChatPersonaWelcome
    val showEmptyGroupSetup = transcriptItems.isEmpty() &&
        state.usageMode == LocalUsageMode.CHAT && state.groupChat.enabled &&
        state.groupChat.members.size < 2
    val planReview = state.pendingQuestion
        ?.takeIf { state.usageMode == LocalUsageMode.WORK }
        ?.let(::localPlanReviewOf)
    val showStreamingTail = shouldShowWorkStreamingTail(
        running = state.running,
        usageMode = state.usageMode,
        lastItem = localReplyStreamingBoundary(transcriptItems),
    )
    val transcriptLastListIndex = localTranscriptLastListIndex(
        transcriptItemCount = transcriptItems.size,
        showPagingRow = showTranscriptPagingRow,
        showEmptyWorkbench = showEmptyGuide,
        showEmptyGroupSetup = showEmptyGroupSetup,
        showPlanReview = planReview != null,
        showStreamingTail = showStreamingTail,
    )
    val messageEditingEnabled = true
    val messageBranchingEnabled = state.usageMode == LocalUsageMode.CHAT
    // One branch lookup index per changed graph; row composition must stay O(1).
    val branchInfoByMessageId = remember(state.chatBranches, messageBranchingEnabled, state.groupChat.enabled) {
        if (messageBranchingEnabled && !state.groupChat.enabled) chatBranchInfoIndex(state.chatBranches)
        else emptyMap()
    }
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
    val generatedImageEditPrompt = stringResource(R.string.media_chat_edit_generated_image_prompt)
    val generatedImageMissingMessage = stringResource(R.string.media_chat_edit_generated_image_missing)
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
                    attachmentError = localComposerAttachmentFailureMessage(error, imageImportFailedMessage)
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
                val chosen = uris.take(available)
                attachmentImportProgress = 0 to chosen.size
                var failure: String? = if (uris.size > available) imageLimitMessage else null
                try {
                    chosen.forEachIndexed { index, uri ->
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
                            failure = localComposerAttachmentFailureMessage(error, imageImportFailedMessage)
                        } finally {
                            attachmentImportProgress = (index + 1) to chosen.size
                        }
                    }
                    attachmentError = failure
                } finally {
                    attachmentImportProgress = null
                }
            }
        }
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { selected ->
        val uris = selected.distinct()
        if (uris.isNotEmpty()) {
            scope.launch {
                attachmentError = null
                attachmentImportProgress = 0 to uris.size
                var failed = 0
                var firstFailure: String? = null
                try {
                    uris.forEachIndexed { index, uri ->
                        try {
                            val imported = onImportAttachment(uri)
                            when (localComposerAttachmentDecision(attachments, imported)) {
                                LocalComposerAttachmentDecision.ACCEPT -> attachments += imported
                                LocalComposerAttachmentDecision.DUPLICATE -> Unit
                                LocalComposerAttachmentDecision.IMAGE_LIMIT -> {
                                    failed++
                                    if (firstFailure == null) firstFailure = imageLimitMessage
                                }
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            failed++
                            if (firstFailure == null) {
                                firstFailure = localComposerAttachmentFailureMessage(error, fileImportFailedMessage)
                            }
                        } finally {
                            attachmentImportProgress = (index + 1) to uris.size
                        }
                    }
                    attachmentError = if (failed == 0) null else
                        "有 $failed 个附件未能添加：${firstFailure ?: fileImportFailedMessage}"
                } finally {
                    attachmentImportProgress = null
                }
            }
        }
    }

    LaunchedEffect(state.sessionId) {
        transcriptInitialPositionReady = false
        scrollHint.hide()
        // Keep the session-scoped cached attachment draft across navigation.
        attachmentError = null
        showReplySuggestions = false
    }
    LocalTranscriptInitialPositioner(
        sessionId = state.sessionId,
        tailIndex = transcriptLastListIndex,
        tailKey = transcriptItems.lastOrNull()?.key.orEmpty(),
        enabled = !transcriptInitialPositionReady && !locatingDiarySource && !workReaderPaused.value,
        onPosition = { index ->
            listState.scrollToItem(index)
            if (state.usageMode == LocalUsageMode.WORK) listState.alignWorkTimelineTailAfterScroll(index)
        },
        onReady = { transcriptInitialPositionReady = true },
    )

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
        enabled = showStreamingTail && !workTailFollowing.value &&
            (state.usageMode != LocalUsageMode.WORK || !workReaderPaused.value),
    )
    if (state.usageMode == LocalUsageMode.WORK) {
        LocalWorkTimelineAutoScroller(
            listState = listState,
            sessionId = state.sessionId,
            tailIndex = transcriptLastListIndex,
            tailKey = if (showStreamingTail) "work-streaming:${state.sessionId}"
                else transcriptItems.lastOrNull()?.key.orEmpty(),
            isReady = transcriptInitialPositionReady,
            isCatchingUp = workTailFollowing,
            isReaderPaused = workReaderPaused,
        )
    } else {
        // Chat mode retains its established scrolling behavior.
        var previousChatTailIndex by remember(state.sessionId) { mutableStateOf(-1) }
        LaunchedEffect(state.messages.size, transcriptItems.size, state.running) {
            if (transcriptItems.isNotEmpty()) {
                val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                val wasNearTail = previousChatTailIndex < 0 ||
                    lastVisible >= previousChatTailIndex - 2
                previousChatTailIndex = transcriptLastListIndex
                if (wasNearTail && !locatingDiarySource) listState.animateScrollToItem(transcriptLastListIndex)
            }
        }
    }

    Column(Modifier.fillMaxSize().background(rootSurfaceColor).safeDrawingPadding().imePadding()) {
        Column(
            Modifier.fillMaxWidth()
                .padding(horizontal = DsMetrics.screenHorizontal, vertical = SyMetric.gapMd),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onOpenDrawer != null) SyIconButton(
                    icon = FeatherIcons.Menu,
                    contentDescription = stringResource(R.string.app_open_navigation),
                    onClick = onOpenDrawer,
                    tint = colors.text,
                    bare = true,
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
                            modelLabel = state.model,
                            onModelClick = {
                                if (state.modelProfiles.isNotEmpty()) showModelPicker = true else onConfigure()
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
                            modelLabel = state.model,
                            configured = state.configured,
                            running = state.running,
                            onModelClick = {
                                if (state.configured) showModelPicker = true else onConfigure()
                            },
                            onNewSession = onNewSession,
                            sessionPinned = sessionPinned,
                            onTogglePin = onTogglePinSession,
                            onRenameSession = { renameSessionOpen = true },
                            onDeleteSession = onDeleteSession,
                            onOpenWorkEvidence = workActions?.let { { showWorkEvidence = true } },
                            onOpenDocuments = if (state.sessionId.isNotBlank() && !state.loading) onOpenNativeDocuments else null,
                        )
                    }
                }
                // Search lives on V6's secondary navigation row to protect
                // the 393dp top bar from clipping current persona/session actions.
                // 隐藏大卡片后仍可从顶部直接查看，不占用聊天记录和输入区。
                if (state.usageMode == LocalUsageMode.WORK &&
                    teamCardDismissed && state.team.visible) {
                    SyIconButton(
                        icon = com.labteto.dshmobile.ui.components.SyEntryIcons.GroupChat,
                        contentDescription = stringResource(R.string.local_team_reopen),
                        onClick = { showTeamPanel = true },
                        tint = colors.textSecondary,
                    )
                }
            }
        }

        LocalModeIntroBanner(modeIntro)

        if (state.usageMode == LocalUsageMode.CHAT && state.groupChat.enabled) {
            GroupAnnouncementCard(
                announcement = state.groupChat.announcement,
                onClick = { showGroupAnnouncement = true },
            )
        }

        state.personaCorrectionNotice?.takeIf {
            state.usageMode == LocalUsageMode.CHAT && !state.groupChat.enabled
        }?.let { notice ->
            Column(
                modifier = Modifier.fillMaxWidth()
                    .padding(horizontal = SyMetric.gapLg, vertical = SyMetric.gapXs),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = SyMetric.gapLg, vertical = SyMetric.gapMd),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(SyMetric.gapMd),
                ) {
                    Text(
                        stringResource(R.string.local_persona_correction_recorded),
                        style = DsType.small13.withReadingWeight(),
                        color = colors.textSecondary,
                        modifier = Modifier.weight(1f),
                    )
                    SyButton(
                        text = stringResource(R.string.local_persona_correction_undo),
                        onClick = {
                            onUndoPersonaCorrection(notice.id, notice.personaId, notice.correction)
                        },
                        variant = SyButtonVariant.Ghost,
                        size = SyButtonSize.Small,
                    )
                }
            }
        }

        if (state.usageMode == LocalUsageMode.WORK && state.deviceApprovalLease) {
            Surface(
                color = colors.warning.copy(alpha = .18f),
                shape = DsShapes.block,
                modifier = Modifier.fillMaxWidth()
                    .padding(horizontal = SyMetric.gapLg, vertical = SyMetric.gapXs),
            ) {
                Row(
                    Modifier.padding(horizontal = SyMetric.gapLg, vertical = SyMetric.gapMd),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(SyMetric.gapMd),
                ) {
                    Column(
                        Modifier.weight(1f)
                            .heightIn(min = SyMetric.touch)
                            .clickable { approvalNoticeExpanded = !approvalNoticeExpanded },
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            stringResource(R.string.local_device_approval_on),
                            style = DsType.small13Strong.withReadingWeight(),
                            color = colors.warningInk,
                        )
                        Text(
                            if (approvalNoticeExpanded) stringResource(R.string.local_approval_scope_hide)
                            else stringResource(R.string.local_approval_scope_show),
                            style = DsType.caption11.withReadingWeight(),
                            color = colors.textSecondary,
                        )
                    }
                    SyButton(
                        stringResource(R.string.common_close),
                        onDisableDeviceTurn,
                        modifier = Modifier.heightIn(min = SyMetric.touch),
                        variant = SyButtonVariant.Ghost,
                    )
                }
                if (approvalNoticeExpanded) {
                    Text(
                        stringResource(R.string.local_device_approval_scope),
                        style = DsType.caption11.withReadingWeight(),
                        color = colors.textSecondary,
                    )
                }
            }
        }

        if (state.groupChat.enabled && state.groupChat.failedReplyMemberIds.isNotEmpty()) {
            GroupReplyFailureNotice(state.groupChat, state.running) { request -> onSend(request, emptyList()) }
        }
        Box(Modifier.weight(1f).fillMaxWidth().nestedScroll(scrollConnection)) {
            val transcriptBody: @Composable () -> Unit = {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = SyMetric.gapLg,
                    end = SyMetric.gapLg,
                    top = SyMetric.gapSection,
                    bottom = SyMetric.gapPage,
                ),
                verticalArrangement = Arrangement.spacedBy(
                    if (state.usageMode == LocalUsageMode.WORK) 0.dp else SyMetric.gapSection,
                ),
            ) {
                if (mediaPage.unavailable) {
                    item(key = "media-storage-error") {
                        SyButton(text = stringResource(R.string.media_retry_storage),
                            onClick = { scope.launch { mediaStudioViewModel.retryStorage() } })
                    }
                }
                if (mediaPage.hasMore) {
                    item(key = "older-media-results") {
                        SyButton(text = stringResource(R.string.media_load_older),
                            onClick = { mediaLimit = Math.addExact(mediaLimit, com.labteto.dshmobile.local.presentation.LocalMediaCreationRuntime.PAGE_SIZE) })
                    }
                }
                if (showTranscriptPagingRow) {
                    item(key = "local-transcript-history-status") {
                        LocalTranscriptPagingStatus(
                            loading = loadingOlderTranscript,
                            error = transcriptHistoryError,
                            hasMore = hasOlderTranscript,
                            onRetry = {
                                scope.launch { onLoadOlderTranscript(state.sessionId) }
                            },
                        )
                    }
                }
                if (showEmptyGuide) {
                    item(key = "local-empty-guide") {
                        Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                            if (showChatPersonaWelcome) {
                                Text(
                                    stringResource(R.string.local_chat_persona_welcome_title),
                                    style = DsType.large20.withReadingWeight(),
                                    color = colors.text,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 32.dp)
                                        .widthIn(max = 280.dp),
                                )
                            } else {
                                EmptyLocalHarness { suggestion ->
                                    drafts.putBoundedLocalDraft(state.sessionId, suggestion)
                                }
                            }
                        }
                    }
                }
                if (showEmptyGroupSetup) {
                    item {
                        SyGlassCard {
                            Text(
                                stringResource(R.string.local_group_chat_setup_required),
                                style = DsType.std14.withReadingWeight(),
                                color = colors.textSecondary,
                            )
                            SyButton(
                                text = stringResource(R.string.local_group_chat_manage_members),
                                onClick = { showGroupMemberPicker = true },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
                planReview?.let { review ->
                    item(key = "pending-plan-review") {
                        LocalPlanReviewCard(
                            review = review,
                            busy = planReviewBusy,
                            onApprove = {
                                if (!planReviewBusy) {
                                    val callId = state.pendingQuestion?.callId
                                    if (callId != null && onAnswerQuestion(callId, review.approve)) planReviewBusy = true
                                }
                            },
                            onDecline = {
                                review.decline?.let { decline ->
                                    if (!planReviewBusy) {
                                        val callId = state.pendingQuestion?.callId
                                        if (callId != null && onAnswerQuestion(callId, decline)) planReviewBusy = true
                                    }
                                }
                            },
                            onRegenerate = {
                                val callId = state.pendingQuestion?.callId
                                if (!planReviewBusy && callId != null && onAnswerQuestion(callId, review.regenerate)) {
                                    planReviewBusy = true
                                }
                            },
                        )
                    }
                }
                items(transcriptItems, key = { it.key }) { transcriptItem ->
                    when (transcriptItem) {
                        is LocalTranscriptItem.Message -> Column(
                            modifier = Modifier.padding(vertical =
                                if (state.usageMode == LocalUsageMode.WORK) SyMetric.gapMd else 0.dp),
                        ) {
                            LocalMessageRow(
                                message = if (transcriptItem.message.role == "assistant")
                                    transcriptItem.message.copy(content = visibleAssistantText(transcriptItem.message.content))
                                else transcriptItem.message,
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
                                    branchInfoByMessageId[transcriptItem.message.id]
                                } else {
                                    null
                                },
                                onEdit = { message -> editingUserMessage = message },
                                onEditGeneratedImage = { message ->
                                    val generated = editableGeneratedImage(message.visibleBlocks())
                                    val reusable = generated?.let { candidate ->
                                        // Reuse the already saved local attachment; never re-download
                                        // an untrusted URL or copy the image bytes into prompt/history.
                                        val original = resolveLocalTranscriptFile(state.workspacePath, candidate.relativePath)
                                        if (original?.isFile == true && original.length() > 0L) {
                                            LocalImportedAttachment(
                                                name = candidate.name, relativePath = candidate.relativePath,
                                                mediaType = candidate.mediaType, bytes = original.length(),
                                                attachmentId = candidate.attachmentId,
                                                width = candidate.width, height = candidate.height,
                                            )
                                        } else null
                                    }
                                    if (reusable == null) {
                                        attachmentError = generatedImageMissingMessage
                                    } else {
                                        attachmentError = when (localComposerAttachmentDecision(attachments, reusable)) {
                                            LocalComposerAttachmentDecision.ACCEPT -> {
                                                attachments += reusable
                                                val current = drafts[state.sessionId].orEmpty()
                                                drafts.putBoundedLocalDraft(state.sessionId,
                                                    listOf(generatedImageEditPrompt, current)
                                                        .filter(String::isNotBlank).joinToString("\n\n"))
                                                null
                                            }
                                            LocalComposerAttachmentDecision.DUPLICATE -> null
                                            LocalComposerAttachmentDecision.IMAGE_LIMIT -> imageLimitMessage
                                        }
                                    }
                                },
                                onCreateMedia = { message, kind ->
                                    mediaRevision = null
                                    mediaSourceMessageId = message.id
                                    mediaInitialPrompt = mediaCreativeDraft(
                                        kind, message.content,
                                        if (state.usageMode == LocalUsageMode.CHAT && !state.groupChat.enabled) state.chatPersona.name else "",
                                        if (state.usageMode == LocalUsageMode.CHAT && !state.groupChat.enabled) state.chatPersona.coreIdentity else "",
                                    )
                                    mediaInitialKind = kind.name
                                    showMediaStudio = true
                                },
                                onConvertToWork = if (state.usageMode == LocalUsageMode.CHAT &&
                                    onUseWorkCapability != null && !state.loading && !state.running
                                ) { message -> onUseWorkCapability(message.content) } else null,
                                 onCreateDocument = if (state.usageMode == LocalUsageMode.WORK &&
                                     nativeDocuments != null && messageActionsEnabled &&
                                    transcriptItem.message.role == "assistant" &&
                                    state.messages.any { it.id == transcriptItem.message.id }
                                ) { message -> documentGenerationMessageId = message.id } else null,
                                onLocateSourceMemory = { message -> onLocateMemoryForMessage(message.id) },
                                onSelectVariant = onSelectMessageVariant,
                                onRegenerate = onRegenerate,
                            )
                            val isHistoricalDocumentSource = state.messages.none { it.id == transcriptItem.message.id }
                            val inlineDocuments = nativeV6DocumentsForMessage(
                                messageId = transcriptItem.message.id,
                                role = transcriptItem.message.role,
                                workMode = state.usageMode == LocalUsageMode.WORK,
                                snapshots = if (isHistoricalDocumentSource) olderDocumentSnapshots
                                    else nativeDocumentSnapshot.documents,
                            )
                            if (nativeDocuments != null &&
                                (nativeDocumentSnapshot.sessionId == state.sessionId || isHistoricalDocumentSource) &&
                                inlineDocuments.isNotEmpty()) {
                                NativeV6DocumentMessage(
                                    messageId = transcriptItem.message.id,
                                    documents = inlineDocuments,
                                    readOnly = isHistoricalDocumentSource,
                                    controller = nativeDocuments,
                                    onPreparedDraft = {
                                        nativeDocuments.consumePreparedDraft(state.sessionId)?.let { prepared ->
                                            val previous = drafts[state.sessionId].orEmpty()
                                            drafts.putBoundedLocalDraft(
                                                state.sessionId,
                                                listOf(prepared, previous).filter(String::isNotBlank).joinToString("\n\n"),
                                            )
                                        }
                                    },
                                )
                            }
                            if (state.usageMode == LocalUsageMode.CHAT && transcriptItem.message.role == "user") {
                                turnUsage.summaries[transcriptItem.message.id]?.let { aggregate ->
                                    LocalChatTurnUsageButton(aggregate) { turnUsage.open(transcriptItem.message.id) }
                                }
                            }
                        }
                        is LocalTranscriptItem.MediaResult ->
                            if (transcriptItem.groupedResults.size >= 2) {
                                LocalReplyImageGallery(
                                    results = transcriptItem.groupedResults,
                                    viewModel = mediaStudioViewModel,
                                )
                            } else LocalGeneratedMediaResultRow(
                            result = transcriptItem.result,
                            inline = transcriptItem.attachedTo != null,
                            viewModel = mediaStudioViewModel,
                            onRevise = { draft ->
                                mediaRevision = draft
                                mediaInitialPrompt = draft.prompt
                                mediaSourceMessageId = draft.sourceMessageId
                                mediaInitialKind = draft.kind.name
                                showMediaStudio = true
                            },
                            onInsertText = { text ->
                                val previous = drafts[state.sessionId].orEmpty()
                                drafts.putBoundedLocalDraft(
                                    state.sessionId,
                                    listOf(previous, text).filter(String::isNotBlank).joinToString("\n\n"),
                                )
                            },
                        )
                        is LocalTranscriptItem.CreationJob -> LocalCreationJobMessageRow(
                            job = transcriptItem.job,
                            inline = transcriptItem.attachedTo != null,
                            viewModel = mediaStudioViewModel,
                        )
                        is LocalTranscriptItem.VideoTask -> LocalGeneratedVideoTaskRow(
                            task = transcriptItem.task,
                            inline = transcriptItem.attachedTo != null,
                            offers = com.labteto.dshmobile.local.model.LocalMediaCatalog.offers(state.modelProfiles),
                            viewModel = mediaStudioViewModel,
                            onRevise = { draft ->
                                mediaRevision = draft
                                mediaInitialPrompt = draft.prompt
                                mediaSourceMessageId = draft.sourceMessageId
                                mediaInitialKind = draft.kind.name
                                showMediaStudio = true
                            },
                        )
                        is LocalTranscriptItem.WorkArtifact -> LocalWorkArtifactMessageRow(
                            artifact = transcriptItem.artifact,
                            inline = transcriptItem.attachedTo != null,
                            workspacePath = state.workspacePath,
                            actions = workActions,
                            onOpen = { onOpenWorkArtifact(transcriptItem.artifact.reference) },
                            sessionId = state.sessionId,
                        )
                        is LocalTranscriptItem.Thinking -> ChatThinkingRow(transcriptItem.messages)
                        is LocalTranscriptItem.WorkPendingTool -> Box(
                            modifier = Modifier.fillMaxWidth().animateItem(),
                        ) {
                            WorkPendingToolRow(transcriptItem.activity)
                        }
                        is LocalTranscriptItem.WorkStep -> Box(
                            modifier = Modifier.fillMaxWidth().animateItem(
                                fadeInSpec = tween(durationMillis = com.shenyan.designsystem.foundation.SyMotion.duration(
                                    com.shenyan.designsystem.foundation.SyMotion.pageEnterFadeMs,
                                    com.shenyan.designsystem.foundation.LocalSyReduceMotion.current,
                                    com.shenyan.designsystem.foundation.LocalSyMotionTempo.current,
                                )),
                                placementSpec = tween(durationMillis = com.shenyan.designsystem.foundation.SyMotion.duration(
                                    com.shenyan.designsystem.foundation.SyMotion.toolExpandMs,
                                    com.shenyan.designsystem.foundation.LocalSyReduceMotion.current,
                                    com.shenyan.designsystem.foundation.LocalSyMotionTempo.current,
                                )),
                                fadeOutSpec = null,
                            ),
                        ) {
                            WorkTimelineStepRow(
                                message = transcriptItem.message,
                                workspacePath = state.workspacePath,
                                repeatCount = transcriptItem.repeatCount,
                                activity = transcriptItem.activity,
                                groupedMessages = transcriptItem.groupedMessages,
                                active = state.running && transcriptItem.message.role != "tool" &&
                                    transcriptItem.key == transcriptItems.lastOrNull()?.key,
                            )
                        }
                    }
                }
                if (workToolReadFailed) {
                    item(key = "work-tool-read-error:${state.sessionId}") {
                        WorkToolStatusNotice(onRetry = { workToolRetry += 1 })
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
                                waitingForUser = state.pendingApproval != null || state.pendingQuestion != null,
                                lastDurableNarrative = latestDurableWorkNarrativeInCurrentTurn(transcriptMessages),
                            )
                        }
                    }
                }
            }
            }
            // Work and Chat share the normal transcript; no separate five-tab dashboard.
            transcriptBody()

            ConversationScrollShortcut(
                target = scrollHint.target ?: ConversationScrollTarget.LATEST.takeIf {
                    state.usageMode == LocalUsageMode.WORK && workReaderPaused.value && listState.canScrollForward
                },
                modifier = Modifier.align(Alignment.BottomCenter)
                    .padding(bottom = SyMetric.gapMd),
                onClick = { target ->
                    scrollHint.hide()
                    if (state.usageMode == LocalUsageMode.WORK) workReaderPaused.value = true
                    transcriptInitialPositionReady = true
                    scope.launch {
                        val targetIndex = if (target == ConversationScrollTarget.START) 0
                            else listState.layoutInfo.totalItemsCount.dec().coerceAtLeast(0)
                        listState.animateScrollToItem(targetIndex)
                        // A long final reply can fill more than one viewport: the
                        // "latest" shortcut must reach its bottom, not its first line.
                        if (target == ConversationScrollTarget.LATEST &&
                            state.usageMode == LocalUsageMode.WORK
                        ) {
                            listState.alignWorkTimelineTailAfterScroll(targetIndex)
                            workReaderPaused.value = false
                        }
                    }
                },
            )
        }

        if (!state.configured && !state.loading && transcriptItems.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(
                    horizontal = SyMetric.gapLg,
                    vertical = SyMetric.gapMd,
                ),
            ) {
                Row(
                    modifier = Modifier.padding(SyMetric.gapLg),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(SyMetric.gapMd),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.local_welcome_connect_title),
                            style = DsType.std14Strong.withReadingWeight(),
                            color = colors.text,
                        )
                        Text(
                            stringResource(R.string.local_welcome_connect_body),
                            style = DsType.small13.withReadingWeight(),
                            color = colors.textSecondary,
                        )
                    }
                    SyButton(
                        text = stringResource(R.string.local_welcome_connect_action),
                        onClick = onConfigure,
                        size = SyButtonSize.Small,
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
                color = colors.dangerInk,
                modifier = Modifier.fillMaxWidth().padding(horizontal = SyMetric.gapLg),
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
                contentPadding = PaddingValues(horizontal = SyMetric.gapLg),
                horizontalArrangement = Arrangement.spacedBy(SyMetric.gapSm),
            ) {
                items(
                    items = state.replySuggestions.filter { it.text.isNotBlank() }.take(3),
                    key = { suggestion -> suggestion.label + "|" + suggestion.text },
                ) { suggestion ->
                    SyMaterialSurface(
                        onClick = { drafts.putBoundedLocalDraft(state.sessionId, suggestion.text) },
                        shape = DsShapes.pillFull,
                        tier = SyMaterialTier.L4_OVERLAY,
                        border = BorderStroke(1.dp, colors.border),
                    ) {
                        Text(
                            suggestion.label.ifBlank {
                                suggestion.text.take(18)
                            },
                            style = DsType.small13.withReadingWeight(),
                            color = colors.textSecondary,
                            maxLines = 1,
                            modifier = Modifier.padding(
                                horizontal = SyMetric.gapLg,
                                vertical = SyMetric.gapSm,
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
                horizontal = SyMetric.gapLg,
                vertical = SyMetric.gapSm,
            ),
        )

        LocalConversationComposer(
            state = state,
            skillDisplayNames = skillDisplayNames,
            activeModelProfile = activeModelProfile,
            input = input,
            attachments = attachments,
            attachmentImportProgress = attachmentImportProgress,
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
                    temperatureUi.range,
                )
            },
            temperaturePosition = if (state.usageMode == LocalUsageMode.CHAT) {
                temperatureUi.range?.let { state.chatState.behaviorTuning.temperaturePosition(it) }
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

    if (state.usageMode == LocalUsageMode.WORK) {
        documentGenerationMessageId?.let { sourceId ->
            nativeDocuments?.let { documents ->
                NativeV6DocumentGenerationDialog(
                    messageId = sourceId,
                    onDismiss = { documentGenerationMessageId = null },
                    onGenerate = documents::generate,
                )
            }
        }
    }
    V6LoadedConversationSearchHost(
        controller = loadedSearchState,
        transcript = transcriptItems,
        listState = listState,
        leadingRows = listOf(
            mediaPage.unavailable, mediaPage.hasMore, showTranscriptPagingRow,
            showEmptyGuide, showEmptyGroupSetup, planReview != null,
        ).count { it },
        visible = state.usageMode == LocalUsageMode.CHAT,
        onSearchAll = onOpenGlobalSearch,
    )
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
            requireConfirmation = workActions != null,
            questionId = question.callId,
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
        temperatureTuningAvailable = temperatureUi.tuningAvailable,
        temperatureRange = temperatureUi.range,
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
        SyResponsiveSheet(
            title = stringResource(R.string.local_reply_suggestions_title),
            onDismiss = { showReplySuggestions = false },
            seamlessBackground = true,
        ) {
            Text(
                stringResource(R.string.local_reply_suggestions_hint),
                style = DsType.small13.withReadingWeight(),
                color = colors.textSecondary,
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
                    color = Color.Transparent,
                ) {
                    Column(
                        Modifier.heightIn(min = SyMetric.touch)
                            .padding(horizontal = SyMetric.gapLg, vertical = SyMetric.gapMd),
                        verticalArrangement = Arrangement.spacedBy(SyMetric.gapXs),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(SyMetric.gapMd),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                suggestion.label,
                                style = DsType.std14Strong.withReadingWeight(),
                                color = colors.text,
                                modifier = Modifier.weight(1f),
                            )
                            if (suggestion.style.isNotBlank() || suggestion.bold) {
                                Text(
                                    if (suggestion.bold) stringResource(R.string.local_reply_suggestions_bold)
                                    else suggestion.style,
                                    style = DsType.caption11.withReadingWeight(),
                                    color = if (suggestion.bold) colors.warningInk else colors.textTertiary,
                                )
                            }
                        }
                        Text(
                            suggestion.text,
                            style = DsType.small13.withReadingWeight(),
                            color = colors.textSecondary,
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
        SyResponsiveSheet(
            title = null,
            onDismiss = { showAttachmentPicker = false },
            showDragHandle = false,
            floating = true,
            scrollable = true,
            seamlessBackground = true,
        ) {
            // Four compact icon-and-label actions in one horizontal row.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                SyQuickActionTile(
                    icon = FeatherIcons.Camera,
                    label = stringResource(R.string.composer_camera),
                    modifier = Modifier.weight(1f),
                    onClick = {
                        showAttachmentPicker = false
                        if (attachments.count { it.mediaType.startsWith("image/") } >= MAX_LOCAL_IMAGE_SELECTION) {
                            attachmentError = imageLimitMessage
                        } else takePhoto()
                    },
                )
                SyQuickActionTile(
                    icon = FeatherIcons.Image,
                    label = stringResource(R.string.local_attachment_image),
                    modifier = Modifier.weight(1f),
                    onClick = { showAttachmentPicker = false; imagePicker.launch(arrayOf("image/*")) },
                )
                SyQuickActionTile(
                    icon = FeatherIcons.Folder,
                    label = stringResource(R.string.local_attachment_file),
                    modifier = Modifier.weight(1f),
                    onClick = { showAttachmentPicker = false; filePicker.launch(arrayOf("*/*")) },
                )
                SyQuickActionTile(
                    icon = FeatherIcons.Sparkles,
                    label = stringResource(R.string.media_studio_entry),
                    modifier = Modifier.weight(1f),
                    onClick = {
                        showAttachmentPicker = false
                        mediaRevision = null
                        mediaInitialPrompt = ""
                        mediaSourceMessageId = null
                        mediaInitialKind = com.labteto.dshmobile.local.model.LocalMediaKind.IMAGE.name
                        showMediaStudio = true
                    },
                )
            }
            if (state.usageMode == LocalUsageMode.CHAT && !state.groupChat.enabled) {
                SySheetChoiceRow(
                    title = stringResource(R.string.local_persona_picker_title),
                    icon = com.labteto.dshmobile.ui.components.SyEntryIcons.Persona,
                    onClick = {
                        showAttachmentPicker = false
                        showPersonaPicker = true
                    },
                )
            }
            if (state.usageMode == LocalUsageMode.WORK && teamCardDismissed && state.team.visible) {
                SySheetChoiceRow(
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

    if (showMediaStudio) {
        SyResponsiveSheet(
            title = stringResource(R.string.media_studio_title),
            onDismiss = { showMediaStudio = false },
            scrollable = true,
            // More room for the editable prompt; other sheets keep their compact height.
            scrollBodyHeightFraction = .85f,
            seamlessBackground = true,
            dimScrimWhenSeamless = true,
        ) {
            LocalMediaStudioSheetContent(
                profiles = state.modelProfiles,
                preferredProfileId = activeModelProfile?.id,
                sessionId = state.sessionId,
                viewModel = mediaStudioViewModel,
                onDelivered = { showMediaStudio = false },
                initialPrompt = mediaInitialPrompt,
                sourceMessageId = mediaSourceMessageId,
                revision = mediaRevision,
                personaVoiceStyle = if (state.usageMode == LocalUsageMode.CHAT && !state.groupChat.enabled)
                    state.chatPersona.facts.filter { it.category == "voiceStyle" }.joinToString("；") { it.content }
                else "",
                initialKind = com.labteto.dshmobile.local.model.LocalMediaKind.valueOf(mediaInitialKind),
                onInsertTranscript = { transcription ->
                    val previous = drafts[state.sessionId].orEmpty()
                    drafts.putBoundedLocalDraft(
                        state.sessionId,
                        listOf(previous, transcription).filter(String::isNotBlank).joinToString("\n\n"),
                    )
                    showMediaStudio = false
                },
            )
        }
    }

    val teamPanelResources = androidx.compose.ui.platform.LocalResources.current
    if (showWorkEvidence && workActions != null && state.usageMode == LocalUsageMode.WORK) {
        LocalWorkEvidenceSheet(state.sessionId, state.workspacePath, workActions,
            onOpenFile = { path -> showWorkEvidence = false; onOpenWorkArtifact(path) },
            onDismiss = { showWorkEvidence = false })
    }
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

/** The Work quick-start panel must never enter a Chat conversation, including unbound personas. */
internal fun shouldShowEmptyLocalHarness(
    usageMode: LocalUsageMode,
    transcriptEmpty: Boolean,
): Boolean = usageMode == LocalUsageMode.WORK && transcriptEmpty

/** Unbound one-to-one Chat gets only the persona invitation, never Work quick-start actions. */
internal fun shouldShowChatPersonaWelcome(
    usageMode: LocalUsageMode,
    transcriptEmpty: Boolean,
    groupChatEnabled: Boolean,
    personaUnbound: Boolean,
): Boolean = usageMode == LocalUsageMode.CHAT && transcriptEmpty &&
    !groupChatEnabled && personaUnbound

@Composable
private fun EmptyLocalHarness(
    onSuggestion: (String) -> Unit,
) {
    val colors = com.shenyan.designsystem.foundation.LocalSyColors.current
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
            color = colors.text,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.widthIn(max = 280.dp),
        )
        Box {
            SyButton(
                text = stringResource(R.string.local_suggestions_title),
                icon = FeatherIcons.Sparkles,
                variant = SyButtonVariant.Info,
                onClick = { suggestionsOpen = true },
            )
            SyPopupMenu(
                expanded = suggestionsOpen,
                onDismiss = { suggestionsOpen = false },
                seamlessBackground = true,
                items = listOf(
                    SyMenuItem(stringResource(R.string.local_suggestion_files), com.labteto.dshmobile.ui.components.SyEntryIcons.WorkspaceFiles, onClick = { onSuggestion(filesPrompt) }),
                    SyMenuItem(stringResource(R.string.local_suggestion_research), com.labteto.dshmobile.ui.components.SyEntryIcons.Search, onClick = { onSuggestion(researchPrompt) }),
                    SyMenuItem(stringResource(R.string.local_suggestion_tasks), com.labteto.dshmobile.ui.components.SyEntryIcons.Tasks, onClick = { onSuggestion(tasksPrompt) }),
                ),
            )
        }
    }
}

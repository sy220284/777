package com.labteto.dshmobile.ui.screens.local

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.res.stringResource
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.chat.PersonaGalleryEntry
import com.labteto.dshmobile.local.feature.LocalFeatureModuleId
import com.labteto.dshmobile.local.model.LocalModelProfile
import com.labteto.dshmobile.local.presentation.LocalConversationSurfaceState
import com.labteto.dshmobile.local.presentation.LocalHarnessShellState
import com.labteto.dshmobile.local.send.LocalSendFeedbackState
import com.labteto.dshmobile.ui.screens.settings.SettingsDestination
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

@Composable
internal fun localShellFeatureUiContribution(
    shell: LocalHarnessShellState,
    state: StateFlow<LocalConversationSurfaceState>,
    activeModelProfile: LocalModelProfile?,
    sendFeedback: LocalSendFeedbackState,
    gallery: List<PersonaGalleryEntry>,
    transcriptHistory: LocalTranscriptHistoryState,
    modeIntro: LocalModeIntro?,
    pinnedSessionIds: Set<String>,
    sessionTitleOverrides: Map<String, String>,
    actions: LocalShellFeatureUiActions,
    onSettingsDestinationChange: (SettingsDestination) -> Unit,
    onPushFeature: (LocalFeaturePage) -> Unit,
    onNewSession: () -> Unit,
    composerHandoff: List<String>,
    skillDisplayNames: Map<String, String>,
    onUseWorkCapability: (String) -> Unit,
    onLocateMemoryForMessage: (String) -> Unit,
    onConsumeComposerHandoff: () -> Unit,
    onOpenDrawer: () -> Unit,
): LocalFeatureUiContribution {
    val scope = rememberCoroutineScope()
    val surface by state.collectAsStateWithLifecycle()
    return LocalFeatureUiContribution(
        moduleId = LocalFeatureModuleId.SHELL,
        restorePage = ::localFeatureRestoreOwnedPage,
    ) { page ->
        check(page == LocalFeaturePage.HOME) { "Shell received non-HOME route: $page" }
        LocalConversationSurface(
            state = surface,
            usageRevision = actions.usageRevision,
            loadTurnSummaries = actions.turnUsageSummaries,
            loadTurnUsage = actions.turnUsage,
            loadUsageRequest = actions.requestUsage,
            composerHandoff = composerHandoff,
            skillDisplayNames = skillDisplayNames,
            onUseWorkCapability = onUseWorkCapability,
            onConsumeComposerHandoff = onConsumeComposerHandoff,
            onOpenDrawer = onOpenDrawer,
            activeModelProfile = activeModelProfile,
            sendFeedback = sendFeedback,
            streamingState = actions.streamingState,
            gallery = gallery,
            transcriptHistory = transcriptHistory,
            modeIntro = modeIntro,
            onConfigure = {
                onSettingsDestinationChange(SettingsDestination.ROOT)
                onPushFeature(LocalFeaturePage.SETTINGS)
            },
            onSelectModel = actions.selectModel,
            onSend = actions.send,
            onSendTeam = actions.sendWithTeam,
            onTeamMemberOutput = actions.teamMemberOutput,
            onSendTeamMemberMessage = actions.sendTeamMemberMessage,
            onStopTeamMember = actions.stopTeamMember,
            onStopTeam = actions.stopTeam,
            onEditAndResend = actions.editAndResend,
            onLocateMemoryForMessage = onLocateMemoryForMessage,
            onSelectMessageVariant = actions.selectMessageVariant,
            onRegenerate = actions.regenerate,
            onGenerateReplySuggestions = actions.generateReplySuggestions,
            onLoadOlderTranscript = actions.loadOlderTranscript,
            onImportAttachment = actions.importAttachment,
            onStop = actions.stop,
            onNewSession = onNewSession,
            onExitGroupChat = actions.exitGroupChat,
            onOpenRunCenter = { onPushFeature(LocalFeaturePage.RUN_CENTER) },
            sessionTitle = sessionTitleOverrides[surface.sessionId]
                ?: shell.sessions.firstOrNull { it.id == surface.sessionId }?.title
                ?: stringResource(R.string.chatlist_new_session),
            sessionPinned = surface.sessionId in pinnedSessionIds,
            onTogglePinSession = { actions.toggleSessionPinned(surface.sessionId) },
            onRenameSession = { title -> actions.renameSession(surface.sessionId, title) },
            onDeleteSession = { scope.launch { actions.deleteSessions(setOf(surface.sessionId)) } },
            onConfigureChatPersona = actions.configureChatPersona,
            onConfigureGroupMembers = actions.configureGroupMembers,
            onSelectGalleryPersona = actions.selectGalleryPersona,
            onAutoFillChatPersona = actions.autoFillChatPersona,
            onSaveGroupAnnouncement = actions.saveGroupAnnouncement,
            onGenerateGroupAnnouncement = actions.generateGroupAnnouncement,
            onUndoPersonaCorrection = actions.undoPersonaCorrection,
            onPlanModeChange = actions.setPlanMode,
            onApprove = actions.approve,
            onDeny = actions.deny,
            onAutoApprovePending = actions.enableAutoApprovalForPending,
            onApproveDeviceTurn = actions.enableDeviceApprovalLease,
            onDisableDeviceTurn = actions.disableDeviceApprovalLease,
            onAnswerQuestion = actions.answerQuestion,
            onCancelQuestion = actions.cancelQuestion,
        )
    }
}

package com.labteto.dshmobile.ui.screens.local

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlinx.coroutines.launch

@Composable
internal fun localShellFeatureUiContribution(
    shell: LocalHarnessShellState,
    state: LocalConversationSurfaceState,
    activeModelProfile: LocalModelProfile?,
    sendFeedback: LocalSendFeedbackState,
    gallery: List<PersonaGalleryEntry>,
    transcriptHistory: LocalTranscriptHistoryState,
    modeIntro: LocalUsageMode?,
    pinnedSessionIds: Set<String>,
    sessionTitleOverrides: Map<String, String>,
    viewModel: LocalHarnessViewModel,
    onSettingsDestinationChange: (SettingsDestination) -> Unit,
    onPushFeature: (LocalFeaturePage) -> Unit,
    onNewSession: () -> Unit,
): LocalFeatureUiContribution {
    val scope = rememberCoroutineScope()
    return LocalFeatureUiContribution(LocalFeatureModuleId.SHELL) { page ->
        check(page == LocalFeaturePage.HOME) { "Shell received non-HOME route: $page" }
        LocalConversationSurface(
            state = state,
            activeModelProfile = activeModelProfile,
            sendFeedback = sendFeedback,
            streamingState = viewModel.streamingState,
            gallery = gallery,
            transcriptHistory = transcriptHistory,
            modeIntro = modeIntro,
            onConfigure = {
                onSettingsDestinationChange(SettingsDestination.ROOT)
                onPushFeature(LocalFeaturePage.SETTINGS)
            },
            onSelectModel = viewModel::selectModel,
            onSend = viewModel::send,
            onEditAndResend = viewModel::editAndResendUserMessage,
            onSelectMessageVariant = viewModel::selectChatMessageVariant,
            onRegenerate = viewModel::regenerateReply,
            onGenerateReplySuggestions = viewModel::generateReplySuggestions,
            onLoadOlderTranscript = viewModel::loadOlderTranscript,
            onImportAttachment = viewModel::importAttachment,
            onStop = viewModel::stop,
            onNewSession = onNewSession,
            onExitGroupChat = viewModel::leaveGroupChatMode,
            onOpenRunCenter = { onPushFeature(LocalFeaturePage.RUN_CENTER) },
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

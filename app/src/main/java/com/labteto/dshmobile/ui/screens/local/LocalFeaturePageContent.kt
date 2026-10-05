package com.labteto.dshmobile.ui.screens.local

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.labteto.dshmobile.R
import com.labteto.dshmobile.automation.AutomationMode
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.chat.PersonaGalleryEntry
import com.labteto.dshmobile.local.model.LocalModelProfile
import com.labteto.dshmobile.local.presentation.LocalConversationSurfaceState
import com.labteto.dshmobile.local.presentation.LocalHarnessShellState
import com.labteto.dshmobile.local.send.LocalSendFeedbackState
import com.labteto.dshmobile.ui.screens.settings.SettingsDestination
import com.labteto.dshmobile.ui.screens.settings.SettingsScreen
import com.labteto.dshmobile.ui.screens.tasks.TasksScreen
import com.labteto.dshmobile.ui.screens.tools.ToolsScreen
import kotlinx.coroutines.launch

@Composable
internal fun LocalFeaturePageContent(
    page: LocalFeaturePage,
    filesMode: LocalFilesMode,
    settingsDestination: SettingsDestination,
    taskMode: AutomationMode?,
    shell: LocalHarnessShellState,
    state: LocalConversationSurfaceState,
    activeModelProfile: LocalModelProfile?,
    sendFeedback: LocalSendFeedbackState,
    gallery: List<PersonaGalleryEntry>,
    transcriptHistory: LocalTranscriptHistoryState,
    modeIntro: LocalUsageMode?,
    pinnedSessionIds: Set<String>,
    sessionTitleOverrides: Map<String, String>,
    updateStatus: String?,
    onCheckUpdate: () -> Unit,
    viewModel: LocalHarnessViewModel,
    onFilesModeChange: (LocalFilesMode) -> Unit,
    onSettingsDestinationChange: (SettingsDestination) -> Unit,
    onTaskModeChange: (AutomationMode?) -> Unit,
    onPushFeature: (LocalFeaturePage) -> Unit,
    onPopFeature: () -> Unit,
    onResetNavigation: () -> Unit,
    onNewPersona: () -> Unit,
    onNewSession: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    Box(Modifier.fillMaxSize()) {
        when (page) {
            LocalFeaturePage.DIARY -> CharacterDiaryScreen(
                gallery = gallery,
                currentPersona = state.chatPersona,
                currentGalleryId = state.galleryId,
                loadEntries = viewModel::diaryEntries,
                onDismiss = onPopFeature,
            )
            LocalFeaturePage.PERSONA_GALLERY -> PersonaGalleryScreen(
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
                    if (viewModel.startFromGallery(id, storyId, freshStory)) onResetNavigation()
                },
                onCreate = onNewPersona,
                onDismiss = onPopFeature,
            )
            LocalFeaturePage.WORKSPACE -> LocalWorkspaceFilesDialog(
                mode = filesMode,
                sessionId = shell.sessionId,
                workspacePath = shell.workspacePath,
                loadWorkspace = viewModel::workspaceFiles,
                loadConversation = viewModel::conversationFiles,
                loadPreview = viewModel::previewWorkspaceFile,
                onDismiss = onPopFeature,
            )
            LocalFeaturePage.RUN_CENTER -> LocalWorkStateContent(viewModel) { workState ->
                LocalRunCenterScreen(
                    state = workState,
                    onJobOutput = viewModel::backgroundJobOutput,
                    onStopJob = viewModel::stopBackgroundJob,
                    onOpenResults = {
                        onFilesModeChange(LocalFilesMode.CONVERSATION)
                        onPushFeature(LocalFeaturePage.WORKSPACE)
                    },
                    onDismiss = onPopFeature,
                )
            }
            LocalFeaturePage.TASKS -> TasksScreen(
                onClose = {
                    onTaskModeChange(null)
                    onPopFeature()
                },
                onOpenSession = { sessionId ->
                    if (viewModel.switchSession(sessionId)) {
                        onTaskModeChange(null)
                        onResetNavigation()
                    }
                },
                initialMode = taskMode,
                handleRootSystemBack = false,
            )
            LocalFeaturePage.TOOLS -> ToolsScreen(
                onClose = onPopFeature,
                handleRootSystemBack = false,
                onOpenTasks = {
                    onTaskModeChange(AutomationMode.WORK)
                    onPushFeature(LocalFeaturePage.TASKS)
                },
                onOpenSettings = { destination ->
                    onSettingsDestinationChange(destination)
                    onPushFeature(LocalFeaturePage.SETTINGS)
                },
            )
            LocalFeaturePage.SETTINGS -> SettingsScreen(
                onClose = {
                    onSettingsDestinationChange(SettingsDestination.ROOT)
                    onPopFeature()
                },
                initialDestination = settingsDestination,
                onCheckUpdate = onCheckUpdate,
                updateStatus = updateStatus,
                handleRootSystemBack = false,
            )
            LocalFeaturePage.HOME -> LocalConversationSurface(
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
}

package com.labteto.dshmobile.ui.screens.local

import com.labteto.dshmobile.local.feature.LocalFeatureModuleId
import com.labteto.dshmobile.local.presentation.LocalHarnessShellState

internal fun localWorkFeatureUiContribution(
    filesMode: LocalFilesMode,
    shell: LocalHarnessShellState,
    actions: LocalWorkFeatureUiActions,
    onFilesModeChange: (LocalFilesMode) -> Unit,
    onPushFeature: (LocalFeaturePage) -> Unit,
    onPopFeature: () -> Unit,
    onOpenFromDrawer: (LocalFeaturePage) -> Unit,
    onCloseDrawer: () -> Unit,
    onContinueArtifact: (String) -> Unit = {},
    requestedFilePath: String? = null,
    onRequestedFilePathChange: (String?) -> Unit = {},
): LocalFeatureUiContribution = LocalFeatureUiContribution(
    moduleId = LocalFeatureModuleId.WORK,
    drawerActions = mapOf(
        LocalFeatureDrawerEntry.WORKSPACE to {
            onRequestedFilePathChange(null)
            onFilesModeChange(LocalFilesMode.WORKSPACE)
            onOpenFromDrawer(LocalFeaturePage.WORKSPACE)
            onCloseDrawer()
        },
        LocalFeatureDrawerEntry.RUN_CENTER to {
            onOpenFromDrawer(LocalFeaturePage.RUN_CENTER)
            onCloseDrawer()
        },
    ),
    backAction = { _, edge -> localFeatureProductBackAction(edge) },
    restorePage = ::localFeatureRestoreOwnedPage,
) { page ->
    when (page) {
        LocalFeaturePage.WORKSPACE -> LocalWorkspaceFilesDialog(
            mode = filesMode,
            sessionId = shell.sessionId,
            workspacePath = shell.workspacePath,
            loadWorkspace = actions.workspaceFiles,
            loadConversation = actions.conversationFiles,
            loadPreview = actions.previewWorkspaceFile,
            onDismiss = onPopFeature,
            initialFilePath = requestedFilePath,
            onInitialFilePathConsumed = { onRequestedFilePathChange(null) },
        )
        LocalFeaturePage.RUN_CENTER -> LocalWorkStateContent(actions.workState) { workState ->
            LocalRunCenterScreen(
                state = workState,
                onJobOutput = actions.backgroundJobOutput,
                onArtifacts = actions.artifactsForUi,
                onHistoryPage = actions.historyPageForUi,
                onArtifactHistory = actions.artifactHistoryForUi,
                onToolActivities = actions.toolActivitiesForUi,
                onEventSequence = actions.eventSequenceForUi,
                onToolEvidence = actions.toolEvidenceForUi,
                onStopJob = actions.stopBackgroundJob,
                onStartBackgroundAgent = actions.startBackgroundAgent,
                onStartResearchAgent = actions.startResearchAgent,
                onSendAgentMessage = actions.sendBackgroundAgentMessage,
                onContinueArtifact = onContinueArtifact,
                onOpenArtifact = { path ->
                    onRequestedFilePathChange(path)
                    onFilesModeChange(LocalFilesMode.CONVERSATION)
                    onPushFeature(LocalFeaturePage.WORKSPACE)
                },
                onOpenResults = {
                    onRequestedFilePathChange(null)
                    onFilesModeChange(LocalFilesMode.CONVERSATION)
                    onPushFeature(LocalFeaturePage.WORKSPACE)
                },
                onDismiss = onPopFeature,
            )
        }
        else -> error("Work received route owned by another feature: $page")
    }
}

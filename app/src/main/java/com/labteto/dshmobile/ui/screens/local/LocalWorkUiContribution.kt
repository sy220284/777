package com.labteto.dshmobile.ui.screens.local

import com.labteto.dshmobile.local.feature.LocalFeatureModuleId
import com.labteto.dshmobile.local.presentation.LocalHarnessShellState

internal fun localWorkFeatureUiContribution(
    filesMode: LocalFilesMode,
    shell: LocalHarnessShellState,
    viewModel: LocalHarnessViewModel,
    onFilesModeChange: (LocalFilesMode) -> Unit,
    onPushFeature: (LocalFeaturePage) -> Unit,
    onPopFeature: () -> Unit,
): LocalFeatureUiContribution = LocalFeatureUiContribution(LocalFeatureModuleId.WORK) { page ->
    when (page) {
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
        else -> error("Work received route owned by another feature: $page")
    }
}

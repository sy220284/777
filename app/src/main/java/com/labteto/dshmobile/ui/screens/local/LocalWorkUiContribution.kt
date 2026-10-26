package com.labteto.dshmobile.ui.screens.local

import com.labteto.dshmobile.local.feature.LocalFeatureModuleId
import com.labteto.dshmobile.local.presentation.LocalHarnessShellState

/** Work execution is shown in the conversation; Workspace is its only standalone page. */
internal fun localWorkFeatureUiContribution(
    filesMode: LocalFilesMode,
    shell: LocalHarnessShellState,
    actions: LocalWorkFeatureUiActions,
    onFilesModeChange: (LocalFilesMode) -> Unit,
    onPopFeature: () -> Unit,
    onOpenFromDrawer: (LocalFeaturePage) -> Unit,
    onCloseDrawer: () -> Unit,
    requestedFilePath: String? = null,
    entryRequest: Int = 0,
    onRequestedFilePathChange: (String?) -> Unit = {},
    onRequestedFilePathConsumed: (String) -> Unit = { onRequestedFilePathChange(null) },
): LocalFeatureUiContribution = LocalFeatureUiContribution(
    moduleId = LocalFeatureModuleId.WORK,
    drawerActions = mapOf(
        LocalFeatureDrawerEntry.WORKSPACE to {
            onRequestedFilePathChange(null)
            onFilesModeChange(LocalFilesMode.WORKSPACE)
            onOpenFromDrawer(LocalFeaturePage.WORKSPACE)
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
            loadDirectory = actions.workspaceDirectory,
            loadDirectoryPage = actions.workspaceDirectoryPage,
            loadSearch = actions.searchWorkspaceFiles,
            loadConversation = actions.conversationFiles,
            loadPreview = actions.previewWorkspaceFile,
            onDismiss = onPopFeature,
            initialFilePath = requestedFilePath,
            entryRequest = entryRequest,
            onInitialFilePathConsumed = onRequestedFilePathConsumed,
        )
        else -> error("Work received route owned by another feature: $page")
    }
}

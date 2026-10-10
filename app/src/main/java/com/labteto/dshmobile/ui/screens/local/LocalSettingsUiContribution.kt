package com.labteto.dshmobile.ui.screens.local

import com.labteto.dshmobile.local.feature.LocalFeatureModuleId
import com.labteto.dshmobile.ui.screens.settings.SettingsDestination
import com.labteto.dshmobile.ui.screens.settings.SettingsScreen

internal fun localSettingsFeatureUiContribution(
    settingsDestination: SettingsDestination,
    memorySourceSessionId: String? = null,
    memorySourceMessageId: String? = null,
    updateStatus: String?,
    onCheckUpdate: () -> Unit,
    onSettingsDestinationChange: (SettingsDestination) -> Unit,
    onPopFeature: () -> Unit,
    onOpenFromDrawer: (LocalFeaturePage) -> Unit,
    onCloseDrawer: () -> Unit,
): LocalFeatureUiContribution = LocalFeatureUiContribution(
    moduleId = LocalFeatureModuleId.SETTINGS,
    drawerActions = mapOf(
        LocalFeatureDrawerEntry.SETTINGS to {
            onSettingsDestinationChange(SettingsDestination.ROOT)
            onOpenFromDrawer(LocalFeaturePage.SETTINGS)
            onCloseDrawer()
        },
    ),
    backAction = { _, edge -> localFeatureProductBackAction(edge) },
    restorePage = ::localFeatureRestoreOwnedPage,
) { page ->
    check(page == LocalFeaturePage.SETTINGS) { "Settings received non-SETTINGS route: $page" }
    SettingsScreen(
        onClose = {
            onSettingsDestinationChange(SettingsDestination.ROOT)
            onPopFeature()
        },
        initialDestination = settingsDestination,
        memorySourceSessionId = memorySourceSessionId,
        memorySourceMessageId = memorySourceMessageId,
        onCheckUpdate = onCheckUpdate,
        updateStatus = updateStatus,
        handleRootSystemBack = false,
    )
}

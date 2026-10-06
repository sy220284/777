package com.labteto.dshmobile.ui.screens.local

import com.labteto.dshmobile.local.feature.LocalFeatureModuleId
import com.labteto.dshmobile.ui.screens.settings.SettingsDestination
import com.labteto.dshmobile.ui.screens.settings.SettingsScreen

internal fun localSettingsFeatureUiContribution(
    settingsDestination: SettingsDestination,
    updateStatus: String?,
    onCheckUpdate: () -> Unit,
    onSettingsDestinationChange: (SettingsDestination) -> Unit,
    onPopFeature: () -> Unit,
): LocalFeatureUiContribution = LocalFeatureUiContribution(LocalFeatureModuleId.SETTINGS) { page ->
    check(page == LocalFeaturePage.SETTINGS) { "Settings received non-SETTINGS route: $page" }
    SettingsScreen(
        onClose = {
            onSettingsDestinationChange(SettingsDestination.ROOT)
            onPopFeature()
        },
        initialDestination = settingsDestination,
        onCheckUpdate = onCheckUpdate,
        updateStatus = updateStatus,
        handleRootSystemBack = false,
    )
}

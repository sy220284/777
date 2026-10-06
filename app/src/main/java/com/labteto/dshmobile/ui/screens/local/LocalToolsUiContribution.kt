package com.labteto.dshmobile.ui.screens.local

import com.labteto.dshmobile.automation.AutomationMode
import com.labteto.dshmobile.local.feature.LocalFeatureModuleId
import com.labteto.dshmobile.ui.screens.settings.SettingsDestination
import com.labteto.dshmobile.ui.screens.tools.ToolsScreen

internal fun localToolsFeatureUiContribution(
    onTaskModeChange: (AutomationMode?) -> Unit,
    onSettingsDestinationChange: (SettingsDestination) -> Unit,
    onPushFeature: (LocalFeaturePage) -> Unit,
    onPopFeature: () -> Unit,
): LocalFeatureUiContribution = LocalFeatureUiContribution(LocalFeatureModuleId.TOOLS) { page ->
    check(page == LocalFeaturePage.TOOLS) { "Tools received non-TOOLS route: $page" }
    ToolsScreen(
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
}

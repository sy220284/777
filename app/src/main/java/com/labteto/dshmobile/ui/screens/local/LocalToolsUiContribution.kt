package com.labteto.dshmobile.ui.screens.local

import com.labteto.dshmobile.automation.AutomationMode
import com.labteto.dshmobile.local.feature.LocalFeatureModuleId
import com.labteto.dshmobile.ui.screens.settings.SettingsDestination
import com.labteto.dshmobile.ui.screens.tools.ToolsScreen

internal fun localToolsFeatureUiContribution(
    startAtPlugins: Boolean,
    startAtSkills: Boolean,
    onUseCapability: (String) -> Unit,
    onTaskModeChange: (AutomationMode?) -> Unit,
    onSettingsDestinationChange: (SettingsDestination) -> Unit,
    onPushFeature: (LocalFeaturePage) -> Unit,
    onPopFeature: () -> Unit,
    onOpenFromDrawer: (LocalFeaturePage) -> Unit,
    onCloseDrawer: () -> Unit,
): LocalFeatureUiContribution = LocalFeatureUiContribution(
    moduleId = LocalFeatureModuleId.TOOLS,
    drawerActions = mapOf(
        LocalFeatureDrawerEntry.TOOLS to {
            onOpenFromDrawer(LocalFeaturePage.TOOLS)
            onCloseDrawer()
        },
    ),
    backAction = { _, edge -> localFeatureProductBackAction(edge) },
    restorePage = ::localFeatureRestoreOwnedPage,
) { page ->
    check(page == LocalFeaturePage.TOOLS) { "Tools received non-TOOLS route: $page" }
    ToolsScreen(
        onClose = onPopFeature,
        startAtPlugins = startAtPlugins,
        startAtSkills = startAtSkills,
        onUseCapability = onUseCapability,
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

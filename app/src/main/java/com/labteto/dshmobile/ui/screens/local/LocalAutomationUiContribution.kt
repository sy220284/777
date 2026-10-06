package com.labteto.dshmobile.ui.screens.local

import com.labteto.dshmobile.automation.AutomationMode
import com.labteto.dshmobile.local.feature.LocalFeatureModuleId
import com.labteto.dshmobile.ui.screens.tasks.TasksScreen

internal fun localAutomationFeatureUiContribution(
    taskMode: AutomationMode?,
    viewModel: LocalHarnessViewModel,
    onTaskModeChange: (AutomationMode?) -> Unit,
    onResetNavigation: () -> Unit,
    onPopFeature: () -> Unit,
): LocalFeatureUiContribution = LocalFeatureUiContribution(LocalFeatureModuleId.AUTOMATION) { page ->
    check(page == LocalFeaturePage.TASKS) { "Automation 收到非 TASKS 路由：$page" }
    TasksScreen(
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
}

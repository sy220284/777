package com.labteto.dshmobile.ui.screens.local

import com.labteto.dshmobile.automation.AutomationMode
import com.labteto.dshmobile.local.feature.LocalFeatureModuleId
import com.labteto.dshmobile.ui.screens.tasks.TasksScreen

internal fun localAutomationFeatureUiContribution(
    taskMode: AutomationMode?,
    onCreateViaChat: (String) -> Unit,
    actions: LocalAutomationFeatureUiActions,
    onTaskModeChange: (AutomationMode?) -> Unit,
    onResetNavigation: () -> Unit,
    onPopFeature: () -> Unit,
    onOpenFromDrawer: (LocalFeaturePage) -> Unit,
    onCloseDrawer: () -> Unit,
): LocalFeatureUiContribution = LocalFeatureUiContribution(
    moduleId = LocalFeatureModuleId.AUTOMATION,
    drawerActions = mapOf(
        LocalFeatureDrawerEntry.TASKS to {
            onTaskModeChange(null)
            onOpenFromDrawer(LocalFeaturePage.TASKS)
            onCloseDrawer()
        },
    ),
    backAction = { _, edge -> localFeatureProductBackAction(edge) },
    restorePage = ::localFeatureRestoreOwnedPage,
) { page ->
    check(page == LocalFeaturePage.TASKS) { "Automation received non-TASKS route: $page" }
    TasksScreen(
        onClose = {
            onTaskModeChange(null)
            onPopFeature()
        },
        onOpenSession = { sessionId ->
            if (actions.switchSession(sessionId)) {
                onTaskModeChange(null)
                onResetNavigation()
            }
        },
        initialMode = taskMode,
        onCreateViaChat = onCreateViaChat,
        handleRootSystemBack = false,
    )
}

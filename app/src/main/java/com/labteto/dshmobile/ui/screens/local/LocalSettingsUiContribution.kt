package com.labteto.dshmobile.ui.screens.local

import com.labteto.dshmobile.local.feature.LocalFeatureModuleId
import com.labteto.dshmobile.ui.screens.settings.SettingsDestination
import com.labteto.dshmobile.ui.screens.settings.SettingsScreen

// FEATURE_TREE: {"id":"settings.minimax.quota","path":"第一层｜产品功能与界面 / 设置与个人配置 / 用量与成本 / MiniMax 侧边栏套餐额度","entry":"localSettingsFeatureUiContribution","owner":"SETTINGS","test":"app/src/test/java/com/labteto/dshmobile/ui/screens/local/MiniMaxQuotaNavigationTest.kt#quotaDrawerOpensQuotaDirectlyAndClosesDrawer"}
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
        LocalFeatureDrawerEntry.USAGE to {
            onSettingsDestinationChange(SettingsDestination.USAGE)
            onOpenFromDrawer(LocalFeaturePage.SETTINGS)
            onCloseDrawer()
        },
        LocalFeatureDrawerEntry.MINIMAX_QUOTA to {
            onSettingsDestinationChange(SettingsDestination.MINIMAX_QUOTA)
            onOpenFromDrawer(LocalFeaturePage.SETTINGS)
            onCloseDrawer()
        },
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

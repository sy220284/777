package com.labteto.dshmobile.ui.screens.local

import com.labteto.dshmobile.ui.screens.settings.SettingsDestination
import org.junit.Assert.assertEquals
import org.junit.Test

class MiniMaxQuotaNavigationTest {
    @Test fun quotaDrawerOpensQuotaDirectlyAndClosesDrawer() {
        val events = mutableListOf<String>()
        val contribution = localSettingsFeatureUiContribution(
            settingsDestination = SettingsDestination.ROOT,
            updateStatus = null,
            onCheckUpdate = {},
            onSettingsDestinationChange = { events += "destination:${it.name}" },
            onPopFeature = {},
            onOpenFromDrawer = { events += "page:${it.name}" },
            onCloseDrawer = { events += "closed" },
        )
        contribution.drawerActions.getValue(LocalFeatureDrawerEntry.MINIMAX_QUOTA).invoke()
        assertEquals(listOf("destination:MINIMAX_QUOTA", "page:SETTINGS", "closed"), events)
    }
}

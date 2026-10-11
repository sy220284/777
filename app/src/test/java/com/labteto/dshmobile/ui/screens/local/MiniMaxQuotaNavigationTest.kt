package com.labteto.dshmobile.ui.screens.local

import com.labteto.dshmobile.ui.screens.settings.SettingsDestination
import org.junit.Assert.assertEquals
import org.junit.Test

class MiniMaxQuotaNavigationTest {
    @Test
    fun quotaDrawerOpensQuotaDirectlyAndClosesDrawer() {
        var destination = SettingsDestination.ROOT
        var page: LocalFeaturePage? = null
        var closes = 0
        val contribution = localSettingsFeatureUiContribution(
            settingsDestination = destination, updateStatus = null,
            onCheckUpdate = {}, onSettingsDestinationChange = { destination = it },
            onPopFeature = {}, onOpenFromDrawer = { page = it },
            onCloseDrawer = { closes++ },
        )
        localFeatureDrawerAction(LocalFeatureDrawerEntry.MINIMAX_QUOTA, listOf(contribution))!!.invoke()
        assertEquals(SettingsDestination.MINIMAX_QUOTA, destination)
        assertEquals(LocalFeaturePage.SETTINGS, page)
        assertEquals(1, closes)
        localFeatureDrawerAction(LocalFeatureDrawerEntry.SETTINGS, listOf(contribution))!!.invoke()
        assertEquals(SettingsDestination.ROOT, destination)
    }
}

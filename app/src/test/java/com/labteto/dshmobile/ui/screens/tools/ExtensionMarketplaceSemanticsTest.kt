package com.labteto.dshmobile.ui.screens.tools

import com.labteto.dshmobile.uicontract.UiSkillRequirement
import com.labteto.dshmobile.uicontract.UiToolConnection
import com.labteto.dshmobile.uicontract.UiToolRegistration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtensionMarketplaceSemanticsTest {
    @Test fun installedDoesNotHideMissingMcpRequirement() {
        val state = installedSkillMarketState(true, null, listOf(UiSkillRequirement("mcp", "github", registered = false)))
        assertEquals(ExtensionMarketStatus.NEEDS_SETUP, state.status)
        assertTrue(state.advice.contains("github"))
    }

    @Test fun registeredDependencyStillRequiresRealInvocationGrant() {
        val state = installedSkillMarketState(true, null, listOf(UiSkillRequirement("mcp", "github", registered = true)))
        assertEquals(ExtensionMarketStatus.INSTALLED, state.status)
        assertTrue(state.advice.contains("授权"))
    }

    @Test fun userDisabledSkillIsManualOnly() {
        val state = installedSkillMarketState(false, null, emptyList())
        assertEquals(ExtensionMarketStatus.MANUAL_ONLY, state.status)
    }

    @Test fun invalidSourceCannotBeAdvertisedReady() {
        val state = installedSkillMarketState(true, "GitHub 来源损坏", emptyList())
        assertEquals(ExtensionMarketStatus.FAILED, state.status)
    }

    @Test fun registryPresenceIsNotHealthyRuntime() {
        val state = connectorMarketState(UiToolRegistration.REGISTERED, UiToolConnection.HEALTH_UNCHECKED)
        assertEquals(ExtensionMarketStatus.REGISTERED, state.status)
        assertTrue(state.label.contains("待验证"))
    }

    @Test fun failedConnectorShowsRepairState() {
        assertEquals(ExtensionMarketStatus.FAILED,
            connectorMarketState(UiToolRegistration.FAILED, UiToolConnection.FAILED).status)
    }

    @Test fun discoveryShowsBothInstalledAndInstallable() {
        assertTrue(marketplaceTabAllows(0, true, false, true, false, false, ExtensionGroup.OFFICE))
        assertTrue(marketplaceTabAllows(0, true, true, false, false, false, ExtensionGroup.OFFICE))
        assertTrue(marketplaceTabAllows(0, false, false, false, true, false, ExtensionGroup.GENERAL))
    }

    @Test fun installedViewOnlyShowsInstalledItems() {
        assertTrue(!marketplaceTabAllows(1, true, false, true, false, false, ExtensionGroup.OFFICE))
        assertTrue(marketplaceTabAllows(1, true, true, false, false, false, ExtensionGroup.OFFICE))
    }
}

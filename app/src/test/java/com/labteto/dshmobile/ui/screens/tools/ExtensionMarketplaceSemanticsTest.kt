package com.labteto.dshmobile.ui.screens.tools

import com.labteto.dshmobile.uicontract.UiSkillRequirement
import com.labteto.dshmobile.uicontract.UiToolConnection
import com.labteto.dshmobile.uicontract.UiToolRegistration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtensionMarketplaceSemanticsTest {
    @Test fun validHttpsMcpEndpointStillSupportsExplicitAuthorization() {
        assertEquals("https://api.githubcopilot.com/mcp/", skillMcpHttpsEndpoint(
            UiSkillRequirement("mcp", "github", transport = "http", url = "https://api.githubcopilot.com/mcp/")))
    }

    @Test fun insecureOrLocalEndpointsCannotBePrefilled() {
        assertEquals(null, skillMcpHttpsEndpoint(UiSkillRequirement("mcp", "unsafe",
            transport = "http", url = "http://localhost:8080/")))
        assertEquals(null, skillMcpHttpsEndpoint(UiSkillRequirement("mcp", "unsafe",
            transport = "stdio", url = "https://example.com/")))
    }

    @Test fun missingDependencyRemainsVisibleAfterInstallation() {
        assertEquals(ExtensionMarketStatus.NEEDS_SETUP, installedSkillMarketState(true, null,
            listOf(UiSkillRequirement("mcp", "github", registered = false))).status)
    }

    @Test fun installedAndConnectedAreDistinctFromRunAuthorization() {
        val state = installedSkillMarketState(true, null, emptyList())
        assertEquals(ExtensionMarketStatus.INSTALLED, state.status)
        assertTrue(state.advice.contains("授权"))
        assertEquals(ExtensionMarketStatus.REGISTERED,
            connectorMarketState(UiToolRegistration.REGISTERED, UiToolConnection.HEALTH_UNCHECKED).status)
    }

    @Test fun defaultAddedIncludesOwnedKindsAndOmitsUninstalledOptions() {
        assertTrue(marketplaceTabAllows(true, true, false, false, false))
        assertTrue(marketplaceTabAllows(true, false, true, false, false))
        assertTrue(marketplaceTabAllows(true, false, false, true, false))
        assertTrue(marketplaceTabAllows(true, false, false, false, true))
        assertTrue(!marketplaceTabAllows(true, false, false, false, false))
    }

    @Test fun discoverShowsOnlyUnownedEntriesByDefault() {
        assertTrue(marketplaceTabAllows(false, false, false, false, false))
        assertTrue(!marketplaceTabAllows(false, true, false, false, false))
        assertTrue(!marketplaceTabAllows(false, false, true, false, false))
        assertTrue(!marketplaceTabAllows(false, false, false, true, false))
        assertTrue(!marketplaceTabAllows(false, false, false, false, true))
    }
}

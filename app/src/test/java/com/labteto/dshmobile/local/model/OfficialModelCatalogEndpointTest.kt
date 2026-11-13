package com.labteto.dshmobile.local.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OfficialModelCatalogEndpointTest {
    @Test
    fun miniMaxDiscoveryUsesTheConfiguredOfficialHostNotAnotherRegion() {
        for (host in listOf("api.minimaxi.com", "api.minimax.io", "api.minimax.cn")) {
            assertEquals(
                "https://$host/v1/models",
                officialModelCatalogEndpoint(LocalModelProfile(
                    id = "route-$host",
                    model = "MiniMax-M3",
                    baseUrl = "https://$host/v1",
                    provider = "MiniMax",
                )),
            )
        }
    }

    @Test
    fun arbitraryHostsAndChatGptPlanAccountsCannotReceiveStoredApiKey() {
        assertNull(officialModelCatalogEndpoint(LocalModelProfile(
            id = "custom", model = "MiniMax-M3",
            baseUrl = "https://api.minimaxi.com.attacker.invalid/v1",
        )))
        assertNull(officialModelCatalogEndpoint(LocalModelProfile(
            id = "plan", model = "gpt-5.6", baseUrl = "https://api.openai.com/v1",
            authKind = LocalModelAuthKind.CHATGPT_PLAN,
        )))
    }

    @Test
    fun knownProvidersKeepTheirPinnedOfficialEndpoint() {
        assertEquals(
            "https://api.openai.com/v1/models",
            officialModelCatalogEndpoint(LocalModelProfile(
                id = "openai", model = "gpt-5.6",
                baseUrl = "https://api.openai.com/v1",
            )),
        )
    }
}

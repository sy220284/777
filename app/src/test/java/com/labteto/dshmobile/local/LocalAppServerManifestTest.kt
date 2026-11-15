package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.feature.LocalFeatureCatalog
import com.labteto.dshmobile.local.feature.LocalFeatureRoute
import com.labteto.dshmobile.uicontract.UI_CONTRACT_VERSION
import com.labteto.dshmobile.uicontract.UiAppServerFeature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.inject.Provider

class LocalAppServerManifestTest {
    @Test fun readingManifestDoesNotInitializeAnyFeatureOwner() {
        val server = LocalAppServer(
            conversationOwner = Provider { error("conversation unexpectedly initialized") },
            sessionOwner = Provider { error("session unexpectedly initialized") },
            agentOwner = Provider { error("agent unexpectedly initialized") },
            automationOwner = Provider { error("automation unexpectedly initialized") },
            toolsOwner = Provider { error("tools unexpectedly initialized") },
            settingsOwner = Provider { error("settings unexpectedly initialized") },
            workReadOwner = Provider { error("work read unexpectedly initialized") },
        )
        assertEquals(6, server.manifest().endpoints.size)
        // A request for one feature does not cause eager initialization of the other six.
    }

    @Test fun manifestHasExactlyOneOwnerForEveryProductRoute() {
        val manifest = localAppServerManifest()
        assertEquals(UI_CONTRACT_VERSION, manifest.version)
        assertEquals(UiAppServerFeature.entries.toSet(), manifest.endpoints.map { it.feature }.toSet())
        assertEquals(LocalFeatureCatalog.modules.size, manifest.endpoints.size)
        assertEquals(LocalFeatureRoute.entries.map { it.name }.toSet(),
            manifest.endpoints.flatMap { it.routes }.toSet())
        assertEquals(LocalFeatureRoute.entries.size,
            manifest.endpoints.flatMap { it.routes }.size)
        assertTrue(manifest.endpoints.all { it.routes.isNotEmpty() })
    }
}

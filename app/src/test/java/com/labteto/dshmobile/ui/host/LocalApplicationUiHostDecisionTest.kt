package com.labteto.dshmobile.ui.host

import com.labteto.dshmobile.uicontract.UI_CONTRACT_VERSION
import com.labteto.dshmobile.uicontract.UiAccessibility
import com.labteto.dshmobile.uicontract.UiPackManifest
import com.labteto.dshmobile.uicontract.UiSceneId
import com.labteto.dshmobile.uicontract.UiWindowClass
import com.labteto.dshmobile.uihost.UiApplicationPackAdmission
import com.labteto.dshmobile.uihost.UiApplicationScenePack
import com.labteto.dshmobile.uihost.UiPackAdmissionRequirements
import com.labteto.dshmobile.uihost.UiSceneRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercises the same catalog and gate AppRoot uses; the presence of a manifest is not enough. */
class LocalApplicationUiHostDecisionTest {
    private val required = LocalUiSceneCatalog.requirements
    private val requirements = UiPackAdmissionRequirements(
        contractVersion = UI_CONTRACT_VERSION,
        requiredScenes = required,
        availableCapabilities = emptySet(),
        windowClass = UiWindowClass.COMPACT,
        requiredAccessibility = UiAccessibility.entries.toSet(),
        dark = false,
    )
    private val renderableScenes = required.filterNot { it.hostProvided }.map { it.id }.toSet()

    private fun pack(id: String, claims: Set<UiSceneId>, rendered: Set<UiSceneId>): UiApplicationScenePack =
        object : UiApplicationScenePack {
            override val manifest = UiPackManifest(
                id = id, version = 1, minContractVersion = UI_CONTRACT_VERSION,
                maxContractVersion = UI_CONTRACT_VERSION,
                scenes = claims, requiredCapabilities = emptySet(),
                windowClasses = UiWindowClass.entries.toSet(),
                accessibility = UiAccessibility.entries.toSet(), supportsDark = true,
            )
            override val renderers = rendered.associateWith { UiSceneRenderer { _ -> } }
        }

    @Test fun retiredSelectorsUseCurrentAdmissionAndNeverInstantiateLegacy() {
        for (selector in listOf(null, "legacy-v6", "legacy-v6-shell")) {
            val missing = decideApplicationUi(selector, emptyList(), requirements)
            assertTrue(missing is LocalApplicationUiDecision.Unavailable)
            assertEquals(V6_APPLICATION_UI, (missing as LocalApplicationUiDecision.Unavailable).selectedId)
            val full = pack(V6_APPLICATION_UI, renderableScenes, renderableScenes)
            assertTrue(decideApplicationUi(selector, listOf(full), requirements) is LocalApplicationUiDecision.Hosted)
        }
    }

    @Test fun conversationSliceCannotEnterWholeApplicationHost() {
        val slice = pack("v6-migrating", setOf(UiSceneId("conversation.chat")), setOf(UiSceneId("conversation.chat")))
        val result = decideApplicationUi("v6-migrating", listOf(slice), requirements)
        assertTrue(result is LocalApplicationUiDecision.Unavailable)
        val rejection = (result as LocalApplicationUiDecision.Unavailable).rejection
        assertTrue(rejection is UiApplicationPackAdmission.Incompatible)
        assertTrue(UiSceneId("route.settings") in (rejection as UiApplicationPackAdmission.Incompatible)
            .compatibility.missingScenes)
    }

    @Test fun aManifestAdvertisingAllScenesButMissingOneRendererIsNotAdmitted() {
        val missing = renderableScenes - UiSceneId("route.settings")
        val result = decideApplicationUi("v6-migrating", listOf(pack("v6-migrating", renderableScenes, missing)), requirements)
        assertTrue(result is LocalApplicationUiDecision.Unavailable)
        val rejection = (result as LocalApplicationUiDecision.Unavailable).rejection
        assertTrue(rejection is UiApplicationPackAdmission.Incomplete)
        assertTrue(UiSceneId("route.settings") in (rejection as UiApplicationPackAdmission.Incomplete)
            .coverage.missingRenderers)
    }

    @Test fun fullCatalogWithIndependentRenderersMayEnterProductionHost() {
        val full = pack("v6-migrating", renderableScenes, renderableScenes)
        val result = decideApplicationUi("v6-migrating", listOf(full), requirements)
        assertTrue(result is LocalApplicationUiDecision.Hosted)
        assertEquals(full, (result as LocalApplicationUiDecision.Hosted).admission.pack)
        assertEquals(7, renderableScenes.count { it.value.startsWith("route.") })
    }

    @Test fun emptyPersistedPackNameCannotQuietlyRestoreLegacy() {
        val result = decideApplicationUi("", emptyList(), requirements)
        assertTrue(result is LocalApplicationUiDecision.Unavailable)
    }

    @Test fun twoSeparateFullCatalogPacksCannotReuseEachOthersNavigationState() {
        val v6 = pack("full-v6", renderableScenes, renderableScenes)
        val candidate = pack("future-candidate", renderableScenes, renderableScenes)
        val packs = listOf(v6, candidate)
        val first = decideApplicationUi("full-v6", packs, requirements)
        val second = decideApplicationUi("future-candidate", packs, requirements)
        assertTrue(first is LocalApplicationUiDecision.Hosted)
        assertTrue(second is LocalApplicationUiDecision.Hosted)
        val firstNav = com.labteto.dshmobile.uihost.UiHostSceneNavigation(
            required, v6.manifest, UiSceneId("route.home"))
        val secondNav = com.labteto.dshmobile.uihost.UiHostSceneNavigation(
            required, candidate.manifest, UiSceneId("route.home"))
        val settings = firstNav.navigate(firstNav.initial, UiSceneId("route.settings"))
        assertTrue(settings is com.labteto.dshmobile.uihost.UiSceneNavigationResult.Changed)
        val foreign = (settings as com.labteto.dshmobile.uihost.UiSceneNavigationResult.Changed).state
        assertEquals(
            com.labteto.dshmobile.uihost.UiSceneNavigationResult.Rejected(
                com.labteto.dshmobile.uihost.UiSceneNavigationRejection.WRONG_PACK),
            secondNav.restore(foreign),
        )
        val local = secondNav.navigate(secondNav.initial, UiSceneId("route.settings"))
        assertTrue(local is com.labteto.dshmobile.uihost.UiSceneNavigationResult.Changed)
    }

    @Test fun productionDeepLinksCoverSevenRoutesAndCurrentSettingsOnly() {
        val targets = LocalApplicationSceneDeepLinks.targets
        assertEquals(7, targets.keys.count { it.startsWith("route.") })
        assertEquals(com.labteto.dshmobile.ui.screens.settings.SettingsDestination.entries.size,
            targets.keys.count { it.startsWith("settings.") })
        assertEquals(targets.size, targets.values.toSet().size)
        assertTrue("system.permissions" !in targets)
        assertTrue("conversation.composer" !in targets)
        assertTrue("settings.__unregistered" !in targets)
    }

    @Test fun externalSceneUrisRejectUnknownOrAmbiguousRoutes() {
        assertEquals("route.tools",
            LocalApplicationSceneDeepLinks.fromExternalUri("dsh777://scene/route.tools"))
        assertEquals("settings.models",
            LocalApplicationSceneDeepLinks.fromExternalUri("dsh777://scene/settings.models"))
        listOf(
            null, "", "https://example.com", "dsh777://scene/system.permissions",
            "dsh777://scene/settings.__unregistered",
            "dsh777://scene/route.home/extra",
            "dsh777://scene/route.home?redirect=route.tools",
            "dsh777://scene/%72oute.home",
            "dsh777://scene/route.home#fragment",
            "DSH777://scene/route.home",
        ).forEach { candidate ->
            assertEquals("Unexpected accepted URI: $candidate", null,
                LocalApplicationSceneDeepLinks.fromExternalUri(candidate))
        }
    }

    @Test fun internalSceneRequestsUseHostWhitelistAndCanNavigateSettings() {
        val full = pack("v6-migrating", renderableScenes, renderableScenes)
        val navigation = com.labteto.dshmobile.uihost.UiHostSceneNavigation(
            required, full.manifest, UiSceneId("route.home"),
            deepLinks = LocalApplicationSceneDeepLinks.targets,
        )
        val result = navigation.openDeepLink(navigation.initial, "settings.models")
        assertTrue(result is com.labteto.dshmobile.uihost.UiSceneNavigationResult.Changed)
        val page = (result as com.labteto.dshmobile.uihost.UiSceneNavigationResult.Changed)
            .state.stack.last()
        assertEquals(UiSceneId("settings.models"), page)
        assertEquals(
            com.labteto.dshmobile.uihost.UiSceneNavigationResult.Rejected(
                com.labteto.dshmobile.uihost.UiSceneNavigationRejection.UNKNOWN_DEEP_LINK),
            navigation.openDeepLink(navigation.initial, "system.permissions"),
        )
        assertEquals(
            com.labteto.dshmobile.uihost.UiSceneNavigationResult.Rejected(
                com.labteto.dshmobile.uihost.UiSceneNavigationRejection.UNKNOWN_DEEP_LINK),
            navigation.openDeepLink(navigation.initial, "https://external.example"),
        )
    }

    @Test fun notificationNavigationRequiresOwnerToActivateExactTargetSession() {
        val old = com.labteto.dshmobile.uicontract.UiSessionsSnapshot(
            com.labteto.dshmobile.uicontract.UiSessionIdentity(
                "old-story", "activation-old", 1L, null, null),
            com.labteto.dshmobile.local.LocalUsageMode.CHAT,
            false, emptyList(), null,
        )
        val new = old.copy(identity = com.labteto.dshmobile.uicontract.UiSessionIdentity(
            "new-story", "activation-new", 2L, null, null))
        assertTrue(!localNotificationHomeReady("new-story", old))
        assertTrue(!localNotificationHomeReady("new-story", old.copy(loading = true)))
        assertTrue(!localNotificationHomeReady("new-story", new.copy(loading = true)))
        assertTrue(!localNotificationHomeReady(null, new))
        assertTrue(!localNotificationHomeReady("new-story", null))
        assertTrue(localNotificationHomeReady("new-story", new))
    }

    @Test fun staleSelectionMustNotSilentlyRenderOldV6() {
        val decision = decideApplicationUi("uninstalled", emptyList(), requirements)
        assertTrue(decision is LocalApplicationUiDecision.Unavailable)
        assertTrue((decision as LocalApplicationUiDecision.Unavailable).rejection
            is UiApplicationPackAdmission.UnknownPack)
    }
}

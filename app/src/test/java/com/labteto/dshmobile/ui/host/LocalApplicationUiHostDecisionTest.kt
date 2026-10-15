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

    @Test fun productionRootDefaultsOnlyToExplicitShippedV6Mode() {
        assertEquals(LocalApplicationUiDecision.Legacy,
            decideApplicationUi(null, emptyList(), requirements))
        assertEquals(LocalApplicationUiDecision.Legacy,
            decideApplicationUi(LEGACY_V6_APP_UI, emptyList(), requirements))
    }

    @Test fun conversationSliceCannotEnterWholeApplicationHost() {
        val slice = pack("next", setOf(UiSceneId("conversation.chat")), setOf(UiSceneId("conversation.chat")))
        val result = decideApplicationUi("next", listOf(slice), requirements)
        assertTrue(result is LocalApplicationUiDecision.Unavailable)
        val rejection = (result as LocalApplicationUiDecision.Unavailable).rejection
        assertTrue(rejection is UiApplicationPackAdmission.Incompatible)
        assertTrue(UiSceneId("route.settings") in (rejection as UiApplicationPackAdmission.Incompatible)
            .compatibility.missingScenes)
    }

    @Test fun aManifestAdvertisingAllScenesButMissingOneRendererIsNotAdmitted() {
        val missing = renderableScenes - UiSceneId("route.settings")
        val result = decideApplicationUi("next", listOf(pack("next", renderableScenes, missing)), requirements)
        assertTrue(result is LocalApplicationUiDecision.Unavailable)
        val rejection = (result as LocalApplicationUiDecision.Unavailable).rejection
        assertTrue(rejection is UiApplicationPackAdmission.Incomplete)
        assertTrue(UiSceneId("route.settings") in (rejection as UiApplicationPackAdmission.Incomplete)
            .coverage.missingRenderers)
    }

    @Test fun fullCatalogWithIndependentRenderersMayEnterProductionHost() {
        val full = pack("next", renderableScenes, renderableScenes)
        val result = decideApplicationUi("next", listOf(full), requirements)
        assertTrue(result is LocalApplicationUiDecision.Hosted)
        assertEquals(full, (result as LocalApplicationUiDecision.Hosted).admission.pack)
        assertEquals(7, renderableScenes.count { it.value.startsWith("route.") })
    }

    @Test fun staleSelectionMustNotSilentlyRenderOldV6() {
        val decision = decideApplicationUi("uninstalled", emptyList(), requirements)
        assertTrue(decision is LocalApplicationUiDecision.Unavailable)
        assertTrue((decision as LocalApplicationUiDecision.Unavailable).rejection
            is UiApplicationPackAdmission.UnknownPack)
    }
}

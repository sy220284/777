package com.labteto.dshmobile.ui.host

import com.labteto.dshmobile.local.feature.LocalFeatureCatalog
import com.labteto.dshmobile.ui.screens.settings.SettingsDestination
import com.labteto.dshmobile.uicontract.UiSceneId
import com.labteto.dshmobile.uicontract.UiSceneKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import com.labteto.dshmobile.uicontract.UiAccessibility
import com.labteto.dshmobile.uicontract.UiWindowClass
import com.labteto.dshmobile.uicontract.checkCompatibility
import com.labteto.dshmobile.uipack.next.NextConversationUiPack
import org.junit.Test

class LocalUiSceneCatalogTest {
    @Test fun verificationPackCannotBeSelectedAsACompleteApplicationPack() {
        val pack = NextConversationUiPack.manifest
        val result = pack.checkCompatibility(1, LocalUiSceneCatalog.requirements,
            pack.requiredCapabilities, UiWindowClass.COMPACT, UiAccessibility.entries.toSet(), false)
        assertFalse(result.compatible)
        assertTrue(UiSceneId("route.settings") in result.missingScenes)
        assertTrue(UiSceneId("settings.account") in result.missingScenes)
    }

    @Test fun requiredCoverageIncludesEveryOwnedRouteAndSettingsDestination() {
        val requirements = LocalUiSceneCatalog.requirements
        val ids = requirements.map { it.id }.toSet()
        assertEquals(requirements.size, ids.size)
        LocalFeatureCatalog.routes.forEach { route ->
            val scene = requirements.single { it.id == UiSceneId("route.${route.name.lowercase()}") }
            assertEquals(LocalFeatureCatalog.ownerOf(route).name, scene.owner)
        }
        val settingPages = requirements.filter {
            it.kind == UiSceneKind.PAGE && it.id.value.startsWith("settings.")
        }.map { it.id }.toSet()
        assertEquals(SettingsDestination.entries.map {
            UiSceneId("settings.${it.name.lowercase()}")
        }.toSet(), settingPages)
    }
}

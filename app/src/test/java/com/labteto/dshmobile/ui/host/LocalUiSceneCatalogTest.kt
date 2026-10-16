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
import com.labteto.dshmobile.uicontract.UiPackManifest
import org.junit.Test

class LocalUiSceneCatalogTest {
    @Test fun verificationPackCannotBeSelectedAsACompleteApplicationPack() {
        val pack = testConversationSliceManifest
        val result = pack.checkCompatibility(1, LocalUiSceneCatalog.requirements,
            pack.requiredCapabilities, UiWindowClass.COMPACT, UiAccessibility.entries.toSet(), false)
        assertFalse(result.compatible)
        assertTrue(UiSceneId("route.settings") in result.missingScenes)
        assertTrue(UiSceneId("settings.account") in result.missingScenes)
    }

    @Test fun updateCheckRemainsAnInlineHostActionRatherThanADeadSettingsRoute() {
        val update = LocalUiSceneCatalog.requirements.single { it.id == UiSceneId("settings.update") }
        assertEquals(UiSceneKind.SLOT, update.kind)
        assertFalse(update.hostProvided)
        assertFalse(SettingsDestination.entries.any { it.name.equals("UPDATE", ignoreCase = true) })
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

/**
 * Pure contract fixture for navigation/compatibility tests.
 * The discontinued Next module must not be compiled or reinstated as a product dependency.
 */
internal val testConversationSliceManifest = UiPackManifest(
    id = "test-conversation-slice",
    version = 1,
    minContractVersion = 1,
    maxContractVersion = 1,
    scenes = setOf(
        "conversation.chat", "conversation.work", "conversation.timeline",
        "work.approval", "work.question",
    ).map(::UiSceneId).toSet(),
    requiredCapabilities = emptySet(),
    windowClasses = UiWindowClass.entries.toSet(),
    accessibility = UiAccessibility.entries.toSet(),
    supportsDark = true,
)

package com.labteto.dshmobile.ui.host

import com.labteto.dshmobile.local.feature.LocalFeatureCatalog
import com.labteto.dshmobile.uicontract.UiSceneId
import com.labteto.dshmobile.uihost.restoreEncodedSnapshot
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
        assertFalse(LocalUiSceneCatalog.requirements.any {
            it.id == UiSceneId("settings.update") && it.kind == UiSceneKind.PAGE
        })
    }

    @Test fun detailActionsCanNavigateAndReturnThroughTheProductionHostCatalog() {
        val manifest = com.labteto.dshmobile.uipack.zhaoxi.ZhaoxiUiPackManifest.let {
            UiPackManifest("detail-navigation-test", 1, 1, 1, it.fullSceneIds,
                emptySet(), UiWindowClass.entries.toSet(), UiAccessibility.entries.toSet(), true)
        }
        val root = UiSceneId("route.home")
        val navigation = com.labteto.dshmobile.uihost.UiHostSceneNavigation(
            LocalUiSceneCatalog.requirements, manifest, root)
        for (id in listOf("conversation.ui_document", "work.plan_review", "chat.branches",
            "media.result", "work.steering_choice", "work.team", "work.capabilities")) {
            val entered = navigation.navigate(navigation.initial, UiSceneId(id))
            assertTrue("$id must be a real destination", entered is com.labteto.dshmobile.uihost.UiSceneNavigationResult.Changed)
            val state = (entered as com.labteto.dshmobile.uihost.UiSceneNavigationResult.Changed).state
            assertEquals(UiSceneId(id), state.stack.last())
            val restored = navigation.restoreEncodedSnapshot(
                com.labteto.dshmobile.uihost.UiSceneNavigationSnapshotCodec.encode(state))
            assertEquals(entered, restored)
            val back = navigation.back(state) as com.labteto.dshmobile.uihost.UiSceneNavigationResult.Changed
            assertEquals(listOf(root), back.state.stack)
        }
        for (id in listOf("conversation.header", "conversation.timeline", "conversation.composer", "settings.update")) {
            assertTrue(navigation.navigate(navigation.initial, UiSceneId(id)) is
                com.labteto.dshmobile.uihost.UiSceneNavigationResult.Rejected)
        }
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
        assertTrue(settingPages.containsAll(setOf(
            UiSceneId("settings.account"), UiSceneId("settings.chat"),
            UiSceneId("settings.session"), UiSceneId("settings.appearance"),
        )))
        assertTrue(settingPages.all { it.value.substringAfter("settings.").isNotBlank() })
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

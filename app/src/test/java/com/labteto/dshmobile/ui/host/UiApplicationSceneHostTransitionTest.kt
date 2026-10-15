package com.labteto.dshmobile.ui.host

import com.labteto.dshmobile.uicontract.UiAccessibility
import com.labteto.dshmobile.uicontract.UiCapabilityId
import com.labteto.dshmobile.uicontract.UiPackManifest
import com.labteto.dshmobile.uicontract.UiSceneId
import com.labteto.dshmobile.uicontract.UiSceneKind
import com.labteto.dshmobile.uicontract.UiSceneRequirement
import com.labteto.dshmobile.uicontract.UiWindowClass
import com.labteto.dshmobile.uihost.UiApplicationPackAdmission
import com.labteto.dshmobile.uihost.UiApplicationPackRegistry
import com.labteto.dshmobile.uihost.UiApplicationScenePack
import com.labteto.dshmobile.uihost.UiHostSceneNavigation
import com.labteto.dshmobile.uihost.UiPackAdmissionRequirements
import com.labteto.dshmobile.uihost.UiSceneNavigationRejection
import com.labteto.dshmobile.uihost.UiSceneNavigationResult
import com.labteto.dshmobile.uihost.UiSceneNavigationSnapshotCodec
import com.labteto.dshmobile.uihost.UiSceneRenderer
import com.labteto.dshmobile.uihost.validateHostedTransition
import org.junit.Assert.assertEquals
import org.junit.Test

class UiApplicationSceneHostTransitionTest {
    private val home = UiSceneId("route.home")
    private val settings = UiSceneId("route.settings")
    private val system = UiSceneId("system.permissions")
    private val scenes = listOf(
        UiSceneRequirement(home, UiSceneKind.PAGE, "SHELL"),
        UiSceneRequirement(settings, UiSceneKind.PAGE, "SETTINGS"),
        UiSceneRequirement(system, UiSceneKind.SYSTEM, "SHELL", hostProvided = true),
    )
    private val manifest = UiPackManifest(
        id = "verified-pack", version = 1, minContractVersion = 1, maxContractVersion = 1,
        scenes = setOf(home, settings),
        requiredCapabilities = setOf(UiCapabilityId("shell.navigation")),
        windowClasses = setOf(UiWindowClass.COMPACT),
        accessibility = setOf(UiAccessibility.SCREEN_READER),
        supportsDark = true,
    )
    private val pack = object : UiApplicationScenePack {
        override val manifest = this@UiApplicationSceneHostTransitionTest.manifest
        override val renderers = mapOf(home to UiSceneRenderer {}, settings to UiSceneRenderer {})
    }
    private val admitted = UiApplicationPackRegistry(listOf(pack)).select(
        "verified-pack",
        UiPackAdmissionRequirements(1, scenes, setOf(UiCapabilityId("shell.navigation")),
            UiWindowClass.COMPACT, setOf(UiAccessibility.SCREEN_READER), false),
    ) as UiApplicationPackAdmission.Admitted
    private val nav = UiHostSceneNavigation(scenes, manifest, home)

    @Test fun validNavigationCommitsOnlyToAdmittedRenderer() {
        val encoded = UiSceneNavigationSnapshotCodec.encode(nav.initial)
        val proposal = nav.navigate(nav.initial, settings)
        assertEquals(proposal, admitted.validateHostedTransition(encoded, encoded, proposal, nav))
    }

    @Test fun oldScreenCallbackCannotChangeTheNewScreen() {
        val old = UiSceneNavigationSnapshotCodec.encode(nav.initial)
        val newState = (nav.navigate(nav.initial, settings) as UiSceneNavigationResult.Changed).state
        val current = UiSceneNavigationSnapshotCodec.encode(newState)
        assertEquals(UiSceneNavigationResult.Rejected(UiSceneNavigationRejection.INVALID_HISTORY),
            admitted.validateHostedTransition(current, old, nav.back(nav.initial), nav))
    }

    @Test fun rejectsUnrestorableOrWrongPackAndHostService() {
        val encoded = UiSceneNavigationSnapshotCodec.encode(nav.initial)
        assertEquals(UiSceneNavigationResult.Rejected(UiSceneNavigationRejection.INVALID_HISTORY),
            admitted.validateHostedTransition("corrupt", "corrupt", nav.back(nav.initial), nav))
        val foreign = nav.initial.copy(packId = "foreign")
        assertEquals(UiSceneNavigationResult.Rejected(UiSceneNavigationRejection.INVALID_HISTORY),
            admitted.validateHostedTransition(encoded, encoded, UiSceneNavigationResult.Changed(foreign), nav))
        val service = nav.initial.copy(stack = listOf(home, system))
        assertEquals(UiSceneNavigationResult.Rejected(UiSceneNavigationRejection.INVALID_HISTORY),
            admitted.validateHostedTransition(encoded, encoded, UiSceneNavigationResult.Changed(service), nav))
    }
}

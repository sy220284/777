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
import com.labteto.dshmobile.uihost.UiSceneRenderAccess
import com.labteto.dshmobile.uihost.UiSceneRenderRejection
import com.labteto.dshmobile.uihost.UiSceneRenderer
import com.labteto.dshmobile.uihost.UiSceneNavigationResult
import com.labteto.dshmobile.uihost.UiSceneNavigationState
import com.labteto.dshmobile.uihost.resolve
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Full-app render admission cannot be forged by a conversation slice's manifest. */
class UiApplicationScenePackTest {
    private fun scene(id: String) = UiSceneId(id)
    private val root = scene("route.home")
    private val settings = scene("route.settings")
    private val service = scene("system.permissions")
    private val requirements = listOf(
        UiSceneRequirement(root, UiSceneKind.PAGE, "SHELL"),
        UiSceneRequirement(settings, UiSceneKind.PAGE, "SETTINGS"),
        UiSceneRequirement(service, UiSceneKind.SYSTEM, "SHELL", hostProvided = true),
    )
    private val manifest = UiPackManifest(
        id = "complete-pack", version = 1, minContractVersion = 1, maxContractVersion = 1,
        scenes = setOf(root, settings),
        requiredCapabilities = setOf(UiCapabilityId("shell.navigation")),
        windowClasses = setOf(UiWindowClass.COMPACT),
        accessibility = setOf(UiAccessibility.SCREEN_READER),
        supportsDark = true,
    )
    private val admission = UiPackAdmissionRequirements(
        contractVersion = 1, requiredScenes = requirements,
        availableCapabilities = setOf(UiCapabilityId("shell.navigation")),
        windowClass = UiWindowClass.COMPACT,
        requiredAccessibility = setOf(UiAccessibility.SCREEN_READER), dark = false,
    )
    private val renderer = UiSceneRenderer { _ -> }
    private fun pack(
        declared: UiPackManifest = manifest,
        implemented: Set<UiSceneId> = setOf(root, settings),
    ) = object : UiApplicationScenePack {
        override val manifest = declared
        override val renderers = implemented.associateWith { renderer }
    }

    @Test fun acceptsIndependentRenderersAndHostOwnsNavigation() {
        val ready = UiApplicationPackRegistry(listOf(pack())).select(manifest.id, admission)
            as UiApplicationPackAdmission.Admitted
        val navigation = UiHostSceneNavigation(requirements, manifest, root)
        val rootRender = ready.resolve(navigation.initial, navigation) as UiSceneRenderAccess.Ready
        assertEquals(root, rootRender.scope.requirement.id)
        val next = rootRender.scope.onNavigate(settings) as UiSceneNavigationResult.Changed
        assertEquals(settings, next.state.stack.last())
        assertEquals(UiSceneNavigationResult.AtRoot, rootRender.scope.onBack())
        assertEquals(settings, (ready.resolve(next.state, navigation) as UiSceneRenderAccess.Ready).scope.requirement.id)
        assertEquals(UiSceneRenderAccess.Rejected(UiSceneRenderRejection.WRONG_PACK),
            ready.resolve(UiSceneNavigationState("foreign", listOf(root)), navigation))
        assertEquals(UiSceneRenderAccess.Rejected(UiSceneRenderRejection.INVALID_HISTORY),
            ready.resolve(navigation.initial.copy(stack = listOf(root, service)), navigation))
    }

    @Test fun refusesDeclaredButUnimplementedPages() {
        val result = UiApplicationPackRegistry(listOf(pack(implemented = setOf(root))))
            .select(manifest.id, admission) as UiApplicationPackAdmission.Incomplete
        assertEquals(setOf(settings), result.coverage.missingRenderers)
    }

    @Test fun refusesHostServicesAndUndeclaredRenderers() {
        val result = UiApplicationPackRegistry(listOf(pack(implemented = setOf(root, settings, service))))
            .select(manifest.id, admission) as UiApplicationPackAdmission.Incomplete
        assertEquals(setOf(service), result.coverage.hostServiceRenderers)
        assertEquals(setOf(service), result.coverage.undeclaredRenderers)
    }

    @Test fun manifestCompatibilityStillGatesMissingOwnerCapabilities() {
        val missing = admission.copy(availableCapabilities = emptySet())
        assertTrue(UiApplicationPackRegistry(listOf(pack()))
            .select(manifest.id, missing) is UiApplicationPackAdmission.Incompatible)
        assertTrue(UiApplicationPackRegistry(listOf(pack()))
            .select("unknown", admission) is UiApplicationPackAdmission.UnknownPack)
    }

    @Test fun unsupportedScenesAndDuplicatePackIdsAreRejected() {
        val extra = scene("future.route")
        val result = UiApplicationPackRegistry(listOf(pack(implemented = setOf(root, settings, extra))))
            .select(manifest.id, admission) as UiApplicationPackAdmission.Incomplete
        assertEquals(setOf(extra), result.coverage.unknownRenderers)
        assertTrue(runCatching { UiApplicationPackRegistry(listOf(pack(), pack())) }.isFailure)
    }
}

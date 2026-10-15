package com.labteto.dshmobile.uihost

import androidx.compose.runtime.Composable
import com.labteto.dshmobile.uicontract.UiPackCompatibility
import com.labteto.dshmobile.uicontract.UiPackManifest
import com.labteto.dshmobile.uicontract.UiSceneId
import com.labteto.dshmobile.uicontract.UiSceneRequirement
import com.labteto.dshmobile.uicontract.checkCompatibility

/**
 * Real scene renderers for an independently compiled, application-wide pack.
 * Unlike a manifest, each entry is an executable Compose rendering entry point.
 * Feature Ports are bound by the App composition root to the pack's typed
 * renderer factories; the Host only supplies navigation, never Owner authority.
 *
 * No currently installed pack implements this whole-application contract yet.
 */
interface UiApplicationScenePack {
    val manifest: UiPackManifest
    val renderers: Map<UiSceneId, UiSceneRenderer>
}

fun interface UiSceneRenderer {
    @Composable
    fun Render(scope: UiSceneRenderScope)
}

/** Host navigation results are proposals: only the Host commits the next state. */
data class UiSceneRenderScope(
    val requirement: UiSceneRequirement,
    val onNavigate: (UiSceneId) -> UiSceneNavigationResult,
    val onBack: () -> UiSceneNavigationResult,
)

data class UiSceneRendererCoverage(
    val missingRenderers: Set<UiSceneId>,
    val undeclaredRenderers: Set<UiSceneId>,
    val hostServiceRenderers: Set<UiSceneId>,
    val unknownRenderers: Set<UiSceneId>,
) {
    val complete: Boolean get() = missingRenderers.isEmpty() &&
        undeclaredRenderers.isEmpty() && hostServiceRenderers.isEmpty() &&
        unknownRenderers.isEmpty()
}

sealed interface UiApplicationPackAdmission {
    data class Admitted(
        val pack: UiApplicationScenePack,
        val requirements: Map<UiSceneId, UiSceneRequirement>,
    ) : UiApplicationPackAdmission

    data class UnknownPack(val id: String) : UiApplicationPackAdmission
    data class Incompatible(val id: String, val compatibility: UiPackCompatibility) : UiApplicationPackAdmission
    data class Incomplete(val id: String, val coverage: UiSceneRendererCoverage) : UiApplicationPackAdmission
}

/**
 * A full-application admission must prove BOTH the manifest and the concrete
 * per-scene renderers. Conversation-only packs are deliberately not accepted.
 * Host-owned system services are never delegated to a UI pack for rendering.
 */
class UiApplicationPackRegistry(packs: Collection<UiApplicationScenePack>) {
    private val registered: Map<String, UiApplicationScenePack>

    init {
        require(packs.map { it.manifest.id }.distinct().size == packs.size) {
            "Duplicate application UI pack identities"
        }
        registered = packs.associateBy { it.manifest.id }
    }

    fun select(
        id: String,
        admission: UiPackAdmissionRequirements,
    ): UiApplicationPackAdmission {
        val pack = registered[id] ?: return UiApplicationPackAdmission.UnknownPack(id)
        val compatibility = pack.manifest.checkCompatibility(
            contractVersion = admission.contractVersion,
            requiredScenes = admission.requiredScenes,
            availableCapabilities = admission.availableCapabilities,
            windowClass = admission.windowClass,
            requiredAccessibility = admission.requiredAccessibility,
            dark = admission.dark,
        )
        if (!compatibility.compatible) return UiApplicationPackAdmission.Incompatible(id, compatibility)

        val required = admission.requiredScenes.associateBy { it.id }
        require(required.size == admission.requiredScenes.size) { "Duplicate Host scene requirements" }
        val rendered = pack.renderers.keys
        val hostServices = admission.requiredScenes.filter { it.hostProvided }.map { it.id }.toSet()
        val coverage = UiSceneRendererCoverage(
            missingRenderers = pack.manifest.scenes - rendered,
            undeclaredRenderers = rendered - pack.manifest.scenes,
            hostServiceRenderers = rendered.intersect(hostServices),
            unknownRenderers = rendered - required.keys,
        )
        return if (coverage.complete) UiApplicationPackAdmission.Admitted(pack, required)
        else UiApplicationPackAdmission.Incomplete(id, coverage)
    }
}

enum class UiSceneRenderRejection { INVALID_HISTORY, WRONG_PACK, SCENE_NOT_RENDERABLE }

sealed interface UiSceneRenderAccess {
    data class Ready(val renderer: UiSceneRenderer, val scope: UiSceneRenderScope) : UiSceneRenderAccess
    data class Rejected(val reason: UiSceneRenderRejection) : UiSceneRenderAccess
}

/** Resolve a scene only from a validated Host back stack, never a raw UI/model string. */
fun UiApplicationPackAdmission.Admitted.resolve(
    state: UiSceneNavigationState,
    navigation: UiHostSceneNavigation,
): UiSceneRenderAccess {
    if (state.packId != pack.manifest.id || navigation.initial.packId != pack.manifest.id)
        return UiSceneRenderAccess.Rejected(UiSceneRenderRejection.WRONG_PACK)
    if (navigation.restore(state) !is UiSceneNavigationResult.Changed)
        return UiSceneRenderAccess.Rejected(UiSceneRenderRejection.INVALID_HISTORY)
    val scene = state.stack.last()
    val requirement = requirements[scene]
        ?: return UiSceneRenderAccess.Rejected(UiSceneRenderRejection.SCENE_NOT_RENDERABLE)
    if (requirement.hostProvided)
        return UiSceneRenderAccess.Rejected(UiSceneRenderRejection.SCENE_NOT_RENDERABLE)
    val renderer = pack.renderers[scene]
        ?: return UiSceneRenderAccess.Rejected(UiSceneRenderRejection.SCENE_NOT_RENDERABLE)
    return UiSceneRenderAccess.Ready(renderer, UiSceneRenderScope(
        requirement = requirement,
        onNavigate = { target -> navigation.navigate(state, target) },
        onBack = { navigation.back(state) },
    ))
}

/** No fallback to a legacy/V6 page when the active pack has no renderer. */
@Composable
fun UiApplicationPackAdmission.Admitted.RenderScene(
    state: UiSceneNavigationState,
    navigation: UiHostSceneNavigation,
): UiSceneRenderAccess {
    val access = resolve(state, navigation)
    if (access is UiSceneRenderAccess.Ready) access.renderer.Render(access.scope)
    return access
}

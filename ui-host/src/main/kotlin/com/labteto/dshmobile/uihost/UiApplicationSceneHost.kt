package com.labteto.dshmobile.uihost

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

/**
 * A renderer callback is only a navigation proposal. Commit it against the live
 * Host snapshot, not the screen state captured by an old asynchronous callback.
 * No missing renderer, foreign pack or invalid history can become visible.
 */
fun UiApplicationPackAdmission.Admitted.validateHostedTransition(
    currentEncoded: String,
    capturedEncoded: String,
    proposal: UiSceneNavigationResult,
    navigation: UiHostSceneNavigation,
    capturedEpoch: Long = 0L,
    currentEpoch: Long = 0L,
): UiSceneNavigationResult {
    if (currentEpoch != capturedEpoch || currentEncoded != capturedEncoded) {
        return UiSceneNavigationResult.Rejected(UiSceneNavigationRejection.INVALID_HISTORY)
    }
    val current = navigation.restoreEncodedSnapshot(currentEncoded)
    if (current !is UiSceneNavigationResult.Changed) return current
    if (resolve(current.state, navigation) !is UiSceneRenderAccess.Ready) {
        return UiSceneNavigationResult.Rejected(UiSceneNavigationRejection.INVALID_HISTORY)
    }
    if (proposal is UiSceneNavigationResult.Changed) {
        // A stale renderer cannot inject a transition from another history or pack.
        val target = navigation.restore(proposal.state)
        if (target !is UiSceneNavigationResult.Changed ||
            resolve(target.state, navigation) !is UiSceneRenderAccess.Ready
        ) {
            return UiSceneNavigationResult.Rejected(UiSceneNavigationRejection.INVALID_HISTORY)
        }
    }
    return proposal
}

/**
 * The real full-app Host renderer. Both hardware Back and UI callbacks commit
 * through one live, versioned SaveableState snapshot. The caller must first
 * obtain full-application admission (manifest AND actual scene renderers).
 *
 * Incomplete conversation-only packs must never be passed here. The production
 * AppRoot switch remains unavailable until whole-app renderers are implemented.
 */
@Composable
fun UiApplicationPackAdmission.Admitted.RenderHostedScene(
    navigation: UiHostSceneNavigation,
    onAtRootBack: () -> Unit,
): UiSceneRenderAccess {
    var encoded by rememberSaveable(pack.manifest.id) {
        mutableStateOf(UiSceneNavigationSnapshotCodec.encode(navigation.initial))
    }
    // A->B->A restores the same encoded stack: track a monotonic transition epoch
    // as well so an asynchronous callback from the first A cannot act in the new A.
    var transitionEpoch by rememberSaveable(pack.manifest.id) { mutableStateOf(0L) }
    val restored = navigation.restoreEncodedSnapshot(encoded)
    if (restored !is UiSceneNavigationResult.Changed) {
        return UiSceneRenderAccess.Rejected(UiSceneRenderRejection.INVALID_HISTORY)
    }
    val access = resolve(restored.state, navigation)
    if (access is UiSceneRenderAccess.Ready) {
        val captured = encoded
        val capturedEpoch = transitionEpoch
        fun commit(proposal: UiSceneNavigationResult): UiSceneNavigationResult {
            val checked = validateHostedTransition(encoded, captured, proposal, navigation,
                capturedEpoch = capturedEpoch, currentEpoch = transitionEpoch)
            when (checked) {
                is UiSceneNavigationResult.Changed -> {
                    val replacement = UiSceneNavigationSnapshotCodec.encode(checked.state)
                    if (replacement != encoded) {
                        encoded = replacement
                        transitionEpoch++
                    }
                }
                UiSceneNavigationResult.AtRoot -> {
                    transitionEpoch++
                    onAtRootBack()
                }
                is UiSceneNavigationResult.Rejected -> Unit
            }
            return checked
        }
        BackHandler { commit(navigation.back(restored.state)) }
        val hosted = access.copy(scope = access.scope.copy(
            onNavigate = { target -> commit(navigation.navigate(restored.state, target)) },
            onBack = { commit(navigation.back(restored.state)) },
        ))
        hosted.renderer.Render(hosted.scope)
        return hosted
    }
    // No legacy-page fallback on rejected/missing scenes.
    return access
}

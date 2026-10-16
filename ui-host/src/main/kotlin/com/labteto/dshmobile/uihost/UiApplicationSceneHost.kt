package com.labteto.dshmobile.uihost

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController

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
    requestedDeepLinkKey: String? = null,
    onDeepLinkProcessed: (String, UiSceneNavigationResult) -> Unit = { _, _ -> },
    persistedNavigationSnapshot: String? = null,
    onNavigationSnapshotUpdated: (String) -> Unit = {},
    // Pure Host-supplied identity, never a UI-pack or model-provided navigation key.
    // Changing sessions must replace the saveable stack as well as the resolver.
    navigationStateScope: String = pack.manifest.id,
): UiSceneRenderAccess {
    var encoded by rememberSaveable(pack.manifest.id, navigationStateScope) {
        mutableStateOf(persistedNavigationSnapshot ?:
            UiSceneNavigationSnapshotCodec.encode(navigation.initial))
    }
    // A->B->A restores the same encoded stack: track a monotonic transition epoch
    // as well so an asynchronous callback from the first A cannot act in the new A.
    var transitionEpoch by rememberSaveable(pack.manifest.id, navigationStateScope) {
        mutableStateOf(0L)
    }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    // The Host owns focus/IME boundaries across real scene and session transitions.
    // Reset only when the validated navigation snapshot or session scope changes;
    // ordinary composer edits and unchanged render recompositions keep their focus.
    LaunchedEffect(encoded, navigationStateScope) {
        focusManager.clearFocus(force = true)
        keyboard?.hide()
    }
    val restored = navigation.restoreEncodedSnapshot(encoded)
    if (restored !is UiSceneNavigationResult.Changed) {
        return UiSceneRenderAccess.Rejected(UiSceneRenderRejection.INVALID_HISTORY)
    }
    val access = resolve(restored.state, navigation)
    if (access is UiSceneRenderAccess.Ready) {
        // Persist only a validated, renderable Host stack, never corrupt or foreign state.
        LaunchedEffect(encoded, navigation) { onNavigationSnapshotUpdated(encoded) }
        // External route requests enter through the same Host-owned whitelist and
        // navigation CAS as renderer proposals; V6 does not silently handle rejects.
        LaunchedEffect(requestedDeepLinkKey, navigation) {
            val key = requestedDeepLinkKey ?: return@LaunchedEffect
            val currentEncoded = encoded
            val currentEpoch = transitionEpoch
            val active = navigation.restoreEncodedSnapshot(currentEncoded)
            val proposal = if (active is UiSceneNavigationResult.Changed)
                navigation.openDeepLink(active.state, key) else active
            val result = validateHostedTransition(
                currentEncoded, currentEncoded, proposal, navigation,
                capturedEpoch = currentEpoch, currentEpoch = transitionEpoch,
            )
            if (result is UiSceneNavigationResult.Changed) {
                val replacement = UiSceneNavigationSnapshotCodec.encode(result.state)
                if (replacement != encoded) {
                    encoded = replacement
                    transitionEpoch++
                }
            }
            onDeepLinkProcessed(key, result)
        }
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

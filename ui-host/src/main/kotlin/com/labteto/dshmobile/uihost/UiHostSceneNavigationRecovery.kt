package com.labteto.dshmobile.uihost

/** Restore only after rechecking the current Host catalog and the selected pack manifest. */
fun UiHostSceneNavigation.restoreEncodedSnapshot(encoded: String?): UiSceneNavigationResult {
    val decoded = UiSceneNavigationSnapshotCodec.decodeOrNull(encoded)
        ?: return UiSceneNavigationResult.Rejected(UiSceneNavigationRejection.INVALID_HISTORY)
    return restore(decoded)
}

/**
 * Versioned navigation migration only for a structurally valid snapshot from this
 * SAME pack. A removed trailing scene is discarded, preserving its last usable
 * ancestor. Malformed data, foreign packs and foreign roots remain rejected.
 * This function is ONLY for one-time restoration, never live renderer callbacks.
 */
fun UiHostSceneNavigation.recoverPersistedEncodedSnapshot(encoded: String?): UiSceneNavigationResult {
    val decoded = UiSceneNavigationSnapshotCodec.decodeOrNull(encoded)
        ?: return UiSceneNavigationResult.Rejected(UiSceneNavigationRejection.INVALID_HISTORY)
    if (decoded.packId != initial.packId)
        return UiSceneNavigationResult.Rejected(UiSceneNavigationRejection.WRONG_PACK)
    if (decoded.stack.firstOrNull() != initial.stack.first())
        return UiSceneNavigationResult.Rejected(UiSceneNavigationRejection.INVALID_HISTORY)
    var recovered = initial
    for (scene in decoded.stack.drop(1)) {
        val next = recovered.copy(stack = recovered.stack + scene)
        val admission = restore(next)
        if (admission !is UiSceneNavigationResult.Changed) break
        recovered = admission.state
    }
    return UiSceneNavigationResult.Changed(recovered)
}

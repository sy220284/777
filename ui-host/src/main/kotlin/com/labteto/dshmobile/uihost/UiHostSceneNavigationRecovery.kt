package com.labteto.dshmobile.uihost

/** Restore only after rechecking the current Host catalog and the selected pack manifest. */
fun UiHostSceneNavigation.restoreEncodedSnapshot(encoded: String?): UiSceneNavigationResult {
    val decoded = UiSceneNavigationSnapshotCodec.decodeOrNull(encoded)
        ?: return UiSceneNavigationResult.Rejected(UiSceneNavigationRejection.INVALID_HISTORY)
    return restore(decoded)
}

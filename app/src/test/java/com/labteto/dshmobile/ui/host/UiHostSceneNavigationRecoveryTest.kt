package com.labteto.dshmobile.ui.host

import com.labteto.dshmobile.uihost.UiHostSceneNavigation
import com.labteto.dshmobile.uihost.UiSceneNavigationRejection
import com.labteto.dshmobile.uihost.UiSceneNavigationResult
import com.labteto.dshmobile.uihost.UiSceneNavigationSnapshotCodec
import com.labteto.dshmobile.uihost.restoreEncodedSnapshot
import com.labteto.dshmobile.uicontract.UiSceneId
import org.junit.Assert.assertEquals
import org.junit.Test

class UiHostSceneNavigationRecoveryTest {
    private val nav = UiHostSceneNavigation(
        requiredScenes = LocalUiSceneCatalog.requirements,
        pack = testConversationSliceManifest,
        root = UiSceneId("conversation.chat"),
    )

    @Test fun actualHostRestoreChecksCapturedPackAndSceneCoverage() {
        val work = (nav.navigate(nav.initial, UiSceneId("conversation.work"))
            as UiSceneNavigationResult.Changed).state
        assertEquals(UiSceneNavigationResult.Changed(work),
            nav.restoreEncodedSnapshot(UiSceneNavigationSnapshotCodec.encode(work)))

        assertEquals(
            UiSceneNavigationResult.Rejected(UiSceneNavigationRejection.WRONG_PACK),
            nav.restoreEncodedSnapshot(UiSceneNavigationSnapshotCodec.encode(work.copy(packId = "v6"))),
        )
        val forged = work.copy(stack = listOf(UiSceneId("conversation.chat"), UiSceneId("route.settings")))
        assertEquals(
            UiSceneNavigationResult.Rejected(UiSceneNavigationRejection.INVALID_HISTORY),
            nav.restoreEncodedSnapshot(UiSceneNavigationSnapshotCodec.encode(forged)),
        )
        assertEquals(
            UiSceneNavigationResult.Rejected(UiSceneNavigationRejection.INVALID_HISTORY),
            nav.restoreEncodedSnapshot("malformed"),
        )
    }
}

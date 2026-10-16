package com.labteto.dshmobile.ui.host

import com.labteto.dshmobile.uihost.UiHostSceneNavigation
import com.labteto.dshmobile.uihost.UiSceneNavigationRejection
import com.labteto.dshmobile.uihost.UiSceneNavigationResult
import com.labteto.dshmobile.uihost.UiSceneNavigationSnapshotCodec
import com.labteto.dshmobile.uihost.restoreEncodedSnapshot
import com.labteto.dshmobile.uicontract.UiSceneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import java.util.Base64
import org.junit.Test

class UiHostSceneNavigationRecoveryTest {
    private val nav = UiHostSceneNavigation(
        requiredScenes = LocalUiSceneCatalog.requirements,
        pack = testConversationSliceManifest,
        root = UiSceneId("conversation.chat"),
    )

    @Test fun malformedUtf8OrNoncanonicalBase64NeverReopensNavigation() {
        val encoded = UiSceneNavigationSnapshotCodec.encode(nav.initial).split('.')
        val invalidUtf8 = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(byteArrayOf(0xc3.toByte(), 0x28))
        val badUtf = encoded.toMutableList().apply { this[2] = invalidUtf8 }.joinToString(".")
        assertNull(UiSceneNavigationSnapshotCodec.decodeOrNull(badUtf))
        assertEquals(
            UiSceneNavigationResult.Rejected(UiSceneNavigationRejection.INVALID_HISTORY),
            nav.restoreEncodedSnapshot(badUtf),
        )
        val padded = encoded.toMutableList().apply { this[2] = "YQ==" }.joinToString(".")
        assertNull(UiSceneNavigationSnapshotCodec.decodeOrNull(padded))
        assertEquals(
            UiSceneNavigationResult.Rejected(UiSceneNavigationRejection.INVALID_HISTORY),
            nav.restoreEncodedSnapshot(padded),
        )
        assertEquals(nav.initial, UiSceneNavigationSnapshotCodec.decodeOrNull(
            UiSceneNavigationSnapshotCodec.encode(nav.initial)))
    }

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

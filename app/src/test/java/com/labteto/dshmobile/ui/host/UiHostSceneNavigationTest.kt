package com.labteto.dshmobile.ui.host

import com.labteto.dshmobile.uihost.UiHostSceneNavigation
import com.labteto.dshmobile.uihost.UiSceneNavigationRejection
import com.labteto.dshmobile.uihost.UiSceneNavigationResult
import com.labteto.dshmobile.uihost.UiSceneNavigationState
import com.labteto.dshmobile.uihost.UiSceneNavigationSnapshotCodec
import com.labteto.dshmobile.uicontract.UiSceneId
import com.labteto.dshmobile.uipack.next.NextConversationUiPack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A conversation slice cannot be promoted to a complete UI through navigation alone. */
class UiHostSceneNavigationTest {
    private fun id(value: String) = UiSceneId(value)
    private val nav = UiHostSceneNavigation(
        requiredScenes = LocalUiSceneCatalog.requirements,
        pack = NextConversationUiPack.manifest,
        root = id("conversation.chat"),
        deepLinks = mapOf("chat" to id("conversation.chat"), "work" to id("conversation.work")),
    )

    private fun state(result: UiSceneNavigationResult) =
        (result as UiSceneNavigationResult.Changed).state

    @Test fun pageAndOverlayUseHostOwnedBackStack() {
        val work = state(nav.navigate(nav.initial, id("conversation.work")))
        val approval = state(nav.navigate(work, id("work.approval")))
        assertEquals(listOf(id("conversation.chat"), id("conversation.work"), id("work.approval")), approval.stack)
        assertEquals(work, state(nav.back(approval)))
        assertEquals(nav.initial, state(nav.back(work)))
        assertEquals(UiSceneNavigationResult.AtRoot, nav.back(nav.initial))
    }

    @Test fun switchingPagesClosesTransientOverlays() {
        val approval = state(nav.navigate(nav.initial, id("work.approval")))
        val work = state(nav.navigate(approval, id("conversation.work")))
        assertEquals(listOf(id("conversation.chat"), id("conversation.work")), work.stack)
    }

    @Test fun sceneMetadataNeverAdmitsUnimplementedPagesOrHostServices() {
        assertEquals(UiSceneNavigationResult.Rejected(UiSceneNavigationRejection.PACK_MISSING_SCENE),
            nav.navigate(nav.initial, id("route.settings")))
        assertEquals(UiSceneNavigationResult.Rejected(UiSceneNavigationRejection.HOST_SERVICE),
            nav.navigate(nav.initial, id("system.permissions")))
        assertEquals(UiSceneNavigationResult.Rejected(UiSceneNavigationRejection.NOT_NAVIGABLE),
            nav.navigate(nav.initial, id("conversation.timeline")))
        assertEquals(UiSceneNavigationResult.Rejected(UiSceneNavigationRejection.UNKNOWN_SCENE),
            nav.navigate(nav.initial, id("future.unknown")))
    }

    @Test fun deepLinksRequireHostPublishedKeys() {
        assertEquals(id("conversation.work"),
            state(nav.openDeepLink(nav.initial, "work")).stack.last())
        assertEquals(UiSceneNavigationResult.Rejected(UiSceneNavigationRejection.UNKNOWN_DEEP_LINK),
            nav.openDeepLink(nav.initial, "https://example.com/route.settings"))
    }

    @Test fun restoreRejectsForeignPackAndForgedOrRetiredScenes() {
        val oldPack = UiSceneNavigationState("v6", listOf(id("conversation.chat")))
        assertEquals(UiSceneNavigationResult.Rejected(UiSceneNavigationRejection.WRONG_PACK),
            nav.restore(oldPack))
        val forged = nav.initial.copy(stack = listOf(id("conversation.chat"), id("route.settings")))
        assertEquals(UiSceneNavigationResult.Rejected(UiSceneNavigationRejection.INVALID_HISTORY),
            nav.restore(forged))
        assertEquals(UiSceneNavigationResult.Rejected(UiSceneNavigationRejection.INVALID_HISTORY),
            nav.restore(nav.initial.copy(stack = emptyList())))
        val valid = state(nav.navigate(nav.initial, id("conversation.work")))
        assertEquals(valid, state(nav.restore(valid)))
        assertEquals(UiSceneNavigationResult.Rejected(UiSceneNavigationRejection.WRONG_PACK),
            nav.navigate(oldPack, id("conversation.work")))
    }

    @Test fun registrationDoesNotAllowAHostServiceAsARootOrDeepLink() {
        val manifest = NextConversationUiPack.manifest
        val badRoot = runCatching {
            UiHostSceneNavigation(LocalUiSceneCatalog.requirements, manifest, id("route.settings"))
        }
        assertTrue(badRoot.isFailure)
        val badDeepLink = runCatching {
            UiHostSceneNavigation(LocalUiSceneCatalog.requirements, manifest,
                id("conversation.chat"), mapOf("permissions" to id("system.permissions")))
        }
        assertTrue(badDeepLink.isFailure)
    }

    @Test fun rootNavigationResetsHistoryAndSamePageDismissesOverlay() {
        val work = state(nav.navigate(nav.initial, id("conversation.work")))
        val approval = state(nav.navigate(work, id("work.approval")))
        assertEquals(work, state(nav.navigate(approval, id("conversation.work"))))
        assertEquals(nav.initial, state(nav.navigate(approval, id("conversation.chat"))))
    }

    @Test fun restoreRejectsOverlayBeforePageAndRepeatedRoot() {
        assertEquals(
            UiSceneNavigationResult.Rejected(UiSceneNavigationRejection.INVALID_HISTORY),
            nav.restore(nav.initial.copy(stack = listOf(id("conversation.chat"),
                id("work.approval"), id("conversation.work")))),
        )
        assertEquals(
            UiSceneNavigationResult.Rejected(UiSceneNavigationRejection.INVALID_HISTORY),
            nav.restore(nav.initial.copy(stack = listOf(id("conversation.chat"),
                id("conversation.work"), id("conversation.chat")))),
        )
    }

    @Test fun navigationBudgetRejectsOverflowWithoutDroppingPreviousState() {
        var snapshot = state(nav.navigate(nav.initial, id("conversation.work")))
        var rejected: UiSceneNavigationResult? = null
        for (i in 1..UiSceneNavigationSnapshotCodec.MAX_STACK_DEPTH) {
            val overlay = id(if (i % 2 == 0) "work.question" else "work.approval")
            val result = nav.navigate(snapshot, overlay)
            if (result is UiSceneNavigationResult.Rejected) {
                rejected = result
                break
            }
            snapshot = state(result)
        }
        assertEquals(
            UiSceneNavigationResult.Rejected(UiSceneNavigationRejection.HISTORY_LIMIT), rejected,
        )
        assertEquals(UiSceneNavigationSnapshotCodec.MAX_STACK_DEPTH, snapshot.stack.size)
        assertEquals(snapshot, state(nav.restore(snapshot)))
    }

    @Test fun versionedMementoRejectsCorruptionAndChecksPackOnRestore() {
        val work = state(nav.navigate(nav.initial, id("conversation.work")))
        val stored = UiSceneNavigationSnapshotCodec.encode(work)
        assertEquals(work, UiSceneNavigationSnapshotCodec.decodeOrNull(stored))
        assertEquals(work, state(nav.restore(UiSceneNavigationSnapshotCodec.decodeOrNull(stored)!!)))
        assertEquals(null, UiSceneNavigationSnapshotCodec.decodeOrNull("old." + stored))
        assertEquals(null, UiSceneNavigationSnapshotCodec.decodeOrNull(stored + "."))
        assertEquals(null, UiSceneNavigationSnapshotCodec.decodeOrNull("u1.invalid"))
        assertEquals(null, UiSceneNavigationSnapshotCodec.decodeOrNull("x".repeat(9_000)))
        assertEquals(null, UiSceneNavigationSnapshotCodec.decodeOrNull(null))
        assertEquals(UiSceneNavigationResult.Rejected(UiSceneNavigationRejection.WRONG_PACK),
            nav.restore(work.copy(packId = "another-pack")))
        assertTrue(runCatching {
            UiSceneNavigationSnapshotCodec.encode(work.copy(stack =
                List(UiSceneNavigationSnapshotCodec.MAX_STACK_DEPTH + 1) { id("conversation.work") }))
        }.isFailure)
    }
}

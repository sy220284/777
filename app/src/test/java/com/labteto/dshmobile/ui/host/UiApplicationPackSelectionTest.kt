package com.labteto.dshmobile.ui.host

import com.labteto.dshmobile.uihost.UiApplicationPackAdmission
import com.labteto.dshmobile.uihost.UiApplicationPackPreference
import com.labteto.dshmobile.uihost.UiApplicationPackRegistry
import com.labteto.dshmobile.uihost.UiApplicationPackSelection
import com.labteto.dshmobile.uihost.UiApplicationPackSelectionResult
import com.labteto.dshmobile.uihost.UiApplicationScenePack
import com.labteto.dshmobile.uihost.UiPackAdmissionRequirements
import com.labteto.dshmobile.uihost.UiSceneRenderer
import com.labteto.dshmobile.uicontract.UiPackManifest
import com.labteto.dshmobile.uicontract.UiSceneId
import com.labteto.dshmobile.uicontract.UiSceneKind
import com.labteto.dshmobile.uicontract.UiSceneRequirement
import com.labteto.dshmobile.uicontract.UiWindowClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UiApplicationPackSelectionTest {
    private val page = UiSceneId("route.home")
    private val scene = UiSceneRequirement(page, UiSceneKind.PAGE, "SHELL")
    private val requirements = UiPackAdmissionRequirements(
        contractVersion = 1, requiredScenes = listOf(scene),
        availableCapabilities = emptySet(), windowClass = UiWindowClass.COMPACT,
        requiredAccessibility = emptySet(), dark = false,
    )
    private fun pack(id: String, actualRenderer: Boolean = true): UiApplicationScenePack =
        object : UiApplicationScenePack {
            override val manifest = UiPackManifest(
                id = id, version = 1, minContractVersion = 1, maxContractVersion = 1,
                scenes = setOf(page), requiredCapabilities = emptySet(),
                windowClasses = setOf(UiWindowClass.COMPACT), accessibility = emptySet(),
                supportsDark = false,
            )
            override val renderers = if (actualRenderer) mapOf(page to UiSceneRenderer { _ -> }) else emptyMap()
        }

    private class Preference(var saved: String? = null) : UiApplicationPackPreference {
        var writes = 0
        var fail = false
        override fun readSelectedId(): String? = saved
        override fun persistSelectedId(id: String): Boolean {
            writes++
            if (fail) return false
            saved = id
            return true
        }
    }

    @Test fun selectsOnlyVerifiedSceneCoverageAndPersistsOneChoice() {
        val prefs = Preference("stable")
        val host = UiApplicationPackSelection(
            UiApplicationPackRegistry(listOf(pack("stable"), pack("candidate"))), prefs, "stable")
        val selected = host.choose("candidate", requirements) as UiApplicationPackSelectionResult.Selected
        assertEquals("candidate", selected.admission.pack.manifest.id)
        assertEquals("candidate", prefs.saved)
        assertEquals("candidate", (host.restore(requirements) as UiApplicationPackSelectionResult.Selected).admission.pack.manifest.id)
        assertEquals(1, prefs.writes)
    }

    @Test fun rejectsIncompleteAndUnknownPackWithoutChangingPersistedSelection() {
        val prefs = Preference("stable")
        val host = UiApplicationPackSelection(
            UiApplicationPackRegistry(listOf(pack("stable"), pack("candidate", actualRenderer = false))),
            prefs, "stable")
        val invalid = host.choose("candidate", requirements) as UiApplicationPackSelectionResult.Rejected
        assertTrue(invalid.reason is UiApplicationPackAdmission.Incomplete)
        assertEquals("stable", invalid.stableOption?.pack?.manifest?.id)
        val unknown = host.choose("uninstalled", requirements) as UiApplicationPackSelectionResult.Rejected
        assertTrue(unknown.reason is UiApplicationPackAdmission.UnknownPack)
        assertEquals("stable", prefs.saved)
        assertEquals(0, prefs.writes)
    }

    @Test fun stalePersistedSelectionIsReportedAndNeverSilentlyRendersStablePack() {
        val prefs = Preference("retired-candidate")
        val host = UiApplicationPackSelection(
            UiApplicationPackRegistry(listOf(pack("stable"))), prefs, "stable")
        val result = host.restore(requirements) as UiApplicationPackSelectionResult.Rejected
        assertEquals("retired-candidate", result.requestedId)
        assertEquals("stable", result.stableOption?.pack?.manifest?.id)
        assertEquals("retired-candidate", prefs.saved)
        assertEquals(0, prefs.writes)
    }

    @Test fun failedDurableWriteDoesNotClaimSelectionSuccess() {
        val prefs = Preference("stable").apply { fail = true }
        val host = UiApplicationPackSelection(
            UiApplicationPackRegistry(listOf(pack("stable"), pack("candidate"))), prefs, "stable")
        assertTrue(host.choose("candidate", requirements) is UiApplicationPackSelectionResult.PersistenceFailed)
        assertEquals("stable", prefs.saved)
        assertEquals(1, prefs.writes)
    }
}

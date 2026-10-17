package com.labteto.dshmobile.ui.host

import com.labteto.dshmobile.uicontract.UiAccessibility
import com.labteto.dshmobile.uicontract.UiPackManifest
import com.labteto.dshmobile.uicontract.UiSceneId
import com.labteto.dshmobile.uicontract.UiWindowClass
import com.labteto.dshmobile.uihost.UiApplicationPackInstallation
import com.labteto.dshmobile.uihost.UiApplicationScenePack
import com.labteto.dshmobile.uihost.UiSceneRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class UiApplicationPackInstallationTest {
    private fun pack(id: String): UiApplicationScenePack = object : UiApplicationScenePack {
        override val manifest = UiPackManifest(
            id = id, version = 1, minContractVersion = 1, maxContractVersion = 1,
            scenes = emptySet(), requiredCapabilities = emptySet(),
            windowClasses = setOf(UiWindowClass.COMPACT),
            accessibility = UiAccessibility.entries.toSet(), supportsDark = true,
        )
        override val renderers: Map<UiSceneId, UiSceneRenderer> = emptyMap()
    }

    @Test fun newPackCanBeDefaultWithoutV6() {
        val installed = UiApplicationPackInstallation(listOf(pack("new-skin")), "new-skin")
        assertEquals("new-skin", installed.resolveSelectedId(null))
    }

    @Test fun unknownSavedPackRemainsUnknown() {
        val installed = UiApplicationPackInstallation(listOf(pack("new-skin")), "new-skin")
        assertEquals("missing-pack", installed.resolveSelectedId("missing-pack"))
    }

    @Test fun historicalV6AliasesResolveOnlyWhenV6IsInstalled() {
        val installed = UiApplicationPackInstallation(
            listOf(pack("v6"), pack("next")), "next",
            legacyAliases = mapOf("legacy-v6" to "v6"),
        )
        assertEquals("v6", installed.resolveSelectedId("legacy-v6"))
        assertEquals("next", installed.resolveSelectedId(null))
        assertEquals("next", installed.resolveSelectedId("next"))
    }

    @Test fun invalidDefaultAliasAndDuplicateIdsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            UiApplicationPackInstallation(listOf(pack("v6")), "missing")
        }
        assertThrows(IllegalArgumentException::class.java) {
            UiApplicationPackInstallation(listOf(pack("v6")), "v6",
                legacyAliases = mapOf("legacy-v6" to "missing"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            UiApplicationPackInstallation(listOf(pack("v6"), pack("v6")), "v6")
        }
    }
}

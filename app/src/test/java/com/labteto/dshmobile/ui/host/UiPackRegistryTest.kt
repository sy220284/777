package com.labteto.dshmobile.ui.host

import com.labteto.dshmobile.uihost.UiPackAdmission
import com.labteto.dshmobile.uihost.UiPackAdmissionRequirements
import com.labteto.dshmobile.uihost.UiPackRegistry
import com.labteto.dshmobile.uicontract.UiAccessibility
import com.labteto.dshmobile.uicontract.UiSceneId
import com.labteto.dshmobile.uicontract.UiWindowClass
import com.labteto.dshmobile.uipack.next.NextConversationUiPack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** No fallback to the old V6 implementation may satisfy missing Next scenes. */
class UiPackRegistryTest {
    private val registry = UiPackRegistry(listOf(NextConversationUiPack))
    private val manifest = NextConversationUiPack.manifest

    private fun requirements(scenes: List<com.labteto.dshmobile.uicontract.UiSceneRequirement>) =
        UiPackAdmissionRequirements(
            contractVersion = 1, requiredScenes = scenes,
            // Test-only fixture. Production must supply live Host/Owner capability inventory.
            availableCapabilities = manifest.requiredCapabilities,
            windowClass = UiWindowClass.COMPACT,
            requiredAccessibility = UiAccessibility.entries.toSet(),
            dark = true,
        )

    @Test fun unknownPackDoesNotFallbackToV6OrNext() {
        val result = registry.select("missing-pack", requirements(emptyList()))
        assertEquals(UiPackAdmission.UnknownPack("missing-pack"), result)
    }

    @Test fun conversationSliceIsRejectedAsWholeApplication() {
        val result = registry.select(manifest.id, requirements(LocalUiSceneCatalog.requirements))
        assertTrue(result is UiPackAdmission.Rejected)
        result as UiPackAdmission.Rejected
        assertTrue(UiSceneId("settings.root") in result.compatibility.missingScenes)
        assertTrue(UiSceneId("route.tools") in result.compatibility.missingScenes)
    }

    @Test fun anExplicitlyLimitedConversationSliceCanBeAdmitted() {
        val necessary = LocalUiSceneCatalog.requirements.filter { it.id.value in setOf(
            "conversation.chat", "conversation.work", "conversation.timeline", "conversation.composer",
            "work.approval", "work.question", "conversation.ui_document"
        ) }
        val result = registry.select(manifest.id, requirements(necessary))
        assertTrue(result is UiPackAdmission.Admitted)
    }

    @Test(expected = IllegalArgumentException::class)
    fun duplicatePackIdentityCannotBeRegistered() {
        UiPackRegistry(listOf(NextConversationUiPack, NextConversationUiPack))
    }
}

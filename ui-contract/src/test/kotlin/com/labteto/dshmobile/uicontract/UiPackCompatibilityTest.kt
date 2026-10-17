package com.labteto.dshmobile.uicontract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiPackCompatibilityTest {
    private val conversation = UiSceneRequirement(UiSceneId("conversation"), UiSceneKind.PAGE, "CHAT")
    private val composer = UiSceneRequirement(UiSceneId("composer"), UiSceneKind.SLOT, "CHAT")
    private val picker = UiSceneRequirement(UiSceneId("file-picker"), UiSceneKind.SYSTEM, "SHELL", hostProvided = true)
    private val send = UiCapabilityId("chat.send")
    private val pack = UiPackManifest("next", 1, 1, 1, setOf(conversation.id, composer.id),
        setOf(send), setOf(UiWindowClass.COMPACT), UiAccessibility.entries.toSet(), true)

    @Test fun neutralSystemScenesDoNotRequireOldPackRenderers() {
        assertTrue(pack.checkCompatibility(1, listOf(conversation, composer, picker), setOf(send),
            UiWindowClass.COMPACT, UiAccessibility.entries.toSet(), true).compatible)
    }

    @Test fun aConversationSliceCannotClaimCoverageOfTheWholeApp() {
        val settings = UiSceneRequirement(UiSceneId("settings"), UiSceneKind.PAGE, "SETTINGS")
        val result = pack.checkCompatibility(1, listOf(conversation, composer, settings), setOf(send),
            UiWindowClass.COMPACT, emptySet(), false)
        assertFalse(result.compatible)
        assertEquals(setOf(settings.id), result.missingScenes)
    }

    @Test fun incompatibleVersionCapabilityWindowAndAccessibilityAreReportedTogether() {
        val result = pack.copy(accessibility = emptySet(), supportsDark = false).checkCompatibility(
            2, listOf(conversation), emptySet(), UiWindowClass.EXPANDED,
            setOf(UiAccessibility.SCREEN_READER), true)
        assertFalse(result.compatible)
        assertFalse(result.contractCompatible)
        assertFalse(result.windowCompatible)
        assertFalse(result.appearanceCompatible)
        assertEquals(setOf(send), result.missingCapabilities)
        assertEquals(setOf(UiAccessibility.SCREEN_READER), result.missingAccessibility)
    }
}

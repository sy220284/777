package com.labteto.dshmobile.uipack.v6

import com.labteto.dshmobile.uicontract.UiChatGroupChoice
import com.labteto.dshmobile.uicontract.UiChatGroupManagementSnapshot
import com.labteto.dshmobile.uicontract.UiSessionIdentity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The UI validates exactly the current Chat Owner limits and offered gallery identities. */
class V6GroupSelectionTest {
    private val identity = UiSessionIdentity("session", "activation", 1, null, null)
    private val options = (1..6).map { UiChatGroupChoice("persona-$it", "人物 $it") }
    private fun snapshot(
        busy: Boolean = false,
        min: Int = 2,
        max: Int = 4,
    ) = UiChatGroupManagementSnapshot(
        identity, active = true, memberIds = setOf("persona-1", "persona-2"),
        available = options, busy = busy, minimumMembers = min, maximumMembers = max,
    )

    @Test fun validMembersAreAcceptedAtBothOwnerBounds() {
        assertTrue(validV6GroupSelection(snapshot(), setOf("persona-1", "persona-2")))
        assertTrue(validV6GroupSelection(snapshot(), (1..4).map { "persona-$it" }.toSet()))
    }

    @Test fun emptySingletonAndOversizeSetsAreRejected() {
        assertFalse(validV6GroupSelection(snapshot(), emptySet()))
        assertFalse(validV6GroupSelection(snapshot(), setOf("persona-1")))
        assertFalse(validV6GroupSelection(snapshot(), (1..5).map { "persona-$it" }.toSet()))
    }

    @Test fun unavailableIdentitiesCannotBeSubmittedEvenAtValidSize() {
        assertFalse(validV6GroupSelection(snapshot(), setOf("persona-1", "forged-gallery-id")))
    }

    @Test fun ownerBusyAndCustomMemberLimitsAreRespected() {
        assertFalse(validV6GroupSelection(snapshot(busy = true), setOf("persona-1", "persona-2")))
        assertFalse(validV6GroupSelection(snapshot(min = 3), setOf("persona-1", "persona-2")))
        assertTrue(validV6GroupSelection(snapshot(min = 3),
            setOf("persona-1", "persona-2", "persona-3")))
    }
}

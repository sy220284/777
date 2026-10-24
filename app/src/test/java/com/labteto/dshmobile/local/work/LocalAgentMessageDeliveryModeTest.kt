package com.labteto.dshmobile.local.work

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Codex-style dual-mode delivery semantics: admission != activation != processing. */
class LocalAgentMessageDeliveryModeTest {
    @Test fun legacyToolsStillActivateByDefault() {
        assertEquals(LocalAgentMessageDeliveryMode.TRIGGER_TURN,
            LocalAgentMessageDeliveryMode.fromWire(null))
        assertEquals(LocalAgentMessageDeliveryMode.TRIGGER_TURN,
            LocalAgentMessageDeliveryMode.fromWire(""))
        assertEquals(LocalAgentMessageDeliveryMode.TRIGGER_TURN,
            LocalAgentMessageDeliveryMode.fromWire("trigger_turn"))
        assertTrue(shouldWakeAgentAfterAcceptedMessage(
            LocalAgentMessageDeliveryMode.TRIGGER_TURN, accepted = true, requiresResume = true))
    }

    @Test fun queueOnlyNeverStartsARestoreEvenWhenTheJobIsDormant() {
        val mode = LocalAgentMessageDeliveryMode.fromWire("queue_only")
        assertEquals(LocalAgentMessageDeliveryMode.QUEUE_ONLY, mode)
        assertFalse(shouldWakeAgentAfterAcceptedMessage(mode, accepted = true, requiresResume = true))
        assertFalse(shouldWakeAgentAfterAcceptedMessage(mode, accepted = true, requiresResume = false))
        assertFalse(shouldWakeAgentAfterAcceptedMessage(mode, accepted = false, requiresResume = true))
    }

    @Test fun rejectedOrAlreadyRunningDeliveryDoesNotStartADuplicateRestore() {
        val mode = LocalAgentMessageDeliveryMode.TRIGGER_TURN
        assertFalse(shouldWakeAgentAfterAcceptedMessage(mode, accepted = false, requiresResume = true))
        assertFalse(shouldWakeAgentAfterAcceptedMessage(mode, accepted = true, requiresResume = false))
    }

    @Test fun unknownDeliveryModeFailsClosedBeforeAnyJobSideEffect() {
        for (input in listOf("wake", "resume", "force", "root", "none")) {
            assertTrue(input, runCatching {
                LocalAgentMessageDeliveryMode.fromWire(input)
            }.isFailure)
        }
    }
}

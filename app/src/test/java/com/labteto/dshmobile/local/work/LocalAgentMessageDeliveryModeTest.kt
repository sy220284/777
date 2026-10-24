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

    @Test fun queueOnlyReceiptMustNotPromiseDeliveryOrFutureActivation() {
        val receipt = renderLocalAgentMessageSubmission(
            messageId = "msg-01",
            mode = LocalAgentMessageDeliveryMode.QUEUE_ONLY,
            accepted = true, duplicate = false, requiresResume = true,
        )
        assertTrue(receipt.contains("accepted=true"))
        assertTrue(receipt.contains("delivery_mode=queue_only"))
        assertTrue(receipt.contains("wake_requested=false"))
        assertTrue(receipt.contains("processing_status=unverified"))
        assertTrue(receipt.contains("completion_status=unverified"))
        assertFalse(receipt.contains("已发送给"))
    }

    @Test fun triggerTurnReportsWakeRequestButNeverFakesSuccessfulActivation() {
        val receipt = renderLocalAgentMessageSubmission(
            messageId = "msg-02",
            mode = LocalAgentMessageDeliveryMode.TRIGGER_TURN,
            accepted = true, duplicate = false, requiresResume = true,
        )
        assertTrue(receipt.contains("wake_requested=true"))
        assertTrue(receipt.contains("processing_status=unverified"))
        val rejected = renderLocalAgentMessageSubmission(
            messageId = "msg-03",
            mode = LocalAgentMessageDeliveryMode.TRIGGER_TURN,
            accepted = false, duplicate = false, requiresResume = true,
            rejection = "inbox full",
        )
        assertTrue(rejected.contains("accepted=false"))
        assertTrue(rejected.contains("wake_requested=false"))
        assertTrue(rejected.contains("reason=inbox full"))
        assertTrue(rejected.contains("processing_status=not_admitted"))
    }

    @Test fun duplicateAcceptanceIsNotASecondProcessingAcknowledgement() {
        val receipt = renderLocalAgentMessageSubmission(
            messageId = "msg-04",
            mode = LocalAgentMessageDeliveryMode.QUEUE_ONLY,
            accepted = true, duplicate = true, requiresResume = false,
        )
        assertTrue(receipt.contains("duplicate=true"))
        assertTrue(receipt.contains("processing_status=unverified"))
    }

    @Test fun duplicateDurableInboxAdmissionMustNotCreateAnotherWakeRequest() {
        val mode = LocalAgentMessageDeliveryMode.TRIGGER_TURN
        assertFalse(shouldWakeAgentAfterAcceptedMessage(
            mode, accepted = true, requiresResume = true, duplicate = true,
        ))
        val receipt = renderLocalAgentMessageSubmission(
            messageId = "msg-same",
            mode = mode,
            accepted = true,
            duplicate = true,
            requiresResume = true,
        )
        assertTrue(receipt.contains("accepted=true"))
        assertTrue(receipt.contains("duplicate=true"))
        assertTrue(receipt.contains("wake_requested=false"))
        assertTrue(receipt.contains("processing_status=unverified"))
    }

    @Test fun unknownDeliveryModeFailsClosedBeforeAnyJobSideEffect() {
        for (input in listOf("wake", "resume", "force", "root", "none")) {
            assertTrue(input, runCatching {
                LocalAgentMessageDeliveryMode.fromWire(input)
            }.isFailure)
        }
    }
}

package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.session.LocalSessionEventLog
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalTeamMessageReceiptTest {
    private val id = "team-msg-abc123"
    private val sessionId = "session-team"
    private val memberId = "member-abc"
    private val jobId = "job-team-abc"

    private fun event(seq: Long, kind: String, data: kotlinx.serialization.json.JsonObject) =
        LocalSessionEventLog.Event(sequence = seq, type = kind, createdAt = seq, data = data)

    private fun queued(seq: Long) = event(seq, LocalAgentTeamContract.TEAM_MESSAGE_QUEUED,
        buildJsonObject {
            put("version", 2)
            put("teamId", sessionId)
            put("message", buildJsonObject {
                put("id", id)
                put("targetId", memberId)
                put("senderId", sessionId)
            })
        })

    private fun delivered(seq: Long) = event(seq, LocalAgentTeamContract.TEAM_MESSAGE_DELIVERED,
        buildJsonObject {
            put("version", 2)
            put("teamId", sessionId)
            put("messageId", id)
            put("targetId", memberId)
        })

    private fun discarded(seq: Long) = event(seq, LocalAgentTeamContract.TEAM_MESSAGE_DISCARDED,
        buildJsonObject {
            put("version", 2)
            put("teamId", sessionId)
            put("messageId", id)
            put("targetId", memberId)
        })

    private fun checkpoint(seq: Long, job: String) = event(
        seq, LOCAL_SUBAGENT_HISTORY_CHECKPOINT_EVENT, buildJsonObject {
            put("background_job_id", job)
            put("claimed_message_ids", JsonArray(listOf(JsonPrimitive(id))))
        },
    )

    private fun modelReply(seq: Long, job: String) = event(
        seq, LOCAL_SUBAGENT_INBOX_MODEL_REPLY_EVENT, buildJsonObject {
            put("background_job_id", job)
            put("message_ids", JsonArray(listOf(JsonPrimitive(id))))
            put("step", 1)
        },
    )

    private fun acknowledged(
        seq: Long,
        member: String = memberId,
        outcome: String = "handled",
        reason: String = "",
    ) = event(seq, LocalAgentTeamContract.TEAM_MESSAGE_ACKNOWLEDGED, buildJsonObject {
        put("version", 2)
        put("teamId", sessionId)
        put("messageId", id)
        put("memberId", member)
        put("outcome", outcome)
        put("reason", reason)
    })

    @Test fun acknowledgedMeansReportedHandlingNotVerifiedTaskCompletion() {
        val received = listOf(queued(1), delivered(2), checkpoint(3, jobId), modelReply(4, jobId))
        val handled = projectLocalTeamMessageReceipt(id, received + acknowledged(5), true, sessionId)
        assertEquals("member_handled_reported", handled.status)
        assertEquals(5L, handled.memberAcknowledgedSequence)
        assertEquals("handled", handled.memberAcknowledgedOutcome)
        assertTrue(handled.render().contains("never independent proof of verified task completion"))
        val declined = projectLocalTeamMessageReceipt(id,
            received + acknowledged(5, outcome = "cannot_handle", reason = "缺少授权"), true, sessionId)
        assertEquals("member_cannot_handle_reported", declined.status)
        assertEquals("缺少授权", declined.memberAcknowledgedReason)
    }

    @Test fun forgedPrematureOrCrossMemberAckCannotUpgradeReceipt() {
        val evidence = listOf(queued(1), delivered(2), checkpoint(3, jobId), modelReply(4, jobId))
        val cross = projectLocalTeamMessageReceipt(id,
            evidence + acknowledged(5, member = "member-attacker"), true, sessionId)
        assertEquals("model_reply_observed", cross.status)
        val premature = projectLocalTeamMessageReceipt(id,
            listOf(queued(1), delivered(2), acknowledged(3),
                checkpoint(4, jobId), modelReply(5, jobId)), true, sessionId)
        assertEquals("model_reply_observed", premature.status)
        val missingHistory = projectLocalTeamMessageReceipt(id,
            listOf(queued(1), delivered(2), acknowledged(3)), true, sessionId)
        assertEquals("accepted_by_inbox", missingHistory.status)
        val incomplete = projectLocalTeamMessageReceipt(id,
            evidence + acknowledged(5), false, sessionId)
        assertEquals("unknown_history_window", incomplete.status)
    }

    @Test fun replyAfterDurableHistoryIsAnObservedModelTurnNotVerifiedTaskCompletion() {
        val receipt = projectLocalTeamMessageReceipt(
            id,
            listOf(queued(1), delivered(2), checkpoint(3, jobId), modelReply(4, jobId)),
            true, sessionId,
        )
        assertEquals("model_reply_observed", receipt.status)
        assertEquals(4L, receipt.modelReplySequence)
        assertTrue(receipt.render().contains("does not prove comprehension"))
        assertFalse(receipt.render().contains("completion_status=completed"))
    }

    @Test fun outOfOrderReplyCannotClaimModelProcessingBeforeClaimCheckpoint() {
        val preCheckpointReply = projectLocalTeamMessageReceipt(
            id, listOf(queued(1), delivered(2), modelReply(3, jobId),
                checkpoint(4, jobId)), true, sessionId,
        )
        assertEquals("checkpointed_in_model_history", preCheckpointReply.status)
        assertEquals(null, preCheckpointReply.modelReplySequence)
        val unrecordedDelivery = projectLocalTeamMessageReceipt(
            id, listOf(queued(1), checkpoint(2, jobId), modelReply(3, jobId)),
            true, sessionId,
        )
        assertEquals("checkpointed_in_model_history", unrecordedDelivery.status)
        assertEquals(null, unrecordedDelivery.modelReplySequence)
    }

    @Test fun wrongRecipientAndPrematureDeliveryNeverBecomeInboxAcceptance() {
        val foreignTargetDelivery = event(2, LocalAgentTeamContract.TEAM_MESSAGE_DELIVERED,
            buildJsonObject {
                put("version", 2); put("teamId", sessionId)
                put("messageId", id); put("targetId", "member-foreign")
            })
        val wrong = projectLocalTeamMessageReceipt(
            id, listOf(queued(1), foreignTargetDelivery), true, sessionId,
        )
        assertEquals("queued", wrong.status)
        assertEquals(null, wrong.deliveredSequence)
        val earlier = projectLocalTeamMessageReceipt(
            id, listOf(delivered(1), queued(2), checkpoint(3, jobId),
                modelReply(4, jobId)), true, sessionId,
        )
        assertEquals("checkpointed_in_model_history", earlier.status)
        assertEquals(null, earlier.modelReplySequence)
    }

    @Test fun unaffiliatedReplyOrMissingHistoryCannotUpgradeDelivery() {
        val wrongJob = projectLocalTeamMessageReceipt(
            id,
            listOf(queued(1), delivered(2), checkpoint(3, jobId),
                modelReply(4, "job-team-other")), true, sessionId,
        )
        assertEquals("checkpointed_in_model_history", wrongJob.status)
        assertEquals(null, wrongJob.modelReplySequence)
        val noDurableHistory = projectLocalTeamMessageReceipt(
            id, listOf(queued(1), delivered(2), modelReply(3, jobId)), true, sessionId,
        )
        assertEquals("accepted_by_inbox", noDurableHistory.status)
        val incomplete = projectLocalTeamMessageReceipt(
            id, listOf(queued(1), checkpoint(2, jobId), modelReply(3, jobId)),
            false, sessionId,
        )
        assertEquals("unknown_history_window", incomplete.status)
    }

    @Test fun statusMustDistinguishQueueInboxAcceptanceAndDurableHistory() {
        val q = projectLocalTeamMessageReceipt(id, listOf(queued(1)), true, sessionId)
        assertEquals("queued", q.status)
        val d = projectLocalTeamMessageReceipt(id, listOf(queued(1), delivered(2)), true, sessionId)
        assertEquals("accepted_by_inbox", d.status)
        assertFalse(d.render().contains("status=checkpointed_in_model_history"))
        val c = projectLocalTeamMessageReceipt(id, listOf(queued(1), delivered(2),
            checkpoint(3, jobId)), true, sessionId)
        assertEquals("checkpointed_in_model_history", c.status)
        assertEquals(3L, c.historyCheckpointSequence)
        assertTrue(c.render().contains("does not prove comprehension"))
    }

    @Test fun differentJobCannotForgeMessageConsumption() {
        val c = projectLocalTeamMessageReceipt(id,
            listOf(queued(1), delivered(2), checkpoint(3, "job-team-attacker")),
            true, sessionId)
        assertEquals("accepted_by_inbox", c.status)
        assertEquals(null, c.historyCheckpointSequence)
    }

    @Test fun eventOrderAndDiscardAreProjectedWithoutMutatingTheLedger() {
        val receipt = projectLocalTeamMessageReceipt(id,
            listOf(discarded(4), queued(1)), true, sessionId)
        assertEquals("discarded", receipt.status)
        assertEquals(4L, receipt.discardedSequence)
    }

    @Test fun incompleteHistoryNeverClaimsUnseenDeliveryOrMessageAbsence() {
        val partial = projectLocalTeamMessageReceipt(id,
            listOf(delivered(2), checkpoint(3, jobId)), false, sessionId)
        assertEquals("unknown_history_window", partial.status)
        assertFalse(partial.historyWindowComplete)
        val observedButIncomplete = projectLocalTeamMessageReceipt(id,
            listOf(queued(1), delivered(2)), false, sessionId)
        assertEquals("unknown_history_window", observedButIncomplete.status)
        assertEquals(2L, observedButIncomplete.deliveredSequence)
        val absent = projectLocalTeamMessageReceipt(id, emptyList(), true, sessionId)
        assertEquals("not_found", absent.status)
    }

    @Test fun identifierAndSessionBoundariesRejectForeignMessages() {
        assertTrue(runCatching {
            projectLocalTeamMessageReceipt("../escape", emptyList(), true, sessionId)
        }.isFailure)
        val foreign = projectLocalTeamMessageReceipt(id, listOf(queued(1)), true, "other-session")
        assertEquals("not_found", foreign.status)
    }
}

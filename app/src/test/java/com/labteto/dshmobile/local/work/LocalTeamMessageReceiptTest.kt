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
        assertTrue(c.render().contains("does not establish provider consumption"))
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

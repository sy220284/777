package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.session.LocalSessionEventLog
import com.labteto.dshmobile.harness.agent.QueuedAgentInput
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalSubagentInboxRecoveryScanTest {
    private fun event(index: Long) = LocalSessionEventLog.Event(
        sequence = index, type = "unrelated/event", createdAt = 0L, data = buildJsonObject {},
    )

    @Test
    fun longEventHistoryCrossesFormer64PageCapWithoutDiscardingInputs() {
        var pages = 0
        val result = recoverSubagentInboxClaims(
            backgroundJobId = "job-1", checkpointSequence = 0L, scanThrough = 14_000L,
            alreadyClaimed = emptySet(), pendingIds = emptySet(),
            pageAfter = { cursor ->
                pages++
                ((cursor + 1L)..minOf(cursor + 200L, 14_000L)).map(::event)
            },
        )
        assertTrue(result.isEmpty())
        assertEquals(70, pages)
    }

    @Test
    fun preservesClaimedInboxMessagesAcrossLongArchiveWithoutDuplicatingPendingIds() {
        val wanted = QueuedAgentInput(id = "msg-keep", content = "新的用户限制")
        val pending = QueuedAgentInput(id = "msg-pending", content = "已在队列")
        val encoded = encodeLocalSubagentInboxClaimEvent(
            agentId = "agent", backgroundJobId = "job-1",
            messages = listOf(wanted, pending),
        )
        var pages = 0
        val result = recoverSubagentInboxClaims(
            backgroundJobId = "job-1",
            checkpointSequence = 0L, scanThrough = 13_001L,
            alreadyClaimed = emptySet(), pendingIds = setOf("msg-pending"),
            pageAfter = { cursor ->
                pages++
                ((cursor + 1L)..minOf(cursor + 200L, 13_001L)).map { index ->
                    if (index == 13_001L) LocalSessionEventLog.Event(
                        sequence = index, type = LOCAL_SUBAGENT_INBOX_CLAIM_EVENT,
                        createdAt = 0L, data = encoded,
                    ) else event(index)
                }
            },
        )
        assertEquals(listOf("msg-keep"), result.map { it.id })
        assertEquals("新的用户限制", result.single().content)
        assertTrue(pages > 64)
    }

    @Test
    fun damagedMiddleSegmentDoesNotSilentlySkipClaimedMessages() {
        val failed = runCatching {
            recoverSubagentInboxClaims(
                backgroundJobId = "job", checkpointSequence = 10L, scanThrough = 15L,
                alreadyClaimed = emptySet(), pendingIds = emptySet(),
                pageAfter = { cursor ->
                    if (cursor == 10L) listOf(event(11L), event(13L), event(14L), event(15L))
                    else emptyList()
                },
            )
        }.exceptionOrNull()
        assertTrue(failed is IllegalStateException)
        assertTrue(failed?.message.orEmpty().contains("SEQUENCE_GAP"))
    }

    @Test
    fun missingArchivedPageFailsClosedRatherThanSkippingUserInputs() {
        val failure = runCatching {
            recoverSubagentInboxClaims(
                backgroundJobId = "job", checkpointSequence = 0L, scanThrough = 2L,
                alreadyClaimed = emptySet(), pendingIds = emptySet(),
                pageAfter = { emptyList() },
            )
        }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(failure?.message.orEmpty().contains("HISTORY_GAP"))
    }

    @Test
    fun malformedMatchingClaimFailsClosedInsteadOfLosingAdmittedUserInput() {
        val valid = encodeLocalSubagentInboxClaimEvent(
            agentId = "agent", backgroundJobId = "job",
            messages = listOf(QueuedAgentInput(id = "accepted", content = "保留这条输入")),
        )
        val invalidVersion = JsonObject(valid + ("version" to JsonPrimitive(99)))
        val incompleteMessages = JsonObject(valid + ("messages" to JsonArray(listOf(
            buildJsonObject { put("id", "accepted"); put("content", "") },
        ))))
        for (payload in listOf(invalidVersion, incompleteMessages)) {
            val failure = runCatching {
                recoverSubagentInboxClaims(
                    backgroundJobId = "job", checkpointSequence = 0L, scanThrough = 1L,
                    alreadyClaimed = emptySet(), pendingIds = emptySet(),
                    pageAfter = { listOf(LocalSessionEventLog.Event(
                        sequence = 1L, type = LOCAL_SUBAGENT_INBOX_CLAIM_EVENT,
                        createdAt = 0L, data = payload,
                    )) },
                )
            }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
            assertTrue(failure?.message.orEmpty().contains("INVALID_CLAIM"))
        }
    }

    @Test
    fun malformedClaimForOtherJobAndLegitimateEmptyClaimDoNotBlockRecovery() {
        val unrelated = LocalSessionEventLog.Event(
            sequence = 1L, type = LOCAL_SUBAGENT_INBOX_CLAIM_EVENT,
            createdAt = 0L, data = buildJsonObject { put("background_job_id", "another-job") },
        )
        val empty = LocalSessionEventLog.Event(
            sequence = 2L, type = LOCAL_SUBAGENT_INBOX_CLAIM_EVENT,
            createdAt = 0L, data = encodeLocalSubagentInboxClaimEvent("agent", "job", emptyList()),
        )
        val result = recoverSubagentInboxClaims(
            backgroundJobId = "job", checkpointSequence = 0L, scanThrough = 2L,
            alreadyClaimed = emptySet(), pendingIds = emptySet(),
            pageAfter = { listOf(unrelated, empty) },
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun nonAdvancingPagesCannotSpinForever() {
        val failure = runCatching {
            recoverSubagentInboxClaims(
                backgroundJobId = "job", checkpointSequence = 5L, scanThrough = 8L,
                alreadyClaimed = emptySet(), pendingIds = emptySet(),
                pageAfter = { listOf(event(5L)) },
            )
        }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(failure?.message.orEmpty().contains("NO_PROGRESS"))
    }
}

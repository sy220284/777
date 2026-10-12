package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.session.LocalSessionEventLog
import kotlinx.serialization.json.buildJsonObject
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

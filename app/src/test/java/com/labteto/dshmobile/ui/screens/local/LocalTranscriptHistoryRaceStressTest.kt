package com.labteto.dshmobile.ui.screens.local

import com.labteto.dshmobile.local.session.LocalHarnessMessage
import com.labteto.dshmobile.local.session.LocalTranscriptPageCursor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercise the real paging state owner while controlling the completion order of its IO source. */
class LocalTranscriptHistoryRaceStressTest {
    private data class Bootstrap(
        val id: String,
        val reply: CompletableDeferred<LocalTranscriptHistoryLoadResult?> = CompletableDeferred(),
    )

    private data class Older(
        val id: String,
        val cursor: LocalTranscriptPageCursor,
        val reply: CompletableDeferred<LocalTranscriptHistoryLoadResult?> = CompletableDeferred(),
    )

    private class ControlledSource : LocalTranscriptHistoryPageSource {
        val bootstraps = Channel<Bootstrap>(Channel.UNLIMITED)
        val older = Channel<Older>(Channel.UNLIMITED)
        override suspend fun bootstrap(sessionId: String): LocalTranscriptHistoryLoadResult? {
            val call = Bootstrap(sessionId)
            bootstraps.send(call)
            return call.reply.await()
        }
        override suspend fun olderBatch(
            sessionId: String,
            olderMessages: List<LocalHarnessMessage>,
            startCursor: LocalTranscriptPageCursor,
        ): LocalTranscriptHistoryLoadResult? {
            val call = Older(sessionId, startCursor)
            older.send(call)
            return call.reply.await()
        }
    }

    private fun page(id: String, remaining: Long?): LocalTranscriptHistoryLoadResult =
        LocalTranscriptHistoryLoadResult(
            olderMessages = listOf(LocalHarnessMessage("message-$id", "user", id, createdAt = 1L)),
            nextCursor = remaining?.let { LocalTranscriptPageCursor(beforeSequenceExclusive = it) },
            loadedRawMessages = 1,
        )

    @Test fun lateBootstrapCannotReplaceNewerSessionOrAnABAReturn() = runBlocking {
        var selected = "A"
        val source = ControlledSource()
        val owner = LocalTranscriptHistoryController(source, { selected }, this)
        val oldA = async(start = CoroutineStart.UNDISPATCHED) { owner.prepare("A") }
        val first = source.bootstraps.receive()
        selected = "B"
        val jobB = async(start = CoroutineStart.UNDISPATCHED) { owner.prepare("B") }
        val second = source.bootstraps.receive()
        second.reply.complete(page("B", 200))
        jobB.await()

        selected = "A"
        val newA = async(start = CoroutineStart.UNDISPATCHED) { owner.prepare("A") }
        val third = source.bootstraps.receive()
        first.reply.complete(page("STALE-A", 100))
        oldA.await()
        assertEquals("A", owner.history.value.sessionId)
        assertTrue(owner.history.value.loading)
        third.reply.complete(page("A", 300))
        newA.await()
        assertEquals("message-A", owner.history.value.olderMessages.single().id)
        assertTrue(owner.history.value.hasMore)
        assertFalse(owner.history.value.loading)
    }

    @Test fun lateOlderPageCannotPoisonNewSessionsPaginationCursor() = runBlocking {
        var selected = "A"
        val source = ControlledSource()
        val owner = LocalTranscriptHistoryController(source, { selected }, this)

        val initial = async(start = CoroutineStart.UNDISPATCHED) { owner.prepare("A") }
        source.bootstraps.receive().reply.complete(page("A", 100))
        initial.await()
        val oldOlder = async(start = CoroutineStart.UNDISPATCHED) { owner.loadOlder("A") }
        val oldCall = source.older.receive()
        assertEquals(100L, oldCall.cursor.beforeSequenceExclusive)

        selected = "B"
        val b = async(start = CoroutineStart.UNDISPATCHED) { owner.prepare("B") }
        source.bootstraps.receive().reply.complete(page("B", 200))
        b.await()
        selected = "A"
        val newA = async(start = CoroutineStart.UNDISPATCHED) { owner.prepare("A") }
        source.bootstraps.receive().reply.complete(page("A-NEW", 300))
        newA.await()

        oldCall.reply.complete(page("STALE", 101))
        oldOlder.await()
        assertEquals("message-A-NEW", owner.history.value.olderMessages.single().id)

        val currentOlder = async(start = CoroutineStart.UNDISPATCHED) { owner.loadOlder("A") }
        val currentCall = source.older.receive()
        assertEquals(300L, currentCall.cursor.beforeSequenceExclusive)
        currentCall.reply.complete(page("A-OLDER", null))
        currentOlder.await()
        assertEquals("message-A-OLDER", owner.history.value.olderMessages.single().id)
        assertFalse(owner.history.value.hasMore)
        assertFalse(owner.history.value.loading)
    }

    @Test fun thousandsOfRapidSwitchesAndReverseCompletionsKeepOnlyFinalIdentity() = runBlocking {
        var selected = "session-0"
        val source = ControlledSource()
        val owner = LocalTranscriptHistoryController(source, { selected }, this)
        val pending = mutableListOf<Pair<kotlinx.coroutines.Deferred<Unit>, Bootstrap>>()
        repeat(2_000) { n ->
            selected = "session-$n"
            val job = async(start = CoroutineStart.UNDISPATCHED) { owner.prepare(selected) }
            pending += job to source.bootstraps.receive()
        }
        val last = pending.last()
        last.second.reply.complete(page("final", null))
        last.first.await()
        pending.dropLast(1).asReversed().forEach { (job, call) ->
            call.reply.complete(page("obsolete", 1))
            job.await()
        }
        assertEquals("session-1999", owner.history.value.sessionId)
        assertEquals("message-final", owner.history.value.olderMessages.single().id)
        assertFalse(owner.history.value.loading)
        assertFalse(owner.history.value.hasMore)
    }
}

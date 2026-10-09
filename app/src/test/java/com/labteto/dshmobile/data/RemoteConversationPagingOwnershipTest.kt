package com.labteto.dshmobile.data

import com.labteto.dshmobile.core.session.ConversationSnapshot
import com.labteto.dshmobile.core.wire.DshApiClient
import com.labteto.dshmobile.core.wire.RpcHttpResponse
import com.labteto.dshmobile.core.wire.RpcTransport
import com.labteto.dshmobile.core.wire.dto.SessionFollowFrame
import java.io.InputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteConversationPagingOwnershipTest {
    private class GateTransport(private val failed: Boolean) : RpcTransport {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        val secondRelease = CompletableDeferred<Unit>()
        private val calls = AtomicInteger()
        override suspend fun post(path: String, body: String): RpcHttpResponse {
            if (calls.incrementAndGet() == 1) {
                started.complete(Unit)
                release.await()
            } else {
                secondStarted.complete(Unit)
                secondRelease.await()
            }
            val id = Json.parseToJsonElement(body).jsonObject.getValue("rpcId")
            val result = if (failed) """{"ok":false,"error":{"code":"old","message":"old failure"}}"""
                else """{"ok":true,"value":{"records":[],"hasMore":false}}"""
            return RpcHttpResponse(200, """{"type":"server-response","rpcId":$id,"result":$result}""")
        }
        override suspend fun <T> download(path: String, consume: (String?, String?, InputStream) -> T): T = error("unused")
        override suspend fun upload(path: String, contentType: String, contentLength: Long,
            body: InputStream, onProgress: ((Long) -> Unit)?): RpcHttpResponse = error("unused")
    }

    private class Fixture(scope: CoroutineScope, val gate: GateTransport) {
        var session = "A"
        var connection: Any = Any()
        var recovered = 0
        val loading = MutableStateFlow(false)
        val failed = MutableStateFlow(false)
        val conversation = MutableStateFlow<ConversationSnapshot?>(null)
        private val api = DshApiClient(gate)
        val runtime = RemoteConversationRuntime(scope, Any(), { session }, { false }, { api }, { _, _ -> true },
            conversation, loading, failed, { _, _ -> }, { recovered++ }, {}, { connection })
        fun install(cursor: Int = 20) {
            runtime.reset(blank = false, clearPublished = true)
            runtime.handleFollowFrame(session, SessionFollowFrame.Snapshot(cursor = cursor, records = emptyList(), hasMore = true))
        }
    }

    @Test fun returningToSameSessionCannotAcceptOldPageOrClearNewLoading() = runTest {
        val f = Fixture(backgroundScope, GateTransport(false))
        f.install()
        val old = async { f.runtime.loadOlder() }
        f.gate.started.await()
        f.session = "B"; f.install()
        f.session = "A"; f.install(30)
        val newRequest = async { f.runtime.loadOlder() }
        f.gate.secondStarted.await()
        f.gate.release.complete(Unit)
        old.await()
        assertTrue(f.loading.value)
        assertEquals(3, f.recovered)
        f.gate.secondRelease.complete(Unit)
        newRequest.await()
        assertEquals(4, f.recovered) // Three installed baselines plus the new page only.
        assertFalse(f.loading.value)
        assertFalse(f.failed.value)
    }

    @Test fun oldErrorDoesNotMarkNewSessionAsFailed() = runTest {
        val f = Fixture(backgroundScope, GateTransport(true))
        f.install()
        val old = async { f.runtime.loadOlder() }
        f.gate.started.await()
        f.session = "B"; f.install()
        f.gate.release.complete(Unit); old.await()
        assertFalse(f.failed.value)
        assertEquals("B", f.conversation.value?.sessionId)
        assertEquals(2, f.recovered)
    }

    @Test fun freshBaselineForSameSessionRejectsOldHasMoreAndFeedback() = runTest {
        val f = Fixture(backgroundScope, GateTransport(false))
        f.install()
        val old = async { f.runtime.loadOlder() }
        f.gate.started.await()
        f.runtime.handleFollowFrame("A", SessionFollowFrame.Snapshot(cursor = 40, records = emptyList(), hasMore = true))
        f.gate.release.complete(Unit); old.await()
        assertTrue(f.conversation.value!!.hasMore)
        assertEquals(2, f.recovered)
    }

    @Test fun hostChangeRejectsPageEvenWhenSessionIdIsUnchanged() = runTest {
        val f = Fixture(backgroundScope, GateTransport(false))
        f.install()
        val old = async { f.runtime.loadOlder() }
        f.gate.started.await()
        f.connection = Any()
        f.runtime.reset(blank = true, clearPublished = true)
        f.gate.release.complete(Unit); old.await()
        assertEquals(null, f.conversation.value)
        assertFalse(f.loading.value)
        assertEquals(1, f.recovered)
    }

    @Test fun staleFailureCannotClearLoadingOfNewPageAfterReturningToSameSession() = runTest {
        val f = Fixture(backgroundScope, GateTransport(true))
        f.install()
        val old = async { f.runtime.loadOlder() }
        f.gate.started.await()
        f.session = "B"; f.install()
        f.session = "A"; f.install(30)
        val current = async { f.runtime.loadOlder() }
        f.gate.secondStarted.await()
        f.gate.release.complete(Unit)
        old.await()
        assertTrue(f.loading.value)
        assertFalse(f.failed.value)
        assertEquals("A", f.conversation.value?.sessionId)
        f.gate.secondRelease.complete(Unit)
        current.await()
        assertFalse(f.loading.value)
        assertTrue(f.failed.value)
    }
}

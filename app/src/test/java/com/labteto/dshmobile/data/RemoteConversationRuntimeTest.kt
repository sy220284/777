package com.labteto.dshmobile.data

import com.labteto.dshmobile.core.session.ConversationSnapshot
import com.labteto.dshmobile.core.session.SessionEventEnvelope
import com.labteto.dshmobile.core.wire.dto.SessionFollowFrame
import com.labteto.dshmobile.core.wire.dto.SessionHistoryRecord
import com.labteto.dshmobile.core.wire.dto.SessionWireEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteConversationRuntimeTest {
    @Test
    fun followSnapshotAndLiveEntryStayBoundToCurrentSession() = runTest {
        val lock = Any()
        var current = "s1"
        val conversation = MutableStateFlow<ConversationSnapshot?>(null)
        val loading = MutableStateFlow(false)
        val failed = MutableStateFlow(false)
        val followed = mutableListOf<Pair<String, Int>>()
        val durable = mutableListOf<Pair<String, SessionEventEnvelope>>()

        val runtime = RemoteConversationRuntime(
            scope = backgroundScope,
            lock = lock,
            currentSessionId = { current },
            runningForSession = { false },
            apiProvider = { null },
            followSession = { sessionId, maxMessages ->
                followed += sessionId to maxMessages
                true
            },
            conversation = conversation,
            loadingOlder = loading,
            loadOlderFailed = failed,
            onDurableEvent = { sessionId, event -> durable += sessionId to event },
            onConnectionRecovered = {},
            logger = {},
        )

        runtime.reset(blank = true, clearPublished = true)
        assertNull(conversation.value)
        runtime.startFollow("s1")
        assertEquals(listOf("s1" to 60), followed)

        runtime.handleFollowFrame(
            "s1",
            SessionFollowFrame.Snapshot(
                cursor = 0,
                records = emptyList(),
                hasMore = false,
            ),
        )
        assertNotNull(conversation.value)
        assertEquals("s1", conversation.value?.sessionId)

        runtime.handleFollowFrame(
            "s1",
            SessionFollowFrame.Entry(
                SessionHistoryRecord.Event(
                    event = SessionWireEvent(
                        type = "turn/start",
                        seq = 1,
                        time = 1L,
                        data = JsonObject(emptyMap()),
                    ),
                ),
            ),
        )
        runCurrent()
        assertEquals(1, durable.size)
        assertEquals("s1", durable.single().first)
        assertTrue(conversation.value?.journal?.any { it.type == "turn/start" } == true)

        current = "s2"
        runtime.handleFollowFrame(
            "s1",
            SessionFollowFrame.Snapshot(
                cursor = 1,
                records = emptyList(),
                hasMore = false,
            ),
        )
        assertEquals("s1", conversation.value?.sessionId)
    }
}

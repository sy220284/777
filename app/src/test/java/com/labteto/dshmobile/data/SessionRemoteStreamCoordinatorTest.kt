package com.labteto.dshmobile.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionRemoteStreamCoordinatorTest {
    @Test
    fun switchingSessionCancelsPreviousFollowAndBuildsBoundedRequest() = runTest {
        val opened = mutableListOf<Pair<String, JsonElement>>()
        val cancelled = mutableListOf<String>()
        var followIndex = 0
        val coordinator = coordinator(
            scope = backgroundScope,
            streamProvider = { endpoint, args ->
                opened += endpoint to args
                if (endpoint == "session/follow") {
                    val label = "follow-${++followIndex}"
                    trackedFlow(label, cancelled)
                } else {
                    trackedFlow(endpoint, cancelled)
                }
            },
        )

        assertTrue(coordinator.followSession("s1", 200))
        runCurrent()
        assertTrue(coordinator.followSession("s2", 200))
        runCurrent()

        assertTrue("follow-1" in cancelled)
        val followCalls = opened.filter { it.first == "session/follow" }
        assertEquals(2, followCalls.size)
        val request = followCalls.last().second.jsonObject.getValue("request").jsonObject
        val address = request.getValue("address").jsonObject
        assertEquals("session", address.getValue("kind").jsonPrimitive.content)
        assertEquals("s2", address.getValue("sessionId").jsonPrimitive.content)
        assertEquals(200, request.getValue("maxMessages").jsonPrimitive.content.toInt())
        assertTrue(request.getValue("assistantStream").jsonPrimitive.boolean)
    }

    @Test
    fun switchingSubagentCancelsPreviousChildFollowAndKeepsOwnershipAddress() = runTest {
        val opened = mutableListOf<Pair<String, JsonElement>>()
        val cancelled = mutableListOf<String>()
        var childIndex = 0
        val coordinator = coordinator(
            scope = backgroundScope,
            streamProvider = { endpoint, args ->
                opened += endpoint to args
                trackedFlow("child-${++childIndex}", cancelled)
            },
        )

        assertTrue(
            coordinator.followSubagent(
                parentSessionId = "parent",
                childSessionId = "child-1",
                mode = "continuable",
                maxMessages = 200,
                onFrame = {},
            ),
        )
        runCurrent()
        assertTrue(
            coordinator.followSubagent(
                parentSessionId = "parent",
                childSessionId = "child-2",
                mode = "one-shot",
                maxMessages = 200,
                onFrame = {},
            ),
        )
        runCurrent()

        assertTrue("child-1" in cancelled)
        val request = opened.last().second.jsonObject.getValue("request").jsonObject
        val address = request.getValue("address").jsonObject
        assertEquals("subagent", address.getValue("kind").jsonPrimitive.content)
        assertEquals("parent", address.getValue("parentSessionId").jsonPrimitive.content)
        assertEquals("child-2", address.getValue("childSessionId").jsonPrimitive.content)
        assertEquals("one-shot", address.getValue("mode").jsonPrimitive.content)
        assertTrue(request.getValue("assistantStream").jsonPrimitive.boolean)
    }

    @Test
    fun restartingHostStreamsCancelsPreviousGenerationCollectors() = runTest {
        val cancelled = mutableListOf<String>()
        val opened = mutableListOf<String>()
        val coordinator = coordinator(
            scope = backgroundScope,
            streamProvider = { endpoint, _ ->
                opened += endpoint
                trackedFlow("$endpoint#${opened.count { it == endpoint }}", cancelled)
            },
        )

        coordinator.restartHostStreams()
        runCurrent()
        coordinator.restartHostStreams()
        runCurrent()

        assertEquals(
            listOf("session/control", "workspace/follow", "session/control", "workspace/follow"),
            opened,
        )
        assertTrue("session/control#1" in cancelled)
        assertTrue("workspace/follow#1" in cancelled)
    }

    private fun coordinator(
        scope: CoroutineScope,
        streamProvider: (String, JsonElement) -> Flow<JsonElement>?,
    ) = SessionRemoteStreamCoordinator(
        scope = scope,
        streamProvider = streamProvider,
        onControlFrame = {},
        onWorkspaceFrame = {},
        onFollowFrame = { _, _ -> },
        onFailure = {},
    )

    private fun trackedFlow(
        label: String,
        cancelled: MutableList<String>,
    ): Flow<JsonElement> = flow {
        try {
            awaitCancellation()
        } finally {
            cancelled += label
        }
    }
}

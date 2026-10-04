package com.labteto.dshmobile.data

import com.labteto.dshmobile.core.wire.DshApiClient
import com.labteto.dshmobile.core.wire.RpcHttpResponse
import com.labteto.dshmobile.core.wire.RpcTransport
import java.io.InputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionAsyncStaleResultTest {
    private class GateTransport(
        private val result: String,
    ) : RpcTransport {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        override suspend fun post(path: String, body: String): RpcHttpResponse {
            started.complete(Unit)
            release.await()
            val rpcId = Json.parseToJsonElement(body).jsonObject.getValue("rpcId")
            return RpcHttpResponse(
                200,
                """{"type":"server-response","rpcId":$rpcId,"result":$result}""",
            )
        }

        override suspend fun <T> download(
            path: String,
            consume: (String?, String?, InputStream) -> T,
        ): T = error("unused")

        override suspend fun upload(
            path: String,
            contentType: String,
            contentLength: Long,
            body: InputStream,
            onProgress: ((Long) -> Unit)?,
        ): RpcHttpResponse = error("unused")
    }

    @Test
    fun olderLandingWriteCannotWinAfterNewerSessionIsOpened() = runTest {
        var currentSession = "A"
        var currentHost = "host"
        val writes = mutableListOf<String>()
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val runtime = SessionLandingRuntime(
            activeHostKey = { currentHost },
            currentSessionId = { currentSession },
            persist = { _, sessionId ->
                if (sessionId == "A") {
                    firstStarted.complete(Unit)
                    releaseFirst.await()
                }
                writes += sessionId
            },
            logger = { _, _ -> },
        )

        val first = launch { runtime.remember("A") }
        firstStarted.await()
        currentSession = "B"
        val second = launch { runtime.remember("B") }
        releaseFirst.complete(Unit)
        joinAll(first, second)

        assertEquals(listOf("A", "B"), writes)
        assertEquals("B", writes.last())
    }

    @Test
    fun staleSessionErrorsDoNotPolluteNewSessionCatalogState() = runTest {
        val commandTransport = GateTransport(
            """{"ok":false,"error":{"code":"stale","message":"old command failure"}}""",
        )
        val skillTransport = GateTransport(
            """{"ok":false,"error":{"code":"stale","message":"old skill failure"}}""",
        )
        var currentSession = "A"
        var activeApi = DshApiClient(commandTransport)
        val errors = mutableListOf<String>()
        val runtime = SessionCatalogRuntime(
            apiForHost = { key -> if (key == "host") activeApi else null },
            activeHostKey = { "host" },
            currentSessionId = { currentSession },
            onConnectionError = { errors += it },
            logger = {},
        )

        val command = async { runtime.refreshCommands("A") }
        commandTransport.started.await()
        currentSession = "B"
        commandTransport.release.complete(Unit)
        command.await()
        assertTrue(runtime.commandsAvailable.value)

        currentSession = "A"
        runtime.resetSession()
        activeApi = DshApiClient(skillTransport)
        val skill = async { runtime.loadSkills("A") }
        skillTransport.started.await()
        currentSession = "B"
        skillTransport.release.complete(Unit)
        skill.await()

        assertTrue(errors.isEmpty())
        assertTrue(runtime.skillsLoading.value)
    }

    @Test
    fun staleHostCatalogSuccessCannotOverwriteNewHost() = runTest {
        val transport = GateTransport("""{"ok":true,"value":{"entries":[]}}""")
        val api = DshApiClient(transport)
        var host = "host-A"
        val runtime = SessionCatalogRuntime(
            apiForHost = { key -> if (key == "host-A") api else null },
            activeHostKey = { host },
            currentSessionId = { "session" },
            onConnectionError = {},
            logger = {},
        )

        val request = async { runtime.refreshPlugins() }
        transport.started.await()
        host = "host-B"
        transport.release.complete(Unit)
        request.await()

        assertNull(runtime.plugins.value)
    }

    @Test
    fun staleSubagentRefreshFailureDoesNotSurfaceOnNewSession() = runTest {
        val transport = GateTransport(
            """{"ok":false,"error":{"code":"stale","message":"old subagent failure"}}""",
        )
        val api = DshApiClient(transport)
        var session = "A"
        val errors = mutableListOf<String?>()
        val remoteStreams = SessionRemoteStreamCoordinator(
            scope = this,
            streamProvider = { _: String, _: JsonElement -> null as Flow<JsonElement>? },
            onControlFrame = {},
            onWorkspaceFrame = {},
            onFollowFrame = { _, _ -> },
            onFailure = {},
        )
        val runtime = SessionSubagentRuntime(
            apiForHost = { key -> if (key == "host") api else null },
            currentSessionId = { session },
            activeHostKey = { "host" },
            remoteStreams = remoteStreams,
            onConnectionError = { errors += it },
            logger = { _, _ -> },
        )

        val request = async { runtime.refresh() }
        transport.started.await()
        session = "B"
        transport.release.complete(Unit)
        request.await()

        assertTrue(errors.isEmpty())
    }
}

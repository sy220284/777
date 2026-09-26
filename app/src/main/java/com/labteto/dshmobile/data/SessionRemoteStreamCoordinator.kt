package com.labteto.dshmobile.data

import com.labteto.dshmobile.core.wire.decodeFromJsonElement
import com.labteto.dshmobile.core.wire.dto.SessionAddress
import com.labteto.dshmobile.core.wire.dto.SessionControlFrame
import com.labteto.dshmobile.core.wire.dto.SessionControlFrameSerializer
import com.labteto.dshmobile.core.wire.dto.SessionFollowFrame
import com.labteto.dshmobile.core.wire.dto.SessionFollowFrameSerializer
import com.labteto.dshmobile.core.wire.dto.SessionFollowRequest
import com.labteto.dshmobile.core.wire.dto.WorkspaceFollowFrame
import com.labteto.dshmobile.core.wire.dto.WorkspaceFollowFrameSerializer
import com.labteto.dshmobile.core.wire.encodeToJsonElement
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal data class SessionRemoteStreamFailure(
    val endpoint: String,
    val sessionId: String? = null,
    val error: Throwable? = null,
    val undecodable: Boolean = false,
)

/**
 * Owns only the lifetime of SessionStore's remote streams.
 *
 * State folding and product semantics stay in SessionStore. This coordinator cancels stale streams,
 * opens the streams that belong to the current connection/session, decodes their frame unions and
 * forwards frames to the existing store handlers.
 */
internal class SessionRemoteStreamCoordinator(
    private val scope: CoroutineScope,
    private val streamProvider: (endpoint: String, args: JsonElement) -> Flow<JsonElement>?,
    private val onControlFrame: (SessionControlFrame) -> Unit,
    private val onWorkspaceFrame: (WorkspaceFollowFrame) -> Unit,
    private val onFollowFrame: (sessionId: String, frame: SessionFollowFrame) -> Unit,
    private val onFailure: (SessionRemoteStreamFailure) -> Unit,
) {
    private var controlJob: Job? = null
    private var workspaceJob: Job? = null
    private var followJob: Job? = null

    fun restartHostStreams() {
        controlJob?.cancel()
        workspaceJob?.cancel()

        streamProvider("session/control", JsonObject(emptyMap()))?.let { flow ->
            controlJob = collectDecoded(
                endpoint = "session/control",
                flow = flow,
                serializer = SessionControlFrameSerializer,
                onFrame = onControlFrame,
            )
        }

        streamProvider("workspace/follow", JsonObject(emptyMap()))?.let { flow ->
            workspaceJob = collectDecoded(
                endpoint = "workspace/follow",
                flow = flow,
                serializer = WorkspaceFollowFrameSerializer,
                onFrame = onWorkspaceFrame,
            )
        }
    }

    fun followSession(sessionId: String, maxMessages: Int): Boolean {
        followJob?.cancel()
        val flow = streamProvider(
            "session/follow",
            sessionFollowStreamArgs(sessionId, maxMessages),
        ) ?: return false
        followJob = scope.launch {
            try {
                flow.collect { item ->
                    val frame = decodeOrNull(SessionFollowFrameSerializer, item)
                    if (frame == null) {
                        onFailure(
                            SessionRemoteStreamFailure(
                                endpoint = "session/follow",
                                sessionId = sessionId,
                                undecodable = true,
                            ),
                        )
                    } else {
                        onFollowFrame(sessionId, frame)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                onFailure(
                    SessionRemoteStreamFailure(
                        endpoint = "session/follow",
                        sessionId = sessionId,
                        error = error,
                    ),
                )
            }
        }
        return true
    }

    fun cancelSessionFollow() {
        followJob?.cancel()
        followJob = null
    }

    private fun <T> collectDecoded(
        endpoint: String,
        flow: Flow<JsonElement>,
        serializer: KSerializer<T>,
        onFrame: (T) -> Unit,
    ): Job = scope.launch {
        try {
            flow.collect { item ->
                decodeOrNull(serializer, item)?.let(onFrame)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            onFailure(SessionRemoteStreamFailure(endpoint = endpoint, error = error))
        }
    }

    private fun <T> decodeOrNull(serializer: KSerializer<T>, item: JsonElement): T? =
        runCatching { decodeFromJsonElement(serializer, item) }.getOrNull()
}

internal fun sessionFollowStreamArgs(
    sessionId: String,
    maxMessages: Int,
): JsonObject = buildJsonObject {
    put(
        "request",
        encodeToJsonElement(
            SessionFollowRequest.serializer(),
            SessionFollowRequest(
                address = SessionAddress.Session(sessionId = sessionId),
                maxMessages = maxMessages,
                assistantStream = true,
            ),
        ),
    )
}

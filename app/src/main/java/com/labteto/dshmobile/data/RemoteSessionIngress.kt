package com.labteto.dshmobile.data

import com.labteto.dshmobile.core.wire.dto.RemoteEventFrame
import com.labteto.dshmobile.core.wire.dto.SessionControlFrame
import com.labteto.dshmobile.core.wire.dto.SessionFollowFrame
import com.labteto.dshmobile.core.wire.dto.WorkspaceFollowFrame
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Normalizes every Remote Session input channel before state is mutated.
 *
 * Decoding stays at the edge; SessionStore remains the facade and applies these mutations through
 * one path. This prevents host notifications, control frames, workspace frames and follow frames
 * from growing independent write paths as the protocol evolves.
 */
internal class RemoteSessionIngress(
    private val emit: (RemoteSessionMutation) -> Unit,
    private val logger: (String) -> Unit,
) {
    fun acceptHostFrame(frame: RemoteEventFrame) {
        when (frame) {
            is RemoteEventFrame.Emit -> acceptNotification(frame.event, frame.args)
            is RemoteEventFrame.Waterfall -> emit(RemoteSessionMutation.Waterfall(frame))
            is RemoteEventFrame.Cancel -> emit(RemoteSessionMutation.WaterfallCancelled(frame.eventId))
            is RemoteEventFrame.Ready -> Unit
            is RemoteEventFrame.Unknown -> logger("unknown host event frame " + frame.type)
        }
    }

    fun acceptControlFrame(frame: SessionControlFrame) {
        emit(RemoteSessionMutation.Control(frame))
    }

    fun acceptWorkspaceFrame(frame: WorkspaceFollowFrame) {
        emit(RemoteSessionMutation.Workspace(frame))
    }

    fun acceptFollowFrame(sessionId: String, frame: SessionFollowFrame) {
        emit(RemoteSessionMutation.Follow(sessionId, frame))
    }

    private fun acceptNotification(event: String, args: List<JsonElement>) {
        fun stringAt(index: Int): String? = args.getOrNull(index)?.jsonPrimitive?.contentOrNull

        when (event) {
            "api-session/added" ->
                args.firstOrNull()?.let { emit(RemoteSessionMutation.SessionAdded(it)) }
            "api-session/removed" ->
                stringAt(0)?.let { emit(RemoteSessionMutation.SessionRemoved(it)) }
            "api-session/status" -> {
                val sessionId = stringAt(0) ?: return
                val running = args.getOrNull(1)?.jsonPrimitive?.booleanOrNull ?: false
                emit(RemoteSessionMutation.RunningChanged(sessionId, running))
            }
            "api-session/activity" -> {
                val sessionId = stringAt(0) ?: return
                val updatedAt = args.getOrNull(1)?.jsonPrimitive?.longOrNull ?: return
                emit(RemoteSessionMutation.ActivityChanged(sessionId, updatedAt))
            }
            "api-session/error" -> emit(RemoteSessionMutation.ConnectionError(stringAt(1)))
            "permission-presets/catalog-changed" -> emit(RemoteSessionMutation.PermissionCatalogChanged)
            "commands/change" -> emit(RemoteSessionMutation.CommandsChanged)
            "agent-preset/selected" -> emit(RemoteSessionMutation.AgentPresetSelected)
            else -> Unit
        }
    }
}

internal sealed interface RemoteSessionMutation {
    data class SessionAdded(val summary: JsonElement) : RemoteSessionMutation
    data class SessionRemoved(val sessionId: String) : RemoteSessionMutation
    data class RunningChanged(val sessionId: String, val running: Boolean) : RemoteSessionMutation
    data class ActivityChanged(val sessionId: String, val updatedAt: Long) : RemoteSessionMutation
    data class ConnectionError(val message: String?) : RemoteSessionMutation
    data object PermissionCatalogChanged : RemoteSessionMutation
    data object CommandsChanged : RemoteSessionMutation
    data object AgentPresetSelected : RemoteSessionMutation
    data class Waterfall(val frame: RemoteEventFrame.Waterfall) : RemoteSessionMutation
    data class WaterfallCancelled(val eventId: String) : RemoteSessionMutation
    data class Control(val frame: SessionControlFrame) : RemoteSessionMutation
    data class Workspace(val frame: WorkspaceFollowFrame) : RemoteSessionMutation
    data class Follow(val sessionId: String, val frame: SessionFollowFrame) : RemoteSessionMutation
}

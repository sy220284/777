package com.labteto.dshmobile.data

import com.labteto.dshmobile.core.wire.DshApiClient
import com.labteto.dshmobile.core.wire.RpcResult
import com.labteto.dshmobile.core.wire.dto.ATTACHMENT_INVALID
import com.labteto.dshmobile.core.wire.dto.CUSTOM_PRESET
import com.labteto.dshmobile.core.wire.dto.CommandSubmitAttachment
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Owns slash-command execution and permission-preset mutation semantics. */
internal class SessionSlashCommandRuntime(
    private val apiForHost: (String?) -> DshApiClient?,
    private val currentSessionId: () -> String?,
    private val activeHostKey: () -> String?,
    private val markCommandsUnavailable: (String, String) -> Unit,
    private val installPermission: (String, String) -> Unit,
    private val clearPermission: (String, String) -> Unit,
    private val onConnectionError: (String?) -> Unit,
) {
    suspend fun run(
        line: String,
        attachments: List<CommandSubmitAttachment> = emptyList(),
        targetSessionId: String? = currentSessionId(),
        targetHost: String? = activeHostKey(),
    ): CommandOutcome {
        val sessionId = targetSessionId ?: return CommandOutcome.Failed("no open session")
        val api = apiForHost(targetHost) ?: return CommandOutcome.Failed("not connected")
        return when (val result = api.commandsExecute(sessionId, line, attachments)) {
            is RpcResult.Ok -> {
                val execution = result.value as? JsonObject
                val commandId = execution?.get("commandId")
                if (commandId == null || commandId is JsonNull) {
                    CommandOutcome.Unknown(line)
                } else {
                    val commandResult = execution["result"] as? JsonObject
                    val text = (commandResult?.get("text") as? JsonPrimitive)?.contentOrNull
                    if ((commandResult?.get("kind") as? JsonPrimitive)?.contentOrNull == "error") {
                        CommandOutcome.Failed(text ?: "command failed")
                    } else {
                        CommandOutcome.Ok(text)
                    }
                }
            }
            is RpcResult.Err -> when (result.error.code) {
                ATTACHMENT_INVALID -> CommandOutcome.Failed(result.error.message)
                "capability-unavailable", "forbidden" -> {
                    markCommandsUnavailable(result.error.code, result.error.message)
                    CommandOutcome.Failed(result.error.message)
                }
                else -> {
                    onConnectionError(result.error.message)
                    CommandOutcome.Failed(result.error.message)
                }
            }
        }
    }

    suspend fun setPermissionPreset(value: String): CommandOutcome {
        if (value == CUSTOM_PRESET) {
            return CommandOutcome.Failed("`$CUSTOM_PRESET` is a derived state, not a preset")
        }
        val sessionId = currentSessionId() ?: return CommandOutcome.Failed("no open session")
        val host = activeHostKey()
        installPermission(sessionId, value)
        val outcome = run("/permission $value", targetSessionId = sessionId, targetHost = host)
        if (outcome !is CommandOutcome.Ok) clearPermission(sessionId, value)
        return outcome
    }
}

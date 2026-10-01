package com.labteto.dshmobile.data

import com.labteto.dshmobile.core.wire.DshApiClient
import com.labteto.dshmobile.core.wire.RpcResult
import com.labteto.dshmobile.core.wire.dto.GoalRef
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Owns goal mutation RPCs while SessionStore remains the projection/state owner. */
internal class SessionGoalRuntime(
    private val apiProvider: () -> DshApiClient?,
    private val currentSessionId: () -> String?,
    private val currentGoalRef: () -> GoalRef?,
    private val onConnectionError: (String?) -> Unit,
    private val logger: (String) -> Unit,
) {
    suspend fun act(action: String, objective: String? = null) {
        val sessionId = currentSessionId() ?: return
        val api = apiProvider() ?: return
        when (action) {
            "create" -> {
                val value = objective
                if (value.isNullOrBlank()) {
                    logger("goal create requires an objective")
                    return
                }
                handle(api.goalCreate(sessionId, buildJsonObject {
                    put("objective", JsonPrimitive(value))
                }))
            }
            "edit", "pause", "resume", "complete", "clear" -> {
                val ref = currentGoalRef()
                if (ref == null) {
                    logger("goal $action requires a current goal (no goal projection)")
                    return
                }
                when (action) {
                    "edit" -> handle(
                        api.goalEdit(
                            sessionId,
                            ref,
                            buildJsonObject {
                                objective?.let { put("objective", JsonPrimitive(it)) }
                            },
                        ),
                    )
                    "pause" -> handle(api.goalPause(sessionId, ref))
                    "resume" -> handle(api.goalResume(sessionId, ref))
                    "complete" -> handle(api.goalComplete(sessionId, ref))
                    "clear" -> handle(api.goalClear(sessionId, ref))
                }
            }
            else -> logger("unknown goal action $action")
        }
    }

    private fun <T> handle(result: RpcResult<T>) {
        if (result is RpcResult.Err) onConnectionError(result.error.message)
    }
}

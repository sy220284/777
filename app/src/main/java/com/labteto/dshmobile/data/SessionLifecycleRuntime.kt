package com.labteto.dshmobile.data

import com.labteto.dshmobile.core.wire.DshApiClient
import com.labteto.dshmobile.core.wire.RpcResult
import com.labteto.dshmobile.core.wire.dto.SessionCreateRequest
import com.labteto.dshmobile.core.wire.dto.SessionForkRequest
import com.labteto.dshmobile.core.wire.dto.SessionRenameRequest

/** Owns session create/rename/fork RPCs while SessionStore owns list and open-session state. */
internal class SessionLifecycleRuntime(
    private val apiProvider: () -> DshApiClient?,
    private val reusableBlankSession: (String) -> String?,
    private val refreshSessions: suspend () -> Unit,
    private val openSession: suspend (String) -> Unit,
    private val onTitleChanged: (String, String) -> Unit,
    private val onConnectionError: (String?) -> Unit,
) {
    suspend fun create(cwd: String? = null, workspaceId: String? = null) {
        if (workspaceId != null) {
            reusableBlankSession(workspaceId)?.let { reusable ->
                openSession(reusable)
                return
            }
        }
        val api = apiProvider() ?: return
        when (val result = api.sessionCreate(SessionCreateRequest(workspaceId = workspaceId, cwd = cwd))) {
            is RpcResult.Ok -> {
                refreshSessions()
                openSession(result.value.sessionId)
            }
            is RpcResult.Err -> onConnectionError(result.error.message)
        }
    }

    suspend fun rename(sessionId: String, title: String) {
        val api = apiProvider() ?: return
        when (val result = api.sessionRename(SessionRenameRequest(sessionId, title))) {
            is RpcResult.Ok -> onTitleChanged(sessionId, result.value.title)
            is RpcResult.Err -> onConnectionError(result.error.message)
        }
    }

    suspend fun fork(sessionId: String, atSeq: Long? = null) {
        val api = apiProvider() ?: return
        when (val result = api.sessionFork(SessionForkRequest(sessionId, atSeq?.toInt()))) {
            is RpcResult.Ok -> refreshSessions()
            is RpcResult.Err -> onConnectionError(result.error.message)
        }
    }
}

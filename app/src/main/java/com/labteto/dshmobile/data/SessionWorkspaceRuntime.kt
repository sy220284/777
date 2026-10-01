package com.labteto.dshmobile.data

import com.labteto.dshmobile.core.wire.DshApiClient
import com.labteto.dshmobile.core.wire.RpcResult
import com.labteto.dshmobile.core.wire.dto.WorkspaceArchiveSessionRequest
import com.labteto.dshmobile.core.wire.dto.WorkspaceCreateRequest
import com.labteto.dshmobile.core.wire.dto.WorkspaceDeleteRequest
import com.labteto.dshmobile.core.wire.dto.WorkspaceRenameRequest
import com.labteto.dshmobile.core.wire.dto.WorkspaceView

/** Owns workspace mutation RPCs while SessionStore remains the single workspace state owner. */
internal class SessionWorkspaceRuntime(
    private val apiProvider: () -> DshApiClient?,
    private val apiForHost: (String?) -> DshApiClient?,
    private val activeHostKey: () -> String?,
    private val onWorkspaceUpsert: (WorkspaceView) -> Unit,
    private val onWorkspaceRemove: (String) -> Unit,
    private val onArchivedChanged: (List<String>) -> Unit,
    private val refreshSessions: suspend () -> Unit,
    private val onConnectionError: (String?) -> Unit,
) {
    suspend fun create(path: String) {
        val api = apiProvider() ?: return
        when (val result = api.workspaceCreate(WorkspaceCreateRequest(path))) {
            is RpcResult.Ok -> onWorkspaceUpsert(result.value.workspace)
            is RpcResult.Err -> onConnectionError(result.error.message)
        }
    }

    suspend fun rename(id: String, title: String) {
        val api = apiProvider() ?: return
        when (val result = api.workspaceRename(WorkspaceRenameRequest(id, title))) {
            is RpcResult.Ok -> onWorkspaceUpsert(result.value.workspace)
            is RpcResult.Err -> onConnectionError(result.error.message)
        }
    }

    suspend fun delete(id: String) {
        val api = apiProvider() ?: return
        when (val result = api.workspaceDelete(WorkspaceDeleteRequest(id))) {
            is RpcResult.Ok -> onWorkspaceRemove(id)
            is RpcResult.Err -> onConnectionError(result.error.message)
        }
    }

    suspend fun archiveSession(sessionId: String) {
        val api = apiProvider() ?: return
        when (val result = api.workspaceArchiveSession(WorkspaceArchiveSessionRequest(sessionId))) {
            is RpcResult.Ok -> {
                onArchivedChanged(result.value.archivedSessionIds)
                refreshSessions()
            }
            is RpcResult.Err -> onConnectionError(result.error.message)
        }
    }

    suspend fun unarchiveSession(sessionId: String): Boolean {
        val key = activeHostKey()
        val result = apiForHost(key)?.workspaceUnarchiveSession(sessionId) ?: return false
        if (key != activeHostKey()) return false
        return when (result) {
            is RpcResult.Ok -> {
                onArchivedChanged(result.value.archivedSessionIds)
                refreshSessions()
                true
            }
            is RpcResult.Err -> {
                onConnectionError(result.error.message)
                false
            }
        }
    }
}

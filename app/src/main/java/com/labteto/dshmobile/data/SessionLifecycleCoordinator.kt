package com.labteto.dshmobile.data

import com.labteto.dshmobile.core.wire.RpcResult
import com.labteto.dshmobile.core.wire.dto.SessionCreateRequest
import com.labteto.dshmobile.core.wire.dto.SessionCreateValue
import com.labteto.dshmobile.core.wire.dto.SessionForkRequest
import com.labteto.dshmobile.core.wire.dto.SessionForkValue
import com.labteto.dshmobile.core.wire.dto.SessionRenameRequest
import com.labteto.dshmobile.core.wire.dto.SessionRenameValue
import com.labteto.dshmobile.core.wire.dto.WorkspaceArchiveSessionRequest
import com.labteto.dshmobile.core.wire.dto.WorkspaceArchiveValue

/**
 * Owns session lifecycle RPC orchestration while SessionStore remains the public state facade.
 *
 * Remote calls are injected as narrow functions so this coordinator has no connection lifetime
 * knowledge and is independently testable. State mutations still flow through SessionStore
 * callbacks, preserving the existing single state source.
 */
internal class SessionLifecycleCoordinator(
    private val createRemote:
        suspend (SessionCreateRequest) -> RpcResult<SessionCreateValue>?,
    private val renameRemote:
        suspend (SessionRenameRequest) -> RpcResult<SessionRenameValue>?,
    private val forkRemote:
        suspend (SessionForkRequest) -> RpcResult<SessionForkValue>?,
    private val archiveRemote:
        suspend (WorkspaceArchiveSessionRequest) -> RpcResult<WorkspaceArchiveValue>?,
    private val unarchiveRemote:
        suspend (hostKey: String?, sessionId: String) -> RpcResult<WorkspaceArchiveValue>?,
    private val activeHostKey: () -> String?,
    private val reusableBlankSession: (workspaceId: String) -> String?,
    private val openSession: suspend (sessionId: String) -> Unit,
    private val refreshSessions: suspend () -> Unit,
    private val setTitle: (sessionId: String, title: String) -> Unit,
    private val setArchived: (List<String>) -> Unit,
    private val setError: (String) -> Unit,
) {
    suspend fun create(cwd: String?, workspaceId: String?) {
        if (workspaceId != null) {
            reusableBlankSession(workspaceId)?.let { reusable ->
                openSession(reusable)
                return
            }
        }

        when (val result = createRemote(SessionCreateRequest(workspaceId = workspaceId, cwd = cwd)) ?: return) {
            is RpcResult.Ok -> {
                refreshSessions()
                openSession(result.value.sessionId)
            }
            is RpcResult.Err -> setError(result.error.message)
        }
    }

    suspend fun rename(sessionId: String, title: String) {
        when (val result = renameRemote(SessionRenameRequest(sessionId, title)) ?: return) {
            is RpcResult.Ok -> setTitle(sessionId, result.value.title)
            is RpcResult.Err -> setError(result.error.message)
        }
    }

    suspend fun fork(sessionId: String, atSeq: Long?) {
        when (
            val result = forkRemote(
                SessionForkRequest(
                    sessionId = sessionId,
                    atSeq = atSeq?.toInt(),
                ),
            ) ?: return
        ) {
            is RpcResult.Ok -> refreshSessions()
            is RpcResult.Err -> setError(result.error.message)
        }
    }

    suspend fun archive(sessionId: String) {
        when (
            val result = archiveRemote(
                WorkspaceArchiveSessionRequest(sessionId),
            ) ?: return
        ) {
            is RpcResult.Ok -> {
                setArchived(result.value.archivedSessionIds)
                refreshSessions()
            }
            is RpcResult.Err -> setError(result.error.message)
        }
    }

    suspend fun unarchive(sessionId: String): Boolean {
        val hostKey = activeHostKey()
        val result = unarchiveRemote(hostKey, sessionId) ?: return false
        if (hostKey != activeHostKey()) return false
        return when (result) {
            is RpcResult.Ok -> {
                setArchived(result.value.archivedSessionIds)
                refreshSessions()
                true
            }
            is RpcResult.Err -> {
                setError(result.error.message)
                false
            }
        }
    }
}

package com.labteto.dshmobile.ui.screens.local

import com.labteto.dshmobile.local.session.LocalSessionRuntime
import com.labteto.dshmobile.local.session.LocalTranscriptPageCursor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Owns transcript-history paging state for the local conversation UI. */
internal class LocalTranscriptHistoryController(
    session: LocalSessionRuntime,
    private val currentSessionId: () -> String,
    liveMessages: () -> List<com.labteto.dshmobile.local.session.LocalHarnessMessage>,
    private val scope: CoroutineScope,
) {
    private val loader = LocalTranscriptHistoryLoader(session, currentSessionId, liveMessages)
    private val _history = MutableStateFlow(LocalTranscriptHistoryState())
    val history: StateFlow<LocalTranscriptHistoryState> = _history.asStateFlow()

    private var cursor: LocalTranscriptPageCursor? = null
    private var initializedSessionId: String? = null

    suspend fun prepare(sessionId: String, force: Boolean = false) {
        if (sessionId.isBlank()) {
            reset()
            return
        }
        val current = _history.value
        if (!force && current.sessionId == sessionId && initializedSessionId == sessionId) return

        cursor = null
        initializedSessionId = null
        _history.value = LocalTranscriptHistoryState(sessionId = sessionId, loading = true)
        try {
            val loaded = loader.bootstrap(sessionId) ?: return
            cursor = loaded.nextCursor
            initializedSessionId = sessionId
            _history.value = LocalTranscriptHistoryState(
                sessionId = sessionId,
                olderMessages = loaded.olderMessages,
                hasMore = loaded.nextCursor != null,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (currentSessionId() != sessionId) return
            cursor = null
            initializedSessionId = null
            _history.value = LocalTranscriptHistoryState(
                sessionId = sessionId,
                error = error.message ?: error::class.java.simpleName,
            )
        }
    }

    suspend fun loadOlder(sessionId: String): Result<Int> {
        if (sessionId.isBlank()) return Result.success(0)
        if (initializedSessionId != sessionId || _history.value.sessionId != sessionId) {
            prepare(sessionId)
        }

        val startCursor = cursor ?: return Result.success(0)
        val current = _history.value
        if (current.loading) return Result.success(0)
        _history.value = current.copy(loading = true, error = null)

        return try {
            val loaded = loader.olderBatch(
                sessionId = sessionId,
                olderMessages = current.olderMessages,
                startCursor = startCursor,
            ) ?: return Result.success(0)
            cursor = loaded.nextCursor
            val latest = _history.value
            if (latest.sessionId != sessionId) return Result.success(0)
            _history.value = latest.copy(
                olderMessages = loaded.olderMessages,
                hasMore = loaded.nextCursor != null,
                loading = false,
                error = null,
            )
            Result.success(loaded.loadedRawMessages)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (_history.value.sessionId == sessionId) {
                _history.value = _history.value.copy(
                    loading = false,
                    error = error.message ?: error::class.java.simpleName,
                )
            }
            Result.failure(error)
        }
    }

    fun refreshAfterTimelineRewrite() {
        val sessionId = currentSessionId()
        cursor = null
        initializedSessionId = null
        _history.value = LocalTranscriptHistoryState(sessionId = sessionId)
        scope.launch { prepare(sessionId, force = true) }
    }

    private fun reset() {
        cursor = null
        initializedSessionId = null
        _history.value = LocalTranscriptHistoryState()
    }
}

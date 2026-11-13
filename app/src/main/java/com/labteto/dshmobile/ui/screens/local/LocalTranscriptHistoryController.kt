package com.labteto.dshmobile.ui.screens.local

import com.labteto.dshmobile.local.presentation.LocalSessionUiFacade
import com.labteto.dshmobile.local.session.LocalHarnessMessage
import com.labteto.dshmobile.local.session.LocalTranscriptPageCursor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Single owner for the visible transcript cursor, including late paging completions. */
internal class LocalTranscriptHistoryController(
    private val loader: LocalTranscriptHistoryPageSource,
    private val currentSessionId: () -> String,
    private val scope: CoroutineScope,
) {
    constructor(
        session: LocalSessionUiFacade,
        currentSessionId: () -> String,
        liveMessages: () -> List<LocalHarnessMessage>,
        scope: CoroutineScope,
    ) : this(LocalTranscriptHistoryLoader(session, currentSessionId, liveMessages), currentSessionId, scope)

    private val _history = MutableStateFlow(LocalTranscriptHistoryState())
    val history: StateFlow<LocalTranscriptHistoryState> = _history.asStateFlow()

    private var cursor: LocalTranscriptPageCursor? = null
    private var initializedSessionId: String? = null
    // Session identity alone cannot reject ABA: A -> B -> A with an old A read still running.
    private var generation = 0L

    private fun isCurrent(sessionId: String, requestGeneration: Long): Boolean =
        generation == requestGeneration &&
            currentSessionId() == sessionId &&
            _history.value.sessionId == sessionId

    suspend fun prepare(sessionId: String, force: Boolean = false) {
        if (sessionId.isBlank()) {
            reset()
            return
        }
        if (currentSessionId() != sessionId) return
        val current = _history.value
        if (!force && current.sessionId == sessionId && initializedSessionId == sessionId) return

        val requestGeneration = ++generation
        cursor = null
        initializedSessionId = null
        _history.value = LocalTranscriptHistoryState(sessionId = sessionId, loading = true)
        try {
            val loaded = loader.bootstrap(sessionId)
            if (!isCurrent(sessionId, requestGeneration)) return
            if (loaded == null) {
                _history.value = _history.value.copy(loading = false, error = "会话历史加载未完成")
                return
            }
            cursor = loaded.nextCursor
            initializedSessionId = sessionId
            _history.value = LocalTranscriptHistoryState(
                sessionId = sessionId,
                olderMessages = loaded.olderMessages,
                hasMore = loaded.nextCursor != null,
            )
        } catch (cancelled: CancellationException) {
            // A cancelled bootstrap must not leave an apparently endless spinner.
            if (isCurrent(sessionId, requestGeneration)) {
                _history.value = _history.value.copy(
                    loading = false,
                    error = "会话历史加载已取消，请重试",
                )
            }
            throw cancelled
        } catch (error: Exception) {
            if (!isCurrent(sessionId, requestGeneration)) return
            cursor = null
            initializedSessionId = null
            _history.value = LocalTranscriptHistoryState(
                sessionId = sessionId,
                error = error.message ?: error::class.java.simpleName,
            )
        }
    }

    suspend fun loadOlder(sessionId: String): Result<Int> {
        if (sessionId.isBlank() || currentSessionId() != sessionId) return Result.success(0)
        if (initializedSessionId != sessionId || _history.value.sessionId != sessionId) {
            prepare(sessionId)
        }
        if (currentSessionId() != sessionId || initializedSessionId != sessionId) {
            return Result.success(0)
        }
        val startCursor = cursor ?: return Result.success(0)
        val current = _history.value
        if (current.loading || current.sessionId != sessionId) return Result.success(0)
        val requestGeneration = generation
        _history.value = current.copy(loading = true, error = null)

        return try {
            val loaded = loader.olderBatch(
                sessionId = sessionId,
                olderMessages = current.olderMessages,
                startCursor = startCursor,
            )
            if (!isCurrent(sessionId, requestGeneration)) return Result.success(0)
            if (loaded == null) {
                _history.value = _history.value.copy(loading = false, error = "会话历史分页未完成")
                return Result.success(0)
            }
            // Commit cursor and visible page only AFTER the same generation check.
            cursor = loaded.nextCursor
            initializedSessionId = sessionId
            _history.value = _history.value.copy(
                olderMessages = loaded.olderMessages,
                hasMore = loaded.nextCursor != null,
                loading = false,
                error = null,
            )
            Result.success(loaded.loadedRawMessages)
        } catch (cancelled: CancellationException) {
            // Cancellation can come from a disappearing UI collector while the
            // Session owner remains alive. Keep its cursor and allow a new drag.
            if (isCurrent(sessionId, requestGeneration)) {
                _history.value = _history.value.copy(loading = false)
            }
            throw cancelled
        } catch (error: Exception) {
            if (isCurrent(sessionId, requestGeneration)) {
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
        generation++
        cursor = null
        initializedSessionId = null
        _history.value = LocalTranscriptHistoryState(sessionId = sessionId)
        scope.launch { prepare(sessionId, force = true) }
    }

    private fun reset() {
        generation++
        cursor = null
        initializedSessionId = null
        _history.value = LocalTranscriptHistoryState()
    }
}

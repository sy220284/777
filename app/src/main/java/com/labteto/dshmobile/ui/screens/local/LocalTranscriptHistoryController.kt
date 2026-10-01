package com.labteto.dshmobile.ui.screens.local

import com.labteto.dshmobile.local.LocalHarnessMessage
import com.labteto.dshmobile.local.LocalTranscriptPageCursor
import com.labteto.dshmobile.local.session.LocalSessionRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Owns transcript-history paging state for the local conversation UI. */
internal class LocalTranscriptHistoryController(
    private val session: LocalSessionRuntime,
    private val currentSessionId: () -> String,
    private val liveMessages: () -> List<LocalHarnessMessage>,
    private val scope: CoroutineScope,
) {
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
            val liveMessagesAtBootstrap =
                if (currentSessionId() == sessionId) liveMessages() else emptyList()
            val firstPage = withContext(Dispatchers.IO) {
                session.transcriptPageForUi(
                    sessionId = sessionId,
                    cursor = null,
                    limit = transcriptHistoryBootstrapLimit(
                        liveMessageCount = liveMessagesAtBootstrap.size,
                        maxPageSize = LOCAL_TRANSCRIPT_HISTORY_PAGE_MESSAGES,
                    ),
                )
            }
            if (currentSessionId() != sessionId) return
            val pageExtras = transcriptHistoryPageExtras(
                pageMessages = firstPage.messages,
                liveMessages = liveMessages(),
            )
            cursor = firstPage.nextCursor
            initializedSessionId = sessionId
            _history.value = LocalTranscriptHistoryState(
                sessionId = sessionId,
                olderMessages = pageExtras,
                hasMore = firstPage.nextCursor != null,
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
        if (
            cursor == null &&
            _history.value.olderMessages.isEmpty() &&
            currentSessionId() == sessionId &&
            liveMessages().size > LOCAL_TRANSCRIPT_HISTORY_PAGE_MESSAGES
        ) {
            prepare(sessionId, force = true)
        }

        val nextCursor = cursor ?: return Result.success(0)
        val current = _history.value
        if (current.loading) return Result.success(0)
        _history.value = current.copy(loading = true, error = null)

        return try {
            val page = withContext(Dispatchers.IO) {
                session.transcriptPageForUi(
                    sessionId = sessionId,
                    cursor = nextCursor,
                    limit = LOCAL_TRANSCRIPT_HISTORY_PAGE_MESSAGES,
                )
            }
            if (currentSessionId() != sessionId) return Result.success(0)
            val latest = _history.value
            if (latest.sessionId != sessionId) return Result.success(0)
            val existingIds = latest.olderMessages.mapTo(hashSetOf(), LocalHarnessMessage::id)
            liveMessages().mapTo(existingIds, LocalHarnessMessage::id)
            val newlyLoaded = page.messages.filterNot { message -> message.id in existingIds }
            cursor = page.nextCursor
            _history.value = latest.copy(
                olderMessages = newlyLoaded + latest.olderMessages,
                hasMore = page.nextCursor != null,
                loading = false,
                error = null,
            )
            Result.success(newlyLoaded.size)
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

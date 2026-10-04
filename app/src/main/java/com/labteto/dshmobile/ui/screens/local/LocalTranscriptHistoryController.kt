package com.labteto.dshmobile.ui.screens.local

import com.labteto.dshmobile.local.LocalHarnessMessage
import com.labteto.dshmobile.local.LocalTranscriptPage
import com.labteto.dshmobile.local.LocalTranscriptPageCursor
import com.labteto.dshmobile.local.session.LocalSessionRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
            var olderMessages = emptyList<LocalHarnessMessage>()
            var pageCursor: LocalTranscriptPageCursor? = null
            var nextCursor: LocalTranscriptPageCursor?

            do {
                val page = readPage(sessionId, pageCursor)
                if (currentSessionId() != sessionId) return

                val live = liveMessages()
                olderMessages = prependUniqueOlderMessages(
                    pageMessages = page.messages,
                    olderMessages = olderMessages,
                    liveMessages = live,
                )
                nextCursor = page.nextCursor

                val visibleTranscript = mergeLocalTranscriptHistory(olderMessages, live)
                val needsMoreVisibleDialogue = needsMoreUserVisibleDialogue(
                    messages = visibleTranscript,
                    target = LOCAL_TRANSCRIPT_INITIAL_VISIBLE_DIALOGUE_MESSAGES,
                )
                val cursorAdvanced = nextCursor != pageCursor
                pageCursor = nextCursor
            } while (nextCursor != null && needsMoreVisibleDialogue && cursorAdvanced)

            cursor = nextCursor
            initializedSessionId = sessionId
            _history.value = LocalTranscriptHistoryState(
                sessionId = sessionId,
                olderMessages = olderMessages,
                hasMore = nextCursor != null,
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

        var nextCursor = cursor ?: return Result.success(0)
        val current = _history.value
        if (current.loading) return Result.success(0)
        _history.value = current.copy(loading = true, error = null)

        return try {
            var olderMessages = current.olderMessages
            var loadedRawMessages = 0
            val initialVisibleCount = userVisibleDialogueMessageCount(
                mergeLocalTranscriptHistory(olderMessages, liveMessages()),
            )

            do {
                val requestedCursor = nextCursor
                val page = readPage(sessionId, requestedCursor)
                if (currentSessionId() != sessionId) return Result.success(0)

                val beforeSize = olderMessages.size
                olderMessages = prependUniqueOlderMessages(
                    pageMessages = page.messages,
                    olderMessages = olderMessages,
                    liveMessages = liveMessages(),
                )
                loadedRawMessages += olderMessages.size - beforeSize
                nextCursor = page.nextCursor

                val visibleCount = userVisibleDialogueMessageCount(
                    mergeLocalTranscriptHistory(olderMessages, liveMessages()),
                )
                val addedVisibleDialogue = (visibleCount - initialVisibleCount).coerceAtLeast(0)
                val cursorAdvanced = nextCursor != requestedCursor
            } while (
                nextCursor != null &&
                addedVisibleDialogue < LOCAL_TRANSCRIPT_HISTORY_VISIBLE_DIALOGUE_BATCH_MESSAGES &&
                cursorAdvanced
            )

            cursor = nextCursor
            val latest = _history.value
            if (latest.sessionId != sessionId) return Result.success(0)
            _history.value = latest.copy(
                olderMessages = olderMessages,
                hasMore = nextCursor != null,
                loading = false,
                error = null,
            )
            Result.success(loadedRawMessages)
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

    private suspend fun readPage(
        sessionId: String,
        pageCursor: LocalTranscriptPageCursor?,
    ): LocalTranscriptPage = withContext(Dispatchers.IO) {
        session.transcriptPageForUi(
            sessionId = sessionId,
            cursor = pageCursor,
            limit = LOCAL_TRANSCRIPT_HISTORY_PAGE_MESSAGES,
        )
    }

    private fun prependUniqueOlderMessages(
        pageMessages: List<LocalHarnessMessage>,
        olderMessages: List<LocalHarnessMessage>,
        liveMessages: List<LocalHarnessMessage>,
    ): List<LocalHarnessMessage> {
        if (pageMessages.isEmpty()) return olderMessages
        val existingIds = HashSet<String>(olderMessages.size + liveMessages.size)
        olderMessages.mapTo(existingIds, LocalHarnessMessage::id)
        liveMessages.mapTo(existingIds, LocalHarnessMessage::id)
        val newlyLoaded = pageMessages.filter { message -> existingIds.add(message.id) }
        return if (newlyLoaded.isEmpty()) olderMessages else newlyLoaded + olderMessages
    }

    private fun reset() {
        cursor = null
        initializedSessionId = null
        _history.value = LocalTranscriptHistoryState()
    }
}

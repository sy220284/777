package com.labteto.dshmobile.ui.screens.local

import com.labteto.dshmobile.local.LocalHarnessMessage
import com.labteto.dshmobile.local.LocalTranscriptPage
import com.labteto.dshmobile.local.LocalTranscriptPageCursor
import com.labteto.dshmobile.local.session.LocalSessionRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class LocalTranscriptHistoryLoadResult(
    val olderMessages: List<LocalHarnessMessage>,
    val nextCursor: LocalTranscriptPageCursor?,
    val loadedRawMessages: Int = 0,
)

/**
 * Reads event-backed transcript pages until a foreground-visible dialogue target is satisfied.
 *
 * Raw runtime/history paging remains bounded by [LOCAL_TRANSCRIPT_HISTORY_PAGE_MESSAGES]. This
 * loader only decides how many bounded pages are needed to satisfy the separate foreground quota.
 */
internal class LocalTranscriptHistoryLoader(
    private val session: LocalSessionRuntime,
    private val currentSessionId: () -> String,
    private val liveMessages: () -> List<LocalHarnessMessage>,
) {
    suspend fun bootstrap(sessionId: String): LocalTranscriptHistoryLoadResult? {
        var olderMessages = emptyList<LocalHarnessMessage>()
        var pageCursor: LocalTranscriptPageCursor? = null
        var nextCursor: LocalTranscriptPageCursor? = null
        var needsMoreVisibleDialogue = true
        var cursorAdvanced = true

        do {
            val page = readPage(sessionId, pageCursor)
            if (currentSessionId() != sessionId) return null

            val live = liveMessages()
            olderMessages = prependUniqueOlderMessages(page.messages, olderMessages, live)
            nextCursor = page.nextCursor
            needsMoreVisibleDialogue = needsMoreUserVisibleDialogue(
                messages = mergeLocalTranscriptHistory(olderMessages, live),
                target = LOCAL_TRANSCRIPT_INITIAL_VISIBLE_DIALOGUE_MESSAGES,
            )
            cursorAdvanced = nextCursor != pageCursor
            pageCursor = nextCursor
        } while (nextCursor != null && needsMoreVisibleDialogue && cursorAdvanced)

        return LocalTranscriptHistoryLoadResult(
            olderMessages = olderMessages,
            nextCursor = nextCursor,
        )
    }

    suspend fun olderBatch(
        sessionId: String,
        olderMessages: List<LocalHarnessMessage>,
        startCursor: LocalTranscriptPageCursor,
    ): LocalTranscriptHistoryLoadResult? {
        var accumulated = olderMessages
        var nextCursor: LocalTranscriptPageCursor? = startCursor
        var loadedRawMessages = 0
        val initialVisibleCount = userVisibleDialogueMessageCount(
            mergeLocalTranscriptHistory(accumulated, liveMessages()),
        )
        var addedVisibleDialogue = 0
        var cursorAdvanced = true

        do {
            val requestedCursor = nextCursor ?: break
            val page = readPage(sessionId, requestedCursor)
            if (currentSessionId() != sessionId) return null

            val beforeSize = accumulated.size
            accumulated = prependUniqueOlderMessages(
                pageMessages = page.messages,
                olderMessages = accumulated,
                liveMessages = liveMessages(),
            )
            loadedRawMessages += accumulated.size - beforeSize
            nextCursor = page.nextCursor
            addedVisibleDialogue = (
                userVisibleDialogueMessageCount(
                    mergeLocalTranscriptHistory(accumulated, liveMessages()),
                ) - initialVisibleCount
            ).coerceAtLeast(0)
            cursorAdvanced = nextCursor != requestedCursor
        } while (
            nextCursor != null &&
            addedVisibleDialogue < LOCAL_TRANSCRIPT_HISTORY_VISIBLE_DIALOGUE_BATCH_MESSAGES &&
            cursorAdvanced
        )

        return LocalTranscriptHistoryLoadResult(
            olderMessages = accumulated,
            nextCursor = nextCursor,
            loadedRawMessages = loadedRawMessages,
        )
    }

    private suspend fun readPage(
        sessionId: String,
        cursor: LocalTranscriptPageCursor?,
    ): LocalTranscriptPage = withContext(Dispatchers.IO) {
        session.transcriptPageForUi(
            sessionId = sessionId,
            cursor = cursor,
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
}

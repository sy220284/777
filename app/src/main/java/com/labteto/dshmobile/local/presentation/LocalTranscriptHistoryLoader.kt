package com.labteto.dshmobile.local.presentation

import com.labteto.dshmobile.uicontract.UiMessageSnapshot
import com.labteto.dshmobile.uicontract.UiMessageRole
import com.labteto.dshmobile.uicontract.UiMessageHistoryPort
import com.labteto.dshmobile.uicontract.UiMessagePage
import com.labteto.dshmobile.uicontract.UiTranscriptCursor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class LocalTranscriptHistoryLoadResult(
    val olderMessages: List<UiMessageSnapshot>,
    val nextCursor: UiTranscriptCursor?,
    val loadedRawMessages: Int = 0,
)

/**
 * Reads event-backed transcript pages until a foreground-visible dialogue target is satisfied.
 *
 * Raw runtime/history paging remains bounded by [LOCAL_TRANSCRIPT_HISTORY_PAGE_MESSAGES]. This
 * loader only decides how many bounded pages are needed to satisfy the separate foreground quota.
 */
internal class LocalTranscriptHistoryLoader(
    private val session: UiMessageHistoryPort,
    private val currentSessionId: () -> String,
    private val liveMessages: () -> List<UiMessageSnapshot>,
) {
    suspend fun bootstrap(sessionId: String): LocalTranscriptHistoryLoadResult? {
        var olderMessages = emptyList<UiMessageSnapshot>()
        var pageCursor: UiTranscriptCursor? = null
        var nextCursor: UiTranscriptCursor? = null
        var needsMoreVisibleDialogue = true
        var cursorAdvanced = true
        var loadedPages = 0
        var loadedRawMessages = 0

        do {
            val page = readPage(sessionId, pageCursor, LOCAL_TRANSCRIPT_INITIAL_VISIBLE_DIALOGUE_MESSAGES)
            loadedPages++
            loadedRawMessages += page.messages.size
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
        } while (
            nextCursor != null &&
            needsMoreVisibleDialogue &&
            cursorAdvanced &&
            loadedPages < LOCAL_TRANSCRIPT_HISTORY_MAX_PAGES_PER_LOAD &&
            loadedRawMessages < LOCAL_TRANSCRIPT_HISTORY_MAX_RAW_MESSAGES_PER_LOAD
        )

        return LocalTranscriptHistoryLoadResult(
            olderMessages = olderMessages,
            nextCursor = nextCursor,
            loadedRawMessages = loadedRawMessages,
        )
    }

    suspend fun olderBatch(
        sessionId: String,
        olderMessages: List<UiMessageSnapshot>,
        startCursor: UiTranscriptCursor,
    ): LocalTranscriptHistoryLoadResult? {
        var accumulated = olderMessages
        var nextCursor: UiTranscriptCursor? = startCursor
        var loadedRawMessages = 0
        val initialVisibleCount = userVisibleDialogueMessageCount(
            mergeLocalTranscriptHistory(accumulated, liveMessages()),
        )
        var addedVisibleDialogue = 0
        var cursorAdvanced = true
        var loadedPages = 0

        do {
            val requestedCursor = nextCursor ?: break
            val page = readPage(sessionId, requestedCursor, LOCAL_TRANSCRIPT_HISTORY_PAGE_MESSAGES)
            loadedPages++
            loadedRawMessages += page.messages.size
            if (currentSessionId() != sessionId) return null

            accumulated = prependUniqueOlderMessages(
                pageMessages = page.messages,
                olderMessages = accumulated,
                liveMessages = liveMessages(),
            )
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
            cursorAdvanced &&
            loadedPages < LOCAL_TRANSCRIPT_HISTORY_MAX_PAGES_PER_LOAD &&
            loadedRawMessages < LOCAL_TRANSCRIPT_HISTORY_MAX_RAW_MESSAGES_PER_LOAD
        )

        return LocalTranscriptHistoryLoadResult(
            olderMessages = accumulated,
            nextCursor = nextCursor,
            loadedRawMessages = loadedRawMessages,
        )
    }

    private suspend fun readPage(
        sessionId: String,
        cursor: UiTranscriptCursor?,
        limit: Int,
    ): UiMessagePage = withContext(Dispatchers.IO) {
        session.messagePage(
            sessionId = sessionId,
            cursor = cursor,
            limit = limit,
        )
    }

    private fun prependUniqueOlderMessages(
        pageMessages: List<UiMessageSnapshot>,
        olderMessages: List<UiMessageSnapshot>,
        liveMessages: List<UiMessageSnapshot>,
    ): List<UiMessageSnapshot> {
        if (pageMessages.isEmpty()) return olderMessages
        val existingIds = HashSet<String>(olderMessages.size + liveMessages.size)
        olderMessages.mapTo(existingIds, UiMessageSnapshot::id)
        liveMessages.mapTo(existingIds, UiMessageSnapshot::id)
        val newlyLoaded = pageMessages.filter { message -> existingIds.add(message.id) }
        return if (newlyLoaded.isEmpty()) olderMessages else newlyLoaded + olderMessages
    }
}

// Foreground history budget survives UI replacement; no Compose or legacy renderer is needed.
internal const val LOCAL_TRANSCRIPT_HISTORY_PAGE_MESSAGES = 24
internal const val LOCAL_TRANSCRIPT_INITIAL_VISIBLE_DIALOGUE_MESSAGES = 12
internal const val LOCAL_TRANSCRIPT_HISTORY_VISIBLE_DIALOGUE_BATCH_MESSAGES = 24
internal const val LOCAL_TRANSCRIPT_HISTORY_MAX_PAGES_PER_LOAD = 8
internal const val LOCAL_TRANSCRIPT_HISTORY_MAX_RAW_MESSAGES_PER_LOAD = 256

internal fun userVisibleDialogueMessageCount(messages: List<UiMessageSnapshot>): Int =
    messages.count { it.role == UiMessageRole.USER || it.role == UiMessageRole.ASSISTANT }

internal fun needsMoreUserVisibleDialogue(messages: List<UiMessageSnapshot>, target: Int): Boolean =
    userVisibleDialogueMessageCount(messages) < target.coerceAtLeast(0)

internal fun mergeLocalTranscriptHistory(
    olderMessages: List<UiMessageSnapshot>,
    liveMessages: List<UiMessageSnapshot>,
): List<UiMessageSnapshot> {
    if (olderMessages.isEmpty()) return liveMessages
    if (liveMessages.isEmpty()) return olderMessages
    val liveIds = liveMessages.mapTo(hashSetOf(), UiMessageSnapshot::id)
    val seen = hashSetOf<String>()
    return buildList(olderMessages.size + liveMessages.size) {
        olderMessages.forEach { message ->
            if (message.id !in liveIds && seen.add(message.id)) add(message)
        }
        liveMessages.forEach { message -> if (seen.add(message.id)) add(message) }
    }
}

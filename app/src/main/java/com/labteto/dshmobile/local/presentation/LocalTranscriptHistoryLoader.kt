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

/** One paginated history algorithm for native V6 and contract-backed UI.
 * It handles bounded reads and identity-safe deduplication without dropping rich message fields. */
internal data class TranscriptHistoryBatch<T, C>(
    val olderMessages: List<T>, val nextCursor: C?, val loadedRawMessages: Int,
)

internal class TranscriptHistoryPager<T, C : Any>(
    private val readPage: suspend (String, C?, Int) -> Pair<List<T>, C?>,
    private val liveMessages: () -> List<T>,
    private val currentSessionId: () -> String,
    private val messageId: (T) -> String,
    private val countDialogue: (List<T>) -> Int,
) {
    suspend fun bootstrap(sessionId: String): TranscriptHistoryBatch<T, C>? {
        var accumulated = emptyList<T>()
        var cursor: C? = null
        var loadedRaw = 0
        var loadedPages = 0
        do {
            val requestedCursor = cursor
            val (page, following) = readPage(
                sessionId, requestedCursor, LOCAL_TRANSCRIPT_INITIAL_VISIBLE_DIALOGUE_MESSAGES)
            loadedRaw += page.size
            loadedPages++
            if (currentSessionId() != sessionId) return null
            accumulated = prependUnique(page, accumulated, liveMessages())
            cursor = following
            val enough = countDialogue(merge(accumulated, liveMessages())) >=
                LOCAL_TRANSCRIPT_INITIAL_VISIBLE_DIALOGUE_MESSAGES
        } while (cursor != null && cursor != requestedCursor && !enough &&
            loadedPages < LOCAL_TRANSCRIPT_HISTORY_MAX_PAGES_PER_LOAD &&
            loadedRaw < LOCAL_TRANSCRIPT_HISTORY_MAX_RAW_MESSAGES_PER_LOAD)
        return TranscriptHistoryBatch(accumulated, cursor, loadedRaw)
    }

    suspend fun olderBatch(
        sessionId: String, older: List<T>, startCursor: C,
    ): TranscriptHistoryBatch<T, C>? {
        var accumulated = older
        var cursor: C? = startCursor
        var loadedRaw = 0
        var loadedPages = 0
        val initialDialogue = countDialogue(merge(accumulated, liveMessages()))
        do {
            val requestedCursor = cursor ?: break
            val (page, following) = readPage(
                sessionId, requestedCursor, LOCAL_TRANSCRIPT_HISTORY_PAGE_MESSAGES)
            loadedRaw += page.size
            loadedPages++
            if (currentSessionId() != sessionId) return null
            accumulated = prependUnique(page, accumulated, liveMessages())
            cursor = following
            val addedDialogue = countDialogue(merge(accumulated, liveMessages())) - initialDialogue
        } while (cursor != null && cursor != requestedCursor &&
            addedDialogue < LOCAL_TRANSCRIPT_HISTORY_VISIBLE_DIALOGUE_BATCH_MESSAGES &&
            loadedPages < LOCAL_TRANSCRIPT_HISTORY_MAX_PAGES_PER_LOAD &&
            loadedRaw < LOCAL_TRANSCRIPT_HISTORY_MAX_RAW_MESSAGES_PER_LOAD)
        return TranscriptHistoryBatch(accumulated, cursor, loadedRaw)
    }


    private fun prependUnique(page: List<T>, older: List<T>, live: List<T>): List<T> {
        if (page.isEmpty()) return older
        val ids = HashSet<String>(older.size + live.size)
        older.mapTo(ids, messageId)
        live.mapTo(ids, messageId)
        val added = page.filter { ids.add(messageId(it)) }
        return if (added.isEmpty()) older else added + older
    }

    private fun merge(older: List<T>, live: List<T>): List<T> {
        if (older.isEmpty()) return live
        if (live.isEmpty()) return older
        val liveIds = live.mapTo(hashSetOf(), messageId)
        val seen = hashSetOf<String>()
        return buildList(older.size + live.size) {
            older.forEach { if (messageId(it) !in liveIds && seen.add(messageId(it))) add(it) }
            live.forEach { if (seen.add(messageId(it))) add(it) }
        }
    }
}

/** Contract-specific page projection; source, cursor and bounds remain Session-owned. */
internal class LocalTranscriptHistoryLoader(
    session: UiMessageHistoryPort,
    currentSessionId: () -> String,
    liveMessages: () -> List<UiMessageSnapshot>,
) {
    private val pager = TranscriptHistoryPager(
        readPage = { sessionId: String, cursor: UiTranscriptCursor?, limit: Int ->
            val page = withContext(Dispatchers.IO) {
                session.messagePage(sessionId, cursor, limit)
            }
            page.messages to page.nextCursor
        },
        liveMessages = liveMessages,
        currentSessionId = currentSessionId,
        messageId = UiMessageSnapshot::id,
        countDialogue = ::userVisibleDialogueMessageCount,
    )

    suspend fun bootstrap(sessionId: String): LocalTranscriptHistoryLoadResult? =
        withContext(Dispatchers.Default) {
            pager.bootstrap(sessionId)?.let {
                LocalTranscriptHistoryLoadResult(it.olderMessages, it.nextCursor, it.loadedRawMessages)
            }
        }

    suspend fun olderBatch(
        sessionId: String,
        olderMessages: List<UiMessageSnapshot>,
        startCursor: UiTranscriptCursor,
    ): LocalTranscriptHistoryLoadResult? = withContext(Dispatchers.Default) {
        pager.olderBatch(sessionId, olderMessages, startCursor)?.let {
            LocalTranscriptHistoryLoadResult(it.olderMessages, it.nextCursor, it.loadedRawMessages)
        }
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

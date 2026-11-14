package com.labteto.dshmobile.ui.screens.local

import com.labteto.dshmobile.local.session.LocalHarnessMessage
import com.labteto.dshmobile.local.presentation.LocalSessionUiFacade
import com.labteto.dshmobile.local.session.LocalTranscriptPageCursor
import com.labteto.dshmobile.local.presentation.TranscriptHistoryPager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class LocalTranscriptHistoryLoadResult(
    val olderMessages: List<LocalHarnessMessage>,
    val nextCursor: LocalTranscriptPageCursor?,
    val loadedRawMessages: Int = 0,
)

/** Narrow, cancellable history source for deterministic navigation ordering tests. */
internal interface LocalTranscriptHistoryPageSource {
    suspend fun bootstrap(sessionId: String): LocalTranscriptHistoryLoadResult?
    suspend fun olderBatch(
        sessionId: String,
        olderMessages: List<LocalHarnessMessage>,
        startCursor: LocalTranscriptPageCursor,
    ): LocalTranscriptHistoryLoadResult?
}

/** Native V6 preserves rich tool/media/message blocks while sharing the one bounded pager. */
internal class LocalTranscriptHistoryLoader(
    private val session: LocalSessionUiFacade,
    currentSessionId: () -> String,
    liveMessages: () -> List<LocalHarnessMessage>,
) : LocalTranscriptHistoryPageSource {
    private val pager = TranscriptHistoryPager(
        readPage = { sessionId: String, cursor: LocalTranscriptPageCursor?, limit: Int ->
            val page = withContext(Dispatchers.IO) {
                session.transcriptPage(sessionId, cursor, limit)
            }
            page.messages to page.nextCursor
        },
        liveMessages = liveMessages,
        currentSessionId = currentSessionId,
        messageId = LocalHarnessMessage::id,
        countDialogue = ::userVisibleDialogueMessageCount,
    )

    override suspend fun bootstrap(sessionId: String): LocalTranscriptHistoryLoadResult? =
        withContext(Dispatchers.Default) {
            pager.bootstrap(sessionId)?.let {
                LocalTranscriptHistoryLoadResult(it.olderMessages, it.nextCursor, it.loadedRawMessages)
            }
        }

    override suspend fun olderBatch(
        sessionId: String,
        olderMessages: List<LocalHarnessMessage>,
        startCursor: LocalTranscriptPageCursor,
    ): LocalTranscriptHistoryLoadResult? = withContext(Dispatchers.Default) {
        pager.olderBatch(sessionId, olderMessages, startCursor)?.let {
            LocalTranscriptHistoryLoadResult(it.olderMessages, it.nextCursor, it.loadedRawMessages)
        }
    }
}

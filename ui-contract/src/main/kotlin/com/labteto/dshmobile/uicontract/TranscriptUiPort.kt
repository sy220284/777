package com.labteto.dshmobile.uicontract

import com.labteto.dshmobile.local.session.LocalHarnessMessage

/** Owner-issued paging continuation; no projection repair state crosses the UI API. */
interface UiTranscriptCursor
/** Compatibility-only: existing V6 paging requires owner message metadata. */
data class UiTranscriptPage(val messages: List<LocalHarnessMessage>, val nextCursor: UiTranscriptCursor?)
/** Read-only, domain-neutral history for independently compiled new UI packs. */
data class UiMessagePage(val messages: List<UiMessageSnapshot>, val nextCursor: UiTranscriptCursor?)
/** Search continuations are scoped to one session, query and original transcript generation. */
interface UiMessageSearchCursor

data class UiMessageSearchPage(val page: UiMessagePage, val hitIds: Set<String>,
    val scannedMessages: Long, val nextCursor: UiMessageSearchCursor?)
sealed interface UiMessageSearchReceipt {
    data class Available(val result: UiMessageSearchPage) : UiMessageSearchReceipt
    data class Failed(val message: String?) : UiMessageSearchReceipt
}
interface UiMessageHistoryPort {
    suspend fun searchMessages(sessionId: String, query: String,
        cursor: UiMessageSearchCursor? = null): UiMessageSearchReceipt = UiMessageSearchReceipt.Failed(null)
    suspend fun messagePage(sessionId: String, cursor: UiTranscriptCursor?, limit: Int): UiMessagePage
}
interface TranscriptUiPort {
    suspend fun transcriptPage(sessionId: String, cursor: UiTranscriptCursor?, limit: Int): UiTranscriptPage
}

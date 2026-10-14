package com.labteto.dshmobile.uicontract

import com.labteto.dshmobile.local.session.LocalHarnessMessage

/** Owner-issued paging continuation; no projection repair state crosses the UI API. */
interface UiTranscriptCursor
data class UiTranscriptPage(val messages: List<LocalHarnessMessage>, val nextCursor: UiTranscriptCursor?)
interface TranscriptUiPort {
    suspend fun transcriptPage(sessionId: String, cursor: UiTranscriptCursor?, limit: Int): UiTranscriptPage
}

package com.labteto.dshmobile.local.presentation

import com.labteto.dshmobile.local.session.LocalSessionRuntime
import com.labteto.dshmobile.local.session.LocalTranscriptPageCursor
import javax.inject.Inject
import javax.inject.Singleton

/** Bounded Session read facade for presentation paging. */
@Singleton
class LocalSessionUiFacade @Inject constructor(
    private val session: LocalSessionRuntime,
) {
    internal fun transcriptPage(sessionId: String, cursor: LocalTranscriptPageCursor?, limit: Int) =
        session.transcriptPageForUi(sessionId, cursor, limit)
}

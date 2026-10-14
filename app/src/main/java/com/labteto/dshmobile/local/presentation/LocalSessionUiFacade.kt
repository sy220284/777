package com.labteto.dshmobile.local.presentation

import com.labteto.dshmobile.local.session.LocalSessionReadRuntime
import com.labteto.dshmobile.local.session.LocalTranscriptPageCursor
import com.labteto.dshmobile.uicontract.TranscriptUiPort
import com.labteto.dshmobile.uicontract.UiTranscriptCursor
import com.labteto.dshmobile.uicontract.UiTranscriptPage
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Bounded authorized Session reads. Pager repair state remains private to its original owner. */
@Singleton
class LocalSessionUiFacade @Inject constructor(
    private val session: LocalSessionReadRuntime,
) : TranscriptUiPort {
    private data class Cursor(val sessionId: String, val instance: String, val reset: Long,
        val ownerCursor: LocalTranscriptPageCursor) : UiTranscriptCursor

    override suspend fun transcriptPage(sessionId: String, cursor: UiTranscriptCursor?, limit: Int): UiTranscriptPage = withContext(Dispatchers.IO) {
        val continuation = cursor?.let { it as? Cursor ?: error("不支持的历史分页标识") }
        require(continuation == null || continuation.sessionId == sessionId) { "历史分页不属于当前会话" }
        // The Session read owner repeats scope authorization before any result is exposed.
        val frame = session.transcriptReadPage(sessionId, continuation?.ownerCursor, limit)
        val page = frame.page
        require(continuation == null || (continuation.instance == frame.instance && continuation.reset == frame.reset)) {
            "历史已更新，请重新加载"
        }
        UiTranscriptPage(page.messages, page.nextCursor?.let { Cursor(sessionId, frame.instance, frame.reset, it) })
    }
}

@dagger.Module
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
internal abstract class LocalTranscriptUiPortBindings {
    @dagger.Binds @Singleton
    abstract fun transcript(implementation: LocalSessionUiFacade): TranscriptUiPort
}

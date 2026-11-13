package com.labteto.dshmobile.local.presentation

import com.labteto.dshmobile.local.session.LocalSessionReadRuntime
import com.labteto.dshmobile.local.session.LocalTranscriptPageCursor
import com.labteto.dshmobile.local.session.LocalHarnessMessage
import com.labteto.dshmobile.uicontract.UiTranscriptCursor
import com.labteto.dshmobile.uicontract.UiMessagePage
import com.labteto.dshmobile.uicontract.UiMessageSnapshot
import com.labteto.dshmobile.uicontract.UiMessageRole
import com.labteto.dshmobile.uicontract.UiMessageHistoryPort
import com.labteto.dshmobile.uicontract.UiMessageSearchCursor
import com.labteto.dshmobile.uicontract.UiMessageSearchPage
import com.labteto.dshmobile.uicontract.UiMessageSearchReceipt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Bounded authorized Session reads. Pager repair state remains private to its original owner. */
@Singleton
class LocalSessionUiFacade @Inject constructor(
    private val session: LocalSessionReadRuntime,
) : UiMessageHistoryPort {
    internal suspend fun previewMessages(sessionId: String, limit: Int = 12): List<LocalHarnessMessage> =
        withContext(Dispatchers.IO) { session.previewTail(sessionId, limit) }

    private data class Cursor(val sessionId: String, val instance: String, val reset: Long,
        val ownerCursor: LocalTranscriptPageCursor) : UiTranscriptCursor

    private data class SearchCursor(val sessionId: String, val query: String,
        val continuation: UiTranscriptCursor) : UiMessageSearchCursor

    override suspend fun searchMessages(sessionId: String, query: String,
        cursor: UiMessageSearchCursor?): UiMessageSearchReceipt = withContext(Dispatchers.IO) {
        try {
            val normalized = query.trim()
            require(normalized.isNotEmpty()) { "请输入搜索关键词" }
            val captured = cursor?.let { it as? SearchCursor ?: error("搜索标识已失效，请重新搜索") }
            require(captured == null || (captured.sessionId == sessionId && captured.query == normalized)) {
                "会话或关键词已变化，请重新搜索"
            }
            var continuation = captured?.continuation
            var scanned = 0L
            var page: UiMessagePage
            var hits: Set<String>
            do {
                coroutineContext.ensureActive()
                // Reuse the authorized, generation-checked pager; keep only one bounded page.
                page = messagePage(sessionId, continuation, 200)
                scanned += page.messages.size
                hits = page.messages.filter { it.text.contains(normalized, ignoreCase = true) }.map { it.id }.toSet()
                continuation = page.nextCursor
            } while (hits.isEmpty() && continuation != null)
            UiMessageSearchReceipt.Available(UiMessageSearchPage(page, hits, scanned,
                continuation?.let { SearchCursor(sessionId, normalized, it) }))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            com.labteto.dshmobile.observability.AppLog.warn("SessionSearch", "failure=true operation=search_session_history cause_type=${error.javaClass.simpleName}")
            UiMessageSearchReceipt.Failed(error.message)
        }
    }

    private suspend fun rawTranscriptPage(sessionId: String, cursor: UiTranscriptCursor?, limit: Int): Pair<List<LocalHarnessMessage>, UiTranscriptCursor?> = withContext(Dispatchers.IO) {
        val continuation = cursor?.let { it as? Cursor ?: error("不支持的历史分页标识") }
        require(continuation == null || continuation.sessionId == sessionId) { "历史分页不属于当前会话" }
        // The Session read owner repeats scope authorization before any result is exposed.
        val frame = session.transcriptReadPage(sessionId, continuation?.ownerCursor, limit)
        val page = frame.page
        require(continuation == null || (continuation.instance == frame.instance && continuation.reset == frame.reset)) {
            "历史已更新，请重新加载"
        }
        page.messages to page.nextCursor?.let { Cursor(sessionId, frame.instance, frame.reset, it) }
    }
    /** Legacy V6 reads the same authorized, bounded native Session pager. */
    internal fun transcriptPage(sessionId: String, cursor: LocalTranscriptPageCursor?, limit: Int) = session.transcriptPage(sessionId, cursor, limit)

    override suspend fun messagePage(sessionId: String, cursor: UiTranscriptCursor?, limit: Int): UiMessagePage {
        val (messages, nextCursor) = rawTranscriptPage(sessionId, cursor, limit)
        return UiMessagePage(messages.map(::projectTranscriptHistoryMessage), nextCursor)
    }
}

/** Only semantic presentation fields cross into any UI pack; Session metadata stays in the Owner. */
internal fun projectTranscriptHistoryMessage(message: LocalHarnessMessage): UiMessageSnapshot =
    UiMessageSnapshot(message.id, when (message.role) {
        "user" -> UiMessageRole.USER
        "assistant", "progress" -> UiMessageRole.ASSISTANT
        "tool" -> UiMessageRole.TOOL
        else -> UiMessageRole.SYSTEM
    }, message.content, message.createdAt, proactive = message.proactive,
        progressive = message.role == "progress")


@dagger.Module
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
internal abstract class LocalMessageHistoryPortBindings {
    @dagger.Binds @Singleton
    abstract fun messageHistory(implementation: LocalSessionUiFacade): UiMessageHistoryPort
}

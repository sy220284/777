package com.labteto.dshmobile.local.session

import com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore
import com.labteto.dshmobile.local.runtime.LocalSessionStorageRuntime
import javax.inject.Inject
import javax.inject.Singleton

/** Read-only Session capability for bounded, authorized transcript access. */
@Singleton
class LocalSessionReadRuntime @Inject internal constructor(
    private val sessionStorage: LocalSessionStorageRuntime,
    runtimeStateStore: LocalRuntimeStateStore,
    activeSessionScopes: LocalActiveSessionScopeProvider,
    eventLogs: LocalSessionEventLogRegistry,
) {
    private val access = LocalSessionAccessCoordinator(
        summaries = sessionStorage.coordinator::summaries,
        currentSessionId = { runtimeStateStore.currentSessionId },
        currentScope = {
            runtimeStateStore.state.value.let { current ->
                LocalSessionAccessScope(
                    projectId = current.projectId,
                    lineageId = current.lineageId.ifBlank { current.sessionId },
                )
            }
        },
        activeScope = activeSessionScopes::scope,
        eventLogFor = eventLogs::get,
    )

    internal data class ReadPage(val page: LocalTranscriptPage, val instance: String, val reset: Long)

    internal fun transcriptPage(sessionId: String, cursor: LocalTranscriptPageCursor? = null,
        limit: Int = 200): LocalTranscriptPage = transcriptReadPage(sessionId, cursor, limit).page

    internal fun transcriptReadPage(sessionId: String, cursor: LocalTranscriptPageCursor?, limit: Int): ReadPage {
        val log = access.authorizedLog(sessionId)
        val instance = log.historyInstanceIdentity
        val reset = log.resetGeneration
        val page = LocalSessionTranscriptPager(log).page(cursor, limit)
        check(!log.isClosed && log.resetGeneration == reset) { "历史已更新，请重新加载" }
        return ReadPage(page, instance, reset)
    }

    internal fun previewTail(sessionId: String, limit: Int): List<LocalHarnessMessage> {
        // The same authorized Session capability is checked before exposing derived SQLite rows.
        access.authorizedLog(sessionId)
        return sessionStorage.previewMessages(sessionId, limit)
    }

    internal fun transcriptTail(sessionId: String, limit: Int): List<LocalHarnessMessage> =
        LocalSessionTranscriptPager(
            eventLog = access.authorizedLog(sessionId),
        ).page(limit = limit).messages

    internal fun completeTranscript(sessionId: String): List<LocalHarnessMessage> =
        LocalSessionTranscriptPager(
            eventLog = access.authorizedLog(sessionId),
        ).all()
}

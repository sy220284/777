package com.labteto.dshmobile.local.session

import com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore
import com.labteto.dshmobile.local.runtime.LocalSessionStorageRuntime
import javax.inject.Inject
import javax.inject.Singleton

/** Read-only Session capability for bounded, authorized transcript access. */
@Singleton
class LocalSessionReadRuntime @Inject internal constructor(
    sessionStorage: LocalSessionStorageRuntime,
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

    internal fun transcriptPage(
        sessionId: String,
        cursor: LocalTranscriptPageCursor? = null,
        limit: Int = 200,
    ): LocalTranscriptPage = LocalSessionTranscriptPager(
        eventLog = access.authorizedLog(sessionId),
    ).page(
        cursor = cursor,
        limit = limit,
    )

    internal fun transcriptTail(sessionId: String, limit: Int): List<LocalHarnessMessage> =
        LocalSessionTranscriptPager(
            eventLog = access.authorizedLog(sessionId),
        ).page(limit = limit).messages

    internal fun completeTranscript(sessionId: String): List<LocalHarnessMessage> =
        LocalSessionTranscriptPager(
            eventLog = access.authorizedLog(sessionId),
        ).all()
}

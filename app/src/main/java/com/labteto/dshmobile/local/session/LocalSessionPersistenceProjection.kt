package com.labteto.dshmobile.local.session

import com.labteto.dshmobile.harness.session.FutureSessionVersionException
import com.labteto.dshmobile.local.LocalHarnessState
import com.labteto.dshmobile.local.LocalSessionCoordinator

/**
 * Session-owned durable projections used by the local runtime composition root.
 *
 * Keeping snapshot cursor ordering and future-version compatibility here prevents the Engine from
 * owning Session persistence policy while preserving Session Event as the durable fact source.
 */
internal fun localSessionPersistenceSnapshot(
    sessionCoordinator: LocalSessionCoordinator,
    currentSessionId: String,
    currentState: () -> LocalHarnessState,
    eventLog: LocalSessionEventLog,
    transcriptProjectionCursor: Long?,
): LocalHarnessSession {
    // Capture durable projection boundaries before reading mutable state. If a concurrent update
    // lands afterwards, replaying its event is safe and idempotent. Reading state first could
    // instead persist old state with a newer cursor and make recovery skip that event.
    val controlProjectedThroughSequence = eventLog.latestSequence()
    val transcriptProjectedThroughSequence = transcriptProjectionCursor
    val state = currentState()
    return sessionCoordinator.snapshot(
        sessionId = currentSessionId,
        state = state,
        controlProjectedThroughSequence = controlProjectedThroughSequence,
        transcriptProjectedThroughSequence = transcriptProjectedThroughSequence,
    )
}

internal fun localSessionSummariesOrEmpty(
    sessionCoordinator: LocalSessionCoordinator,
    onFutureVersion: (FutureSessionVersionException) -> Unit,
): List<LocalSessionSummary> = try {
    sessionCoordinator.summaries()
} catch (future: FutureSessionVersionException) {
    onFutureVersion(future)
    emptyList()
}

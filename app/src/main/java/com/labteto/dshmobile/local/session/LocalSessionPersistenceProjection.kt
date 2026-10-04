package com.labteto.dshmobile.local

import com.labteto.dshmobile.harness.session.FutureSessionVersionException

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
    binding: LocalWorkRunBinding? = null,
): LocalHarnessSession {
    // Capture durable projection boundaries before reading mutable state. If a concurrent update
    // lands afterwards, replaying its event is safe and idempotent. Reading state first could
    // instead persist old state with a newer cursor and make recovery skip that event.
    val log = binding?.eventLog ?: eventLog
    val controlProjectedThroughSequence = log.latestSequence()
    val transcriptProjectedThroughSequence =
        binding?.transcriptProjectionCursor ?: transcriptProjectionCursor
    val state = binding?.state?.value ?: currentState()
    return sessionCoordinator.snapshot(
        sessionId = binding?.sessionId ?: currentSessionId,
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

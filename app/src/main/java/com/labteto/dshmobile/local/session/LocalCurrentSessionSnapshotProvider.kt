package com.labteto.dshmobile.local.session

/**
 * Shared Session asks for a materialized current snapshot through this neutral contract.
 * The app composition implementation may combine Feature-owned state, but Session infrastructure
 * never imports Chat/Work internals or the aggregate LocalHarnessState.
 */
internal interface LocalCurrentSessionSnapshotProvider {
    fun snapshot(
        expectedSessionId: String,
        boundary: LocalSessionSnapshotBoundary,
        runtimeWindowMessages: Int,
    ): LocalHarnessSession?
}

internal data class LocalSessionSnapshotBoundary(
    val controlProjectedThroughSequence: Long,
    val transcriptProjectedThroughSequence: Long?,
)

internal fun localSessionSnapshotBoundary(
    eventLog: LocalSessionEventLog,
    transcriptProjectionCursor: Long?,
): LocalSessionSnapshotBoundary = LocalSessionSnapshotBoundary(
    // Read the durable control cursor before the mutable Feature snapshot is materialized.
    controlProjectedThroughSequence = eventLog.latestSequence(),
    transcriptProjectedThroughSequence = transcriptProjectionCursor,
)

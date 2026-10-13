package com.labteto.dshmobile.local

/**
 * Request evidence memo: a non-authoritative cache of references into the existing SessionEventLog.
 *
 * Message, tool and context surfaces use the same identity, digest and sequence contract.
 * This class does not persist, reconstruct, or own evidence: the EventLog is the sole authority.
 * After log recreation/truncation, a reference with a sequence above the current log tip is
 * invalidated and its original payload is appended again by the request coordinator.
 */
internal enum class LocalRequestSurfaceKind { MESSAGE, TOOL, CONTEXT }

internal class LocalRequestEvidenceSurfaceMemo {
    private data class Reference(val digest: String, val sequence: Long)
    private val lock = Any()
    private val entries = mutableMapOf<LocalRequestSurfaceKind, MutableMap<String, Reference>>()

    fun ensure(
        kind: LocalRequestSurfaceKind,
        key: String,
        digest: String,
        currentLogSequence: Long,
        append: () -> Long,
    ): Long = synchronized(lock) {
        val surface = entries.getOrPut(kind) { mutableMapOf() }
        val cached = surface[key]
        if (cached?.digest == digest && cached.sequence <= currentLogSequence) {
            cached.sequence
        } else {
            // Only memoize after the durable EventLog append actually succeeds.
            val sequence = append()
            surface[key] = Reference(digest, sequence)
            sequence
        }
    }
}

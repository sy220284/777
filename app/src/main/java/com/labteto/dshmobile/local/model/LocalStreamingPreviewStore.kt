package com.labteto.dshmobile.local.model

import com.labteto.dshmobile.local.LocalUsageMode
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Identity of one model request allowed to own the transient visible preview. */
internal data class LocalStreamingPreviewOwner(
    val sessionId: String,
    val requestId: String,
    val usageMode: LocalUsageMode,
    val generation: Long,
)

/**
 * Owns transient streaming preview independently from the durable transcript.
 *
 * The preview is process-wide for rendering efficiency, but every mutation is identity-scoped.
 * A stale request can never overwrite or clear a newer request from the same session, and UI can
 * reject previews that belong to another session or product mode.
 */
internal fun LocalHarnessStreamingState.forSurface(
    sessionId: String,
    usageMode: LocalUsageMode,
): LocalHarnessStreamingState =
    if (this.sessionId == sessionId && this.usageMode == usageMode) {
        this
    } else {
        LocalHarnessStreamingState()
    }

internal class LocalStreamingPreviewStore {
    private val generation = AtomicLong(0L)
    private val mutableState = MutableStateFlow(LocalHarnessStreamingState())
    val state: StateFlow<LocalHarnessStreamingState> = mutableState.asStateFlow()

    fun newOwner(
        sessionId: String,
        requestId: String,
        usageMode: LocalUsageMode,
    ): LocalStreamingPreviewOwner = LocalStreamingPreviewOwner(
        sessionId = sessionId,
        requestId = requestId,
        usageMode = usageMode,
        generation = generation.incrementAndGet(),
    )

    fun begin(owner: LocalStreamingPreviewOwner) {
        mutableState.update { current ->
            if (current.accepts(owner)) owner.emptyState() else current
        }
    }

    fun publishAssistant(owner: LocalStreamingPreviewOwner, text: String) {
        if (text.isEmpty()) return
        mutableState.update { current ->
            when {
                current.requestId == owner.requestId -> current.copy(assistant = text)
                current.accepts(owner) -> owner.emptyState().copy(assistant = text)
                else -> current
            }
        }
    }

    fun clear(owner: LocalStreamingPreviewOwner) {
        mutableState.update { current ->
            if (current.sessionId == owner.sessionId && current.requestId == owner.requestId) {
                LocalHarnessStreamingState()
            } else {
                current
            }
        }
    }

    private fun LocalHarnessStreamingState.accepts(owner: LocalStreamingPreviewOwner): Boolean =
        sessionId != owner.sessionId || owner.generation >= generation

    private fun LocalStreamingPreviewOwner.emptyState() = LocalHarnessStreamingState(
        sessionId = sessionId,
        requestId = requestId,
        usageMode = usageMode,
        generation = generation,
    )
}

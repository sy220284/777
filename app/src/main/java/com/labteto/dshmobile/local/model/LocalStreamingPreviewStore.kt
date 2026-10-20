package com.labteto.dshmobile.local.model

import com.labteto.dshmobile.local.LocalUsageMode
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
 * Each live request retains its bounded preview independently of the visible conversation.
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
    // Only live requests retain previews. Completion removes its slot; no historical cache.
    private val previews = mutableMapOf<String, LocalHarnessStreamingState>()
    private var visibleSessionId: String? = null

    @Synchronized
    fun selectSession(sessionId: String) {
        visibleSessionId = sessionId
        projectVisible()
    }

    @Synchronized
    fun newOwner(sessionId: String, requestId: String, usageMode: LocalUsageMode): LocalStreamingPreviewOwner {
        val owner = LocalStreamingPreviewOwner(sessionId, requestId, usageMode, generation.incrementAndGet())
        previews[sessionId] = owner.emptyState()
        projectVisible()
        return owner
    }

    @Synchronized
    fun begin(owner: LocalStreamingPreviewOwner, attempt: Int = 1) {
        updateOwned(owner) { owner.emptyState().copy(phase = LocalModelPreviewPhase.WAITING, attempt = attempt) }
    }

    @Synchronized
    fun retry(owner: LocalStreamingPreviewOwner, nextAttempt: Int) {
        updateOwned(owner) { owner.emptyState().copy(phase = LocalModelPreviewPhase.RETRYING, attempt = nextAttempt) }
    }

    @Synchronized
    fun publishAssistant(owner: LocalStreamingPreviewOwner, text: String) {
        if (text.isEmpty()) return
        updateOwned(owner) { it.copy(assistant = text, phase = LocalModelPreviewPhase.STREAMING) }
    }

    @Synchronized
    fun publishReasoning(owner: LocalStreamingPreviewOwner, text: String) {
        if (text.isEmpty()) return
        updateOwned(owner) { it.copy(reasoning = text, phase = LocalModelPreviewPhase.STREAMING) }
    }

    @Synchronized
    fun clear(owner: LocalStreamingPreviewOwner) {
        if (previews[owner.sessionId]?.owns(owner) == true) {
            previews.remove(owner.sessionId)
            projectVisible()
        }
    }

    private fun updateOwned(owner: LocalStreamingPreviewOwner, transform: (LocalHarnessStreamingState) -> LocalHarnessStreamingState) {
        val current = previews[owner.sessionId] ?: return
        if (!current.owns(owner)) return
        previews[owner.sessionId] = transform(current)
        projectVisible()
    }

    private fun projectVisible() {
        mutableState.value = previews[visibleSessionId] ?: LocalHarnessStreamingState()
    }

    private fun LocalHarnessStreamingState.owns(owner: LocalStreamingPreviewOwner) =
        requestId == owner.requestId && generation == owner.generation && usageMode == owner.usageMode

    private fun LocalStreamingPreviewOwner.emptyState() = LocalHarnessStreamingState(
        sessionId = sessionId, requestId = requestId, usageMode = usageMode, generation = generation,
    )
}

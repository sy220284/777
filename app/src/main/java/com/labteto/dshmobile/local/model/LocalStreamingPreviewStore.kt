package com.labteto.dshmobile.local.model

import com.labteto.dshmobile.local.LocalHarnessStreamingState
import com.labteto.dshmobile.local.LocalUsageMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Identity of one model request allowed to own the transient visible preview. */
internal data class LocalStreamingPreviewOwner(
    val sessionId: String,
    val requestId: String,
    val usageMode: LocalUsageMode,
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
    private val mutableState = MutableStateFlow(LocalHarnessStreamingState())
    val state: StateFlow<LocalHarnessStreamingState> = mutableState.asStateFlow()

    fun begin(owner: LocalStreamingPreviewOwner) {
        mutableState.value = owner.emptyState()
    }

    fun publishAssistant(owner: LocalStreamingPreviewOwner, text: String) {
        if (text.isEmpty()) return
        mutableState.update { current ->
            when {
                current.sessionId != owner.sessionId -> owner.emptyState().copy(assistant = text)
                current.requestId == owner.requestId -> current.copy(assistant = text)
                else -> current
            }
        }
    }

    fun publishReasoning(owner: LocalStreamingPreviewOwner, text: String) {
        if (text.isEmpty()) return
        mutableState.update { current ->
            when {
                current.sessionId != owner.sessionId -> owner.emptyState().copy(reasoning = text)
                current.requestId == owner.requestId -> current.copy(reasoning = text)
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

    private fun LocalStreamingPreviewOwner.emptyState() = LocalHarnessStreamingState(
        sessionId = sessionId,
        requestId = requestId,
        usageMode = usageMode,
    )
}

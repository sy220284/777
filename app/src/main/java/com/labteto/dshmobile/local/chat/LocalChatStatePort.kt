package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.LocalHarnessState
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.model.LocalModelState
import com.labteto.dshmobile.local.runtime.LocalKernelState
import com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore
import com.labteto.dshmobile.local.session.LocalHarnessMessage
import com.labteto.dshmobile.local.session.LocalTranscriptRuntimeIndex
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * ChatFeature's writable projection.
 *
 * Shared facts are visible for admission/revalidation, but write-back is restricted to Chat-owned
 * state plus the Chat transcript window, handoff summary and user-visible error.
 */
internal data class LocalChatProjectionState(
    val loading: Boolean,
    val modelState: LocalModelState,
    val sessionId: String,
    val usageMode: LocalUsageMode,
    val chat: LocalChatState,
    val handoffSummary: String?,
    val messages: List<LocalHarnessMessage>,
    val transcriptIndex: LocalTranscriptRuntimeIndex,
    val kernel: LocalKernelState,
    val error: String?,
)

internal fun LocalHarnessState.toLocalChatProjectionState(): LocalChatProjectionState =
    LocalChatProjectionState(
        loading = loading,
        modelState = modelState,
        sessionId = sessionId,
        usageMode = usageMode,
        chat = chat,
        handoffSummary = handoffSummary,
        messages = messages,
        transcriptIndex = transcriptIndex,
        kernel = kernel,
        error = error,
    )

@Singleton
internal class LocalChatStatePort private constructor(
    private val readAggregate: () -> LocalHarnessState,
    private val updateAggregate: ((LocalHarnessState) -> LocalHarnessState) -> Unit,
) {
    @Inject
    internal constructor(runtimeStateStore: LocalRuntimeStateStore) : this(
        readAggregate = { runtimeStateStore.state.value },
        updateAggregate = { transform -> runtimeStateStore.projection.update(transform) },
    )

    internal constructor(state: MutableStateFlow<LocalHarnessState>) : this(
        readAggregate = { state.value },
        updateAggregate = { transform -> state.update(transform) },
    )

    internal val value: LocalChatProjectionState
        get() = readAggregate().toLocalChatProjectionState()

    internal fun update(
        transform: (LocalChatProjectionState) -> LocalChatProjectionState,
    ) {
        updateAggregate { current ->
            val updated = transform(current.toLocalChatProjectionState())
            current.copy(
                chat = updated.chat,
                handoffSummary = updated.handoffSummary,
                messages = updated.messages,
                transcriptIndex = updated.transcriptIndex,
                error = updated.error,
            )
        }
    }
}

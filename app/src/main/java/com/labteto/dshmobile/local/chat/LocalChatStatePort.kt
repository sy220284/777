package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.model.LocalModelState
import com.labteto.dshmobile.local.runtime.LocalKernelState
import com.labteto.dshmobile.local.session.LocalHarnessMessage
import com.labteto.dshmobile.local.session.LocalTranscriptRuntimeIndex

/**
 * ChatFeature's narrow writable projection.
 *
 * The Feature never receives the aggregate LocalHarnessState or its writable flow. The app
 * composition root adapts Shared Runtime projection into this contract.
 */
internal data class LocalChatProjectionState(
    val loading: Boolean,
    val modelState: LocalModelState,
    val sessionId: String,
    val usageMode: LocalUsageMode,
    val autoRecall: Boolean,
    val lineageId: String,
    val chat: LocalChatState,
    val handoffSummary: String?,
    val messages: List<LocalHarnessMessage>,
    val transcriptIndex: LocalTranscriptRuntimeIndex,
    val kernel: LocalKernelState,
    val error: String?,
)

internal interface LocalChatStatePort {
    val value: LocalChatProjectionState

    fun update(transform: (LocalChatProjectionState) -> LocalChatProjectionState)
}

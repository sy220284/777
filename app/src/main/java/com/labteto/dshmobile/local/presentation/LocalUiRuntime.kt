package com.labteto.dshmobile.local.presentation

import com.labteto.dshmobile.local.chat.LocalChatRuntime
import com.labteto.dshmobile.local.model.LocalModelRuntime
import com.labteto.dshmobile.local.session.LocalSessionRuntime
import com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore
import com.labteto.dshmobile.local.work.LocalWorkRuntime
import javax.inject.Inject
import javax.inject.Singleton

/** Dependency-only aggregator; capability ownership stays in the four runtimes. */
@Singleton
class LocalUiRuntime @Inject constructor(
    internal val session: LocalSessionRuntime,
    internal val chat: LocalChatRuntime,
    internal val work: LocalWorkRuntime,
    internal val model: LocalModelRuntime,
    runtimeStateStore: LocalRuntimeStateStore,
) {
    internal val state = runtimeStateStore.state
    internal val streamingState = runtimeStateStore.streamingPreviewStore.state
    internal val sendFeedbackState = runtimeStateStore.sendFeedbackState
}

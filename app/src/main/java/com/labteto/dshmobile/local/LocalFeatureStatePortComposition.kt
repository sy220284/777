package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.chat.LocalChatProjectionState
import com.labteto.dshmobile.local.chat.LocalChatStatePort
import com.labteto.dshmobile.local.runtime.LocalAggregateProjectionPort
import com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore
import com.labteto.dshmobile.local.runtime.localAggregateProjectionPort
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** Application composition adapters from the aggregate runtime projection to Feature-owned ports. */
internal fun LocalHarnessState.toLocalChatProjectionState(): LocalChatProjectionState =
    LocalChatProjectionState(
        loading = loading,
        modelState = modelState,
        sessionId = sessionId,
        usageMode = usageMode,
        autoRecall = autoRecall,
        lineageId = lineageId,
        chat = chat,
        handoffSummary = handoffSummary,
        messages = messages,
        transcriptIndex = transcriptIndex,
        kernel = kernel,
        error = error,
    )

internal fun localAggregateChatStatePort(
    state: MutableStateFlow<LocalHarnessState>,
): LocalChatStatePort = localAggregateChatStatePort(localAggregateProjectionPort(state))

internal fun localAggregateChatStatePort(
    state: LocalAggregateProjectionPort,
): LocalChatStatePort = object : LocalChatStatePort {
    override val value: LocalChatProjectionState
        get() = state.value.toLocalChatProjectionState()

    override fun update(transform: (LocalChatProjectionState) -> LocalChatProjectionState) {
        state.update { current ->
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

@Module
@InstallIn(SingletonComponent::class)
internal object LocalFeatureStatePortCompositionModule {
    @Provides
    @Singleton
    fun provideLocalChatStatePort(runtimeStateStore: LocalRuntimeStateStore): LocalChatStatePort =
        localAggregateChatStatePort(runtimeStateStore.projection)
}

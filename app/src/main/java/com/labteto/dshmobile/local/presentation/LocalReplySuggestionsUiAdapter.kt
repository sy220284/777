package com.labteto.dshmobile.local.presentation

import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore
import com.labteto.dshmobile.local.uiBindingIdentity
import com.labteto.dshmobile.uicontract.ReplySuggestionsUiPort
import com.labteto.dshmobile.uicontract.UiReplySuggestionsSnapshot
import com.labteto.dshmobile.uicontract.UiChatReplySuggestion
import com.labteto.dshmobile.uicontract.UiSessionIdentity
import com.labteto.dshmobile.uicontract.UiCommandReceipt
import com.labteto.dshmobile.uicontract.UiCommandRejection
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.map

@Singleton
internal class LocalReplySuggestionsUiAdapter @Inject constructor(
    private val runtime: LocalUiRuntime,
    private val state: LocalRuntimeStateStore,
) : ReplySuggestionsUiPort {
    override val snapshots = state.state.map { current ->
        UiReplySuggestionsSnapshot(
            identity = if (current.usageMode == LocalUsageMode.CHAT)
                state.uiBindingIdentity(current) else null,
            values = if (current.usageMode == LocalUsageMode.CHAT)
                current.chat.replySuggestions.map {
                    UiChatReplySuggestion(it.label, it.text, it.style, it.bold)
                } else emptyList(),
        )
    }
    override suspend fun generate(identity: UiSessionIdentity): UiCommandReceipt {
        val admitted = synchronized(state.foregroundRunHandle.lock) {
            val current = state.state.value
            !state.sessionTransitioning && current.usageMode == LocalUsageMode.CHAT &&
                state.uiBindingIdentity(current) == identity && !current.loading
        }
        if (!admitted) return UiCommandReceipt.Rejected(UiCommandRejection.STALE_IDENTITY)
        return try {
            if (runtime.chat.generateReplySuggestions()) UiCommandReceipt.Accepted
            else UiCommandReceipt.NotAdmitted
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { UiCommandReceipt.Failed(failure.message) }
    }
}

@dagger.Module
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
internal abstract class LocalReplySuggestionsUiBindings {
    @dagger.Binds @Singleton
    abstract fun bind(implementation: LocalReplySuggestionsUiAdapter): ReplySuggestionsUiPort
}

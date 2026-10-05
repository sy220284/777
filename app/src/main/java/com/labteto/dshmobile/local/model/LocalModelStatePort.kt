package com.labteto.dshmobile.local.model

import com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore
import kotlinx.coroutines.flow.first

/** Model-owned view/write boundary over the shared visible runtime projection. */
internal data class LocalModelProjectionSnapshot(
    val loading: Boolean,
    val modelState: LocalModelState,
    val error: String?,
)

internal interface LocalModelStatePort {
    val value: LocalModelProjectionSnapshot
    suspend fun awaitReady()
    fun commit(modelState: LocalModelState, error: String?)
    fun publishError(message: String)
}

internal fun localModelStatePort(
    runtimeStateStore: LocalRuntimeStateStore,
): LocalModelStatePort = object : LocalModelStatePort {
    override val value: LocalModelProjectionSnapshot
        get() = runtimeStateStore.state.value.let { state ->
            LocalModelProjectionSnapshot(
                loading = state.loading,
                modelState = state.modelState,
                error = state.error,
            )
        }

    override suspend fun awaitReady() {
        runtimeStateStore.state.first { state -> !state.loading }
    }

    override fun commit(modelState: LocalModelState, error: String?) {
        runtimeStateStore.projection.projectModelState(modelState, error)
    }

    override fun publishError(message: String) {
        runtimeStateStore.projection.publishError(message)
    }
}

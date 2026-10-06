package com.labteto.dshmobile.local.presentation

import com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Tasks observes only automation-relevant local chat targeting state. */
@Singleton
class LocalTaskRuntime @Inject constructor(
    runtimeStateStore: LocalRuntimeStateStore,
) {
    private val runtimeState = runtimeStateStore.state
    val state: Flow<LocalHarnessTaskState> =
        runtimeState.map { it.toTaskUiState() }.distinctUntilChanged()
    val initialState: LocalHarnessTaskState get() = runtimeState.value.toTaskUiState()
    internal fun snapshot(): LocalHarnessTaskState = runtimeState.value.toTaskUiState()
}

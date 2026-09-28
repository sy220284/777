package com.labteto.dshmobile.local.presentation

import com.labteto.dshmobile.local.LocalHarnessEngine
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Tasks observes only automation-relevant local chat targeting state. */
@Singleton
class LocalTaskRuntime @Inject constructor(
    private val engine: LocalHarnessEngine,
) {
    val state: Flow<LocalHarnessTaskState> =
        engine.state.map { it.toTaskUiState() }.distinctUntilChanged()
    val initialState: LocalHarnessTaskState get() = engine.state.value.toTaskUiState()
    internal fun snapshot(): LocalHarnessTaskState = engine.state.value.toTaskUiState()
}

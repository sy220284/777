package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.LocalHarnessState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** Narrow mutable ownership surface for Work domain state. */
internal interface LocalWorkStatePort {
    fun snapshot(): LocalWorkState
    fun update(transform: (LocalWorkState) -> LocalWorkState)
}

/**
 * Transitional adapter from the aggregate projection to Work-owned state.
 *
 * Keep this adapter at the boundary; Work domain coordinators must depend on LocalWorkStatePort
 * rather than MutableStateFlow<LocalHarnessState>.
 */
internal fun localAggregateWorkStatePort(
    state: MutableStateFlow<LocalHarnessState>,
): LocalWorkStatePort = object : LocalWorkStatePort {
    override fun snapshot(): LocalWorkState = state.value.work

    override fun update(transform: (LocalWorkState) -> LocalWorkState) {
        state.update { current -> current.copy(work = transform(current.work)) }
    }
}


internal fun localWorkRunStatePort(
    state: MutableStateFlow<LocalWorkRunState>,
): LocalWorkStatePort = object : LocalWorkStatePort {
    override fun snapshot(): LocalWorkState = state.value.work

    override fun update(transform: (LocalWorkState) -> LocalWorkState) {
        state.update { current -> current.copy(work = transform(current.work)) }
    }
}

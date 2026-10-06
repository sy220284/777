package com.labteto.dshmobile.local.work

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** Narrow mutable ownership surface for Work domain state. */
internal interface LocalWorkStatePort {
    fun snapshot(): LocalWorkState
    fun update(transform: (LocalWorkState) -> LocalWorkState)
}

/** Detached Work runs already own a Work-only state container, so no aggregate adapter is needed. */
internal fun localWorkRunStatePort(
    state: MutableStateFlow<LocalWorkRunState>,
): LocalWorkStatePort = object : LocalWorkStatePort {
    override fun snapshot(): LocalWorkState = state.value.work

    override fun update(transform: (LocalWorkState) -> LocalWorkState) {
        state.update { current -> current.copy(work = transform(current.work)) }
    }
}

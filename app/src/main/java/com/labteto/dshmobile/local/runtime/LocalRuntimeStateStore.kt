package com.labteto.dshmobile.local.runtime

import com.labteto.dshmobile.local.LocalHarnessState
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide owner of the visible local runtime state.
 *
 * Engine bootstrap initializes the first snapshot once; Feature/Session capabilities then consume
 * the same state without reaching through LocalHarnessEngine.
 */
@Singleton
class LocalRuntimeStateStore @Inject constructor() {
    private val mutable = MutableStateFlow(LocalHarnessState())
    @Volatile private var initialized = false

    internal val state: StateFlow<LocalHarnessState> = mutable.asStateFlow()
    internal val mutableState: MutableStateFlow<LocalHarnessState>
        get() = mutable

    @Synchronized
    internal fun initialize(initialState: LocalHarnessState): MutableStateFlow<LocalHarnessState> {
        check(!initialized) { "LocalRuntimeStateStore 已完成初始化" }
        mutable.value = initialState
        initialized = true
        return mutable
    }
}

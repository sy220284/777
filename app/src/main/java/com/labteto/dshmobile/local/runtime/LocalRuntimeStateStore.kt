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
    @Volatile private var foregroundSessionId: String? = null

    internal val state: StateFlow<LocalHarnessState> = mutable.asStateFlow()
    internal val mutableState: MutableStateFlow<LocalHarnessState>
        get() = mutable

    internal val currentSessionId: String
        get() = foregroundSessionId ?: error("LocalRuntimeStateStore 尚未初始化")

    @Synchronized
    internal fun initialize(initialState: LocalHarnessState): MutableStateFlow<LocalHarnessState> {
        check(!initialized) { "LocalRuntimeStateStore 已完成初始化" }
        require(initialState.sessionId.isNotBlank()) { "初始会话编号不能为空" }
        foregroundSessionId = initialState.sessionId
        mutable.value = initialState
        initialized = true
        return mutable
    }

    @Synchronized
    internal fun activateSession(sessionId: String) {
        check(initialized) { "LocalRuntimeStateStore 尚未初始化" }
        require(sessionId.isNotBlank()) { "会话编号不能为空" }
        foregroundSessionId = sessionId
    }
}

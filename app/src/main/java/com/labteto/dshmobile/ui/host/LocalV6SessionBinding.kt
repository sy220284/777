package com.labteto.dshmobile.ui.host

import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.uicontract.SessionUiPort
import kotlinx.coroutines.flow.map

/** Owner-neutral V6 session binding; no legacy ViewModel or Shell references. */
internal class LocalV6SessionBinding(private val sessions: SessionUiPort) {
    fun isWork(): Boolean = sessions.snapshot().usageMode == LocalUsageMode.WORK
    fun sessionId(): String = sessions.snapshot().identity?.sessionId.orEmpty()
    val workModeUpdates: kotlinx.coroutines.flow.Flow<Boolean> =
        sessions.snapshots.map { it.usageMode == LocalUsageMode.WORK }
}

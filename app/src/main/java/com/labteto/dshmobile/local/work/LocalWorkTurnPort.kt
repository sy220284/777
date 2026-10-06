package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.runtime.LocalSessionRuntimeLease
import com.labteto.dshmobile.local.send.LocalPreparedSend
import kotlinx.coroutines.Job

/**
 * Transitional low-level Work turn start bridge.
 *
 * WorkFeature owns product admission, regeneration and Agent execution. This bridge now only starts
 * an already-admitted first Work turn until the remaining composition dependencies leave Engine.
 */
internal interface LocalWorkTurnPort {
    fun startPrepared(
        prepared: LocalPreparedSend,
        sessionLease: LocalSessionRuntimeLease,
    ): Job
}

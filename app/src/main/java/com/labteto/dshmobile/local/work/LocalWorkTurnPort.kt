package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.runtime.LocalSessionRuntimeLease
import com.labteto.dshmobile.local.send.LocalPreparedSend
import kotlinx.coroutines.Job

/**
 * Work-owned low-level turn-start contract.
 *
 * WorkFeature owns product admission, regeneration and Agent execution. This port only starts an
 * already-admitted Work turn and does not expose Work internals to callers.
 */
internal interface LocalWorkTurnPort {
    fun startPrepared(
        prepared: LocalPreparedSend,
        sessionLease: LocalSessionRuntimeLease,
    ): Job
}

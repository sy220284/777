package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.runtime.LocalSessionRuntimeLease
import com.labteto.dshmobile.local.send.LocalPreparedSend
import kotlinx.coroutines.Job

/**
 * Transitional low-level Work turn start bridge.
 *
 * WorkFeature owns product admission, inbox and regeneration eligibility. This bridge only starts
 * an already-admitted Work turn until the Work Agent loop leaves the composition root.
 */
internal interface LocalWorkTurnPort {
    fun startPrepared(
        prepared: LocalPreparedSend,
        sessionLease: LocalSessionRuntimeLease,
    ): Job

    fun startRegeneration(messageId: String): Job
}

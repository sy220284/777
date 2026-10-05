package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.runtime.LocalSessionRuntimeLease
import com.labteto.dshmobile.local.send.LocalPreparedSend
import kotlinx.coroutines.Job

/**
 * Transitional low-level Work turn start bridge.
 *
 * WorkFeature owns product send admission and durable inbox semantics. This bridge only starts the
 * already-admitted first turn until the Work Agent loop itself leaves the composition root.
 */
internal interface LocalWorkTurnPort {
    fun startPrepared(
        prepared: LocalPreparedSend,
        sessionLease: LocalSessionRuntimeLease,
    ): Job

    fun regenerateReply(messageId: String): Boolean
}

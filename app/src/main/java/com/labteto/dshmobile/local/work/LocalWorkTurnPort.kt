package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.send.LocalPreparedSend
import com.labteto.dshmobile.local.send.LocalSendResult

/**
 * Transitional low-level Work turn bridge.
 *
 * WorkExecutionPort is owned by WorkFeature. This bridge exists only while the remaining foreground
 * admission/binding/run implementation is migrated out of the composition root.
 */
internal interface LocalWorkTurnPort {
    fun sendPrepared(prepared: LocalPreparedSend): LocalSendResult

    fun regenerateReply(messageId: String): Boolean
}

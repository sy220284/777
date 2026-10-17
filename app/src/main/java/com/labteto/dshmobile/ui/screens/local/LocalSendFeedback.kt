package com.labteto.dshmobile.ui.screens.local

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.send.LocalSendRejectReason

/** Only a committed session can move the selected tab. */
@Suppress("UNUSED_PARAMETER")
internal fun localHarnessDrawerUsageMode(
    current: LocalUsageMode,
    pending: LocalUsageMode?,
): LocalUsageMode = current
@Composable
internal fun localSendRejectMessage(reason: LocalSendRejectReason, limit: Int?): String = when (reason) {
    LocalSendRejectReason.EMPTY -> stringResource(R.string.local_send_rejected_empty)
    LocalSendRejectReason.LOADING -> stringResource(R.string.local_send_rejected_loading)
    LocalSendRejectReason.UNCONFIGURED -> stringResource(R.string.local_send_rejected_unconfigured)
    LocalSendRejectReason.SESSION_TRANSITION -> stringResource(R.string.local_send_rejected_session_transition)
    LocalSendRejectReason.QUEUE_FULL -> stringResource(
        R.string.local_send_rejected_queue_full,
        limit ?: 0,
    )
    LocalSendRejectReason.QUEUE_UNAVAILABLE -> stringResource(R.string.local_send_rejected_queue_unavailable)
}

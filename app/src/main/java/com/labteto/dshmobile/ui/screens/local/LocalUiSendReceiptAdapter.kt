package com.labteto.dshmobile.ui.screens.local

import com.labteto.dshmobile.local.send.LocalSendRejectReason
import com.labteto.dshmobile.local.send.LocalSendResult
import com.labteto.dshmobile.uicontract.UiCommandReceipt
import com.labteto.dshmobile.uicontract.UiCommandRejection

/** Temporary V6 result adapter. No state or business admission is implemented here. */
internal fun UiCommandReceipt.toLegacySendResult(): LocalSendResult = when (this) {
    UiCommandReceipt.Accepted -> LocalSendResult.Started
    UiCommandReceipt.Queued -> LocalSendResult.Queued
    is UiCommandReceipt.Rejected -> LocalSendResult.rejected(when (reason) {
        UiCommandRejection.EMPTY_INPUT -> LocalSendRejectReason.EMPTY
        UiCommandRejection.LOADING -> LocalSendRejectReason.LOADING
        UiCommandRejection.UNCONFIGURED -> LocalSendRejectReason.UNCONFIGURED
        UiCommandRejection.STALE_IDENTITY, UiCommandRejection.SESSION_TRANSITION -> LocalSendRejectReason.SESSION_TRANSITION
        UiCommandRejection.QUEUE_FULL -> LocalSendRejectReason.QUEUE_FULL
        UiCommandRejection.QUEUE_UNAVAILABLE -> LocalSendRejectReason.QUEUE_UNAVAILABLE
        UiCommandRejection.APPROVAL_EXPIRED, UiCommandRejection.UNSUPPORTED -> error("Non-send rejection in text-send receipt")
    }, limit)
    is UiCommandReceipt.Failed, UiCommandReceipt.OutcomeUnknown -> error("Non-admission receipt in text-send port")
}

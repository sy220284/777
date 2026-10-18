package com.labteto.dshmobile.uipack.zhaoxi.scenes

import androidx.compose.runtime.Composable
import com.labteto.dshmobile.uicontract.*
import com.labteto.dshmobile.uipack.zhaoxi.components.*

internal fun UiCommandReceipt.admitted(): Boolean = this == UiCommandReceipt.Accepted || this == UiCommandReceipt.Queued
internal fun UiCommandReceipt.userHint(): String = when (this) {
    UiCommandReceipt.Accepted -> "请求已受理"
    UiCommandReceipt.Queued -> "请求已排队"
    is UiCommandReceipt.Rejected -> rejectionHint(reason)
    is UiCommandReceipt.Failed -> message
    UiCommandReceipt.OutcomeUnknown -> "结果尚未确认，请先查看当前状态，避免重复提交"
    is UiCommandReceipt.MessageEdited -> "编辑结果：$result"
    UiCommandReceipt.NotAdmitted -> "操作未被受理"
}
@Composable
internal fun CommandFeedback(receipt: UiCommandReceipt?) {
    receipt?.let { ZxBanner(it.userHint(), if (it.admitted()) ZxTone.Info else ZxTone.Warn) }
}

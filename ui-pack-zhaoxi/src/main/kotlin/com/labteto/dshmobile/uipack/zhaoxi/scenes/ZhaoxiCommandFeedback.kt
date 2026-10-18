package com.labteto.dshmobile.uipack.zhaoxi.scenes

import androidx.compose.runtime.Composable
import com.labteto.dshmobile.uicontract.*
import com.labteto.dshmobile.uipack.zhaoxi.components.*

internal fun UiCommandReceipt.admitted(): Boolean = this == UiCommandReceipt.Accepted || this == UiCommandReceipt.Queued
internal fun UiCommandReceipt.userHint(): String = when (this) {
    UiCommandReceipt.Accepted -> "请求已受理"
    UiCommandReceipt.Queued -> "请求已排队"
    is UiCommandReceipt.Rejected -> rejectionHint(reason)
    is UiCommandReceipt.Failed -> message.takeIf(String::isNotBlank) ?: "操作失败，请重试"
    UiCommandReceipt.OutcomeUnknown -> "结果尚未确认，请先查看当前状态，避免重复提交"
    is UiCommandReceipt.MessageEdited -> when (result) {
        UiMessageEditOutcome.SENT -> "已提交修改并开始回复"
        UiMessageEditOutcome.COMMITTED_NOT_STARTED -> "修改已保存，暂未开始回复"
        UiMessageEditOutcome.BUSY -> "当前会话正在执行，请稍后重试"
        UiMessageEditOutcome.UNAVAILABLE -> "此时无法修改消息"
        UiMessageEditOutcome.WRONG_MODE -> "当前会话模式不支持该操作"
        UiMessageEditOutcome.UNCONFIGURED -> "请先完成模型配置"
        UiMessageEditOutcome.HISTORY_UNAVAILABLE -> "历史消息暂不可用，请刷新"
        UiMessageEditOutcome.MESSAGE_MISSING -> "消息已不存在，请返回刷新"
        UiMessageEditOutcome.EMPTY -> "请输入修改后的消息"
        UiMessageEditOutcome.UNCHANGED -> "内容未改变"
        UiMessageEditOutcome.FAILED -> "修改失败，请重试"
    }
    UiCommandReceipt.NotAdmitted -> "操作未被受理"
}
/** An editor closes only on an admitted edit, never on BUSY/FAILED or an uncertain result. */
internal fun UiCommandReceipt.exitsMessageEditor(): Boolean = when (this) {
    UiCommandReceipt.Accepted, UiCommandReceipt.Queued -> true
    is UiCommandReceipt.MessageEdited ->
        result == UiMessageEditOutcome.SENT || result == UiMessageEditOutcome.COMMITTED_NOT_STARTED
    else -> false
}

/** Message edits may commit without starting a reply; that is not a rejection. */
internal fun UiCommandReceipt.positiveFeedback(): Boolean =
    admitted() || (this is UiCommandReceipt.MessageEdited &&
        (result == UiMessageEditOutcome.SENT || result == UiMessageEditOutcome.COMMITTED_NOT_STARTED))

@Composable
internal fun CommandFeedback(receipt: UiCommandReceipt?) {
    receipt?.let { ZxBanner(it.userHint(), if (it.positiveFeedback()) ZxTone.Info else ZxTone.Warn) }
}

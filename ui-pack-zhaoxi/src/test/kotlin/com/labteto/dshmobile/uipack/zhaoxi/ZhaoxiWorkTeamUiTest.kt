package com.labteto.dshmobile.uipack.zhaoxi

import com.labteto.dshmobile.uicontract.UiCommandReceipt
import com.labteto.dshmobile.uicontract.UiCommandRejection
import com.labteto.dshmobile.uicontract.UiWorkTeamResult
import com.labteto.dshmobile.uipack.zhaoxi.scenes.zxWorkTeamResultLabel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZhaoxiWorkTeamUiTest {
    @Test fun acceptedTeamCommandIsNotLabeledCompleted() {
        val result = zxWorkTeamResultLabel(
            UiWorkTeamResult(UiCommandReceipt.Accepted, "请求进入成员收件箱"), "成员消息")
        assertTrue(result.contains("已受理"))
        assertFalse(result.contains("已完成"))
    }

    @Test fun queuedTeamCommandRemainsQueued() {
        val result = zxWorkTeamResultLabel(
            UiWorkTeamResult(UiCommandReceipt.Queued, "稍后执行"), "停止团队")
        assertTrue(result.contains("已排队"))
        assertFalse(result.contains("已经停止"))
    }

    @Test fun staleMemberCommandDoesNotClaimSuccess() {
        val result = zxWorkTeamResultLabel(
            UiWorkTeamResult(UiCommandReceipt.Rejected(UiCommandRejection.STALE_IDENTITY),
                "会话已改变"), "停止成员")
        assertTrue(result.contains("会话已改变"))
        assertFalse(result.contains("已受理"))
    }
}

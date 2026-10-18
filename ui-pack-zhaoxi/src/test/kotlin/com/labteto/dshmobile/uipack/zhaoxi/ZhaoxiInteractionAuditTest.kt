package com.labteto.dshmobile.uipack.zhaoxi

import com.labteto.dshmobile.uicontract.ToolsUiReceipt
import com.labteto.dshmobile.uicontract.UiMcpHealth
import com.labteto.dshmobile.uicontract.UiToolError
import com.labteto.dshmobile.uipack.zhaoxi.scenes.ZxRuntimeLimitField
import com.labteto.dshmobile.uipack.zhaoxi.scenes.zxMcpHealthText
import com.labteto.dshmobile.uipack.zhaoxi.scenes.zxQuietHoursError
import com.labteto.dshmobile.uipack.zhaoxi.scenes.zxRuntimeLimitError
import com.labteto.dshmobile.uipack.zhaoxi.scenes.zxTemperatureCeilingError
import com.labteto.dshmobile.uipack.zhaoxi.scenes.zxTimeOfDayMinutes
import com.labteto.dshmobile.uipack.zhaoxi.scenes.zxToolReceiptText
import com.labteto.dshmobile.uicontract.AutomationUiReceipt
import com.labteto.dshmobile.uicontract.UiAutomationRejection
import com.labteto.dshmobile.uicontract.UiPersonaStoryReceipt
import com.labteto.dshmobile.uicontract.UiStorySessionSync
import com.labteto.dshmobile.uicontract.UiPersonaPortraitReceipt
import com.labteto.dshmobile.uipack.zhaoxi.scenes.zxAutomationReceiptText
import com.labteto.dshmobile.uipack.zhaoxi.scenes.zxStoryFeedback
import com.labteto.dshmobile.uipack.zhaoxi.scenes.zxPortraitFeedback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class ZhaoxiInteractionAuditTest {
    @Test fun runtimeLimitsMatchExistingOwnersRatherThanSilentlyClamping() {
        assertNull(zxRuntimeLimitError(ZxRuntimeLimitField.MAIN_STEPS, "4"))
        assertNull(zxRuntimeLimitError(ZxRuntimeLimitField.MAIN_STEPS, "512"))
        assertNotNull(zxRuntimeLimitError(ZxRuntimeLimitField.MAIN_STEPS, "3"))
        assertNotNull(zxRuntimeLimitError(ZxRuntimeLimitField.MAIN_STEPS, "513"))
        assertNull(zxRuntimeLimitError(ZxRuntimeLimitField.SUBAGENT_STEPS, "1"))
        assertNotNull(zxRuntimeLimitError(ZxRuntimeLimitField.SUBAGENT_STEPS, "-1"))
        assertNull(zxRuntimeLimitError(ZxRuntimeLimitField.MODEL_ATTEMPTS, "5"))
        assertNotNull(zxRuntimeLimitError(ZxRuntimeLimitField.MODEL_ATTEMPTS, "6"))
        assertNotNull(zxRuntimeLimitError(ZxRuntimeLimitField.MODEL_ATTEMPTS, "garbage"))
    }

    @Test fun modelTemperatureRejectsNonFiniteAndOutOfRangeValues() {
        assertNull(zxTemperatureCeilingError("1.0"))
        assertNull(zxTemperatureCeilingError("2.0"))
        assertNotNull(zxTemperatureCeilingError("0.5"))
        assertNotNull(zxTemperatureCeilingError("2.1"))
        assertNotNull(zxTemperatureCeilingError("NaN"))
        assertNotNull(zxTemperatureCeilingError("Infinity"))
    }

    @Test fun quietTimeAcceptsOvernightButRejectsAmbiguousOrMalformedWindows() {
        assertNull(zxQuietHoursError("22:00", "07:00"))
        assertNull(zxQuietHoursError("09:00", "17:00"))
        assertNotNull(zxQuietHoursError("00:00", "00:00"))
        assertNotNull(zxQuietHoursError("12:60", "13:00"))
        assertNotNull(zxQuietHoursError("25:00", "13:00"))
        assertEquals(23 * 60 + 59, zxTimeOfDayMinutes("23:59"))
    }

    @Test fun mcpReceiptsDescribeOperationsWithoutLeakingTechnicalNames() {
        assertEquals("设置已保存", zxToolReceiptText(ToolsUiReceipt.Applied()))
        assertEquals("无法解析服务器地址", zxToolReceiptText(ToolsUiReceipt.Failed(UiToolError.DNS)))
        assertEquals("缺少连接权限，请检查授权", zxToolReceiptText(ToolsUiReceipt.Failed(UiToolError.PERMISSION)))
        assertEquals("连接已响应", zxMcpHealthText(UiMcpHealth.RESPONDED))
        assertEquals("尚未建立连接", zxMcpHealthText(UiMcpHealth.NOT_CONNECTED))
    }
    @Test fun automationReceiptNeverClaimsRunCompletedWhenOnlyAdmitted() {
        assertEquals("运行请求已提交",
            zxAutomationReceiptText("立即运行", AutomationUiReceipt.Applied("task-a")))
        assertEquals("任务已更新，请重新载入",
            zxAutomationReceiptText("保存", AutomationUiReceipt.Rejected(UiAutomationRejection.STALE_TASK)))
        assertEquals("调度操作未被接受",
            zxAutomationReceiptText("暂停", AutomationUiReceipt.Rejected(UiAutomationRejection.OWNER_REJECTED)))
    }

    @Test fun storySaveDistinguishesDeferredSessionSyncAndPortraitWarnings() {
        assertEquals("故事已保存，当前会话稍后同步",
            UiPersonaStoryReceipt.Saved(UiStorySessionSync.DEFERRED).zxStoryFeedback())
        assertEquals("画像已更新",
            UiPersonaPortraitReceipt.Updated("persona-1").zxPortraitFeedback())
        assertNotNull(UiPersonaPortraitReceipt.Updated("persona-1", "old file").zxPortraitFeedback()
            .takeIf { it.contains("old file") })
    }

}

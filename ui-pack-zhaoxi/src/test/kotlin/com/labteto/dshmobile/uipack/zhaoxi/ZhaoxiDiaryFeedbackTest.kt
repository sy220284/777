package com.labteto.dshmobile.uipack.zhaoxi

import com.labteto.dshmobile.uicontract.UiDiaryReceipt
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxTone
import com.labteto.dshmobile.uipack.zhaoxi.scenes.zxDiaryReceiptFeedback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ZhaoxiDiaryFeedbackTest {
    @Test fun acceptedAndStaleDiaryChangesHaveDifferentUserFeedback() {
        assertEquals("操作已保存" to ZxTone.Info,
            zxDiaryReceiptFeedback(UiDiaryReceipt.Applied))
        val (staleText, staleTone) = zxDiaryReceiptFeedback(UiDiaryReceipt.Stale)
        assertEquals(ZxTone.Warn, staleTone)
        assertTrue(staleText.contains("重新载入"))
    }

    @Test fun failuresAreDisplayedAsErrorsRatherThanSuccessfulMessages() {
        assertEquals("磁盘空间不足" to ZxTone.Err,
            zxDiaryReceiptFeedback(UiDiaryReceipt.Failed("磁盘空间不足")))
        assertEquals("操作失败，请重试" to ZxTone.Err,
            zxDiaryReceiptFeedback(UiDiaryReceipt.Failed(null)))
        assertEquals("操作失败，请重试" to ZxTone.Err,
            zxDiaryReceiptFeedback(UiDiaryReceipt.Failed("   ")))
    }
}

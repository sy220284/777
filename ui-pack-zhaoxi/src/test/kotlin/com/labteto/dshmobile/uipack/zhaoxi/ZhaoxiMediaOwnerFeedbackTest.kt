package com.labteto.dshmobile.uipack.zhaoxi

import com.labteto.dshmobile.uicontract.UiPersonaPortraitReceipt
import com.labteto.dshmobile.uipack.zhaoxi.scenes.zxPortraitImportFeedback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The renderer must never confuse a missing persona or failed import with an updated portrait. */
class ZhaoxiMediaOwnerFeedbackTest {
    @Test fun confirmedImportIsTheOnlyNormalSuccess() {
        val feedback = zxPortraitImportFeedback(UiPersonaPortraitReceipt.Updated("gallery-1"))
        assertEquals("人物画像已更新", feedback.message)
        assertFalse(feedback.failure)
        assertFalse(feedback.warning)
    }

    @Test fun committedImportWithCleanupWarningIsNotReportedAsPureSuccess() {
        val feedback = zxPortraitImportFeedback(
            UiPersonaPortraitReceipt.Updated("gallery-1", "旧文件仍在设备上")
        )
        assertFalse(feedback.failure)
        assertTrue(feedback.warning)
        assertTrue(feedback.message.contains("清理旧图片"))
    }

    @Test fun missingAndRejectedImportCannotShowSuccess() {
        val missing = zxPortraitImportFeedback(UiPersonaPortraitReceipt.Missing)
        assertTrue(missing.failure)
        assertFalse(missing.message.contains("已更新"))
        val failed = zxPortraitImportFeedback(UiPersonaPortraitReceipt.Failed("文件过大"))
        assertTrue(failed.failure)
        assertTrue(failed.message.contains("文件过大"))
        val empty = zxPortraitImportFeedback(UiPersonaPortraitReceipt.Failed(null))
        assertTrue(empty.failure)
        assertFalse(empty.message.isBlank())
    }
}

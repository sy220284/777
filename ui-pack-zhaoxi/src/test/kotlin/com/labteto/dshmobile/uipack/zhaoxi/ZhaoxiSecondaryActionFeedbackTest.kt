package com.labteto.dshmobile.uipack.zhaoxi

import com.labteto.dshmobile.uicontract.UiPersonaGalleryDeletionReceipt
import com.labteto.dshmobile.uicontract.UiStorySessionSync
import com.labteto.dshmobile.uipack.zhaoxi.scenes.zxGalleryDeletionFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Keep user-visible archive messages independent of Kotlin sealed-class formatting. */
class ZhaoxiSecondaryActionFeedbackTest {
    @Test fun missingArchiveAndGenericErrorsHaveReadableFeedback() {
        assertEquals("人物已经不存在", zxGalleryDeletionFailure(UiPersonaGalleryDeletionReceipt.Missing))
        assertEquals("删除失败", zxGalleryDeletionFailure(UiPersonaGalleryDeletionReceipt.Failed(null)))
        assertEquals("删除失败", zxGalleryDeletionFailure(UiPersonaGalleryDeletionReceipt.Failed("")))
        assertEquals("磁盘空间不足", zxGalleryDeletionFailure(UiPersonaGalleryDeletionReceipt.Failed("磁盘空间不足")))
    }

    @Test fun successfulDeletionIsNotRenderedAsAnInternalReceipt() {
        val feedback = zxGalleryDeletionFailure(
            UiPersonaGalleryDeletionReceipt.Deleted(UiStorySessionSync.APPLIED))
        assertEquals("人物已删除", feedback)
        assertFalse(feedback.contains("Deleted"))
    }
}

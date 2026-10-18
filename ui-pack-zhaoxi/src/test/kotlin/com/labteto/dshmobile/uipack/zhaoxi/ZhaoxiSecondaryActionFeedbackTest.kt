package com.labteto.dshmobile.uipack.zhaoxi

import com.labteto.dshmobile.uicontract.UiPersonaGalleryDeletionReceipt
import com.labteto.dshmobile.uicontract.UiStorySessionSync
import com.labteto.dshmobile.uipack.zhaoxi.scenes.zxGalleryDeletionFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import com.labteto.dshmobile.uicontract.UiPersonaImportReceipt
import com.labteto.dshmobile.uicontract.UiAttachmentReceipt
import com.labteto.dshmobile.uicontract.UiCommandRejection
import com.labteto.dshmobile.uipack.zhaoxi.scenes.zxImportFeedback
import com.labteto.dshmobile.uipack.zhaoxi.scenes.zxAttachmentFeedback
import com.labteto.dshmobile.uipack.zhaoxi.scenes.zxGroupMemberCountError
import com.labteto.dshmobile.uipack.zhaoxi.scenes.zxAnnouncementLengthError
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
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
    @Test fun personaImportOnlyClaimsSuccessForCommittedArchives() {
        assertEquals("人物已导入", UiPersonaImportReceipt.Imported("persona-1").zxImportFeedback())
        assertEquals("导入状态不确定，请先刷新人物图集",
            UiPersonaImportReceipt.Failed(null).zxImportFeedback())
        assertEquals("存储不足", UiPersonaImportReceipt.Failed("存储不足").zxImportFeedback())
    }

    @Test fun attachmentImportReportsRevokedPermissionAndRejectionSafely() {
        assertEquals("文件读取权限已失效，请重新选择",
            UiAttachmentReceipt.GrantRevoked.zxAttachmentFeedback())
        assertEquals("附件导入失败", UiAttachmentReceipt.Failed(null).zxAttachmentFeedback())
        assertEquals("文件已损坏", UiAttachmentReceipt.Failed("文件已损坏").zxAttachmentFeedback())
        val rejected = UiAttachmentReceipt.Rejected(UiCommandRejection.UNSUPPORTED).zxAttachmentFeedback()
        assertFalse(rejected.contains("UiAttachmentReceipt"))
    }

    @Test fun groupMemberSelectionUsesExactOwnerMinMax() {
        assertNotNull(zxGroupMemberCountError(1, 2, 6))
        assertNull(zxGroupMemberCountError(2, 2, 6))
        assertNull(zxGroupMemberCountError(6, 2, 6))
        assertNotNull(zxGroupMemberCountError(7, 2, 6))
    }

    @Test fun groupAnnouncementRespectsOwnerCharacterLimit() {
        assertNull(zxAnnouncementLengthError(0, 2000))
        assertNull(zxAnnouncementLengthError(2000, 2000))
        assertEquals("公告不能超过 2000 字", zxAnnouncementLengthError(2001, 2000))
    }

}

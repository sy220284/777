package com.labteto.dshmobile.ui.screens.local

import com.labteto.dshmobile.uicontract.UiDocumentNode
import org.junit.Assert.assertEquals
import org.junit.Test

class NativeV6DocumentPresentationTest {
    @Test fun aDraftActionCannotMasqueradeAsConfirmingOrSubmittingTheForm() {
        val draft = com.labteto.dshmobile.uicontract.UiDocumentAction.SendUserInput(
            "send", "", listOf("city"), submit = false,
        )
        val direct = draft.copy(submit = true)
        assertEquals("放入输入框（仍需点击发送）", nativeV6ActionLabel("确认表单", draft))
        assertEquals("确认并发送表单", nativeV6ActionLabel("确认并发送表单", direct))
    }

    @Test fun submittedFormReportsTheRealOwnerAdmissionOrRejection() {
        val accepted = com.labteto.dshmobile.uicontract.UiDocumentReceipt.OwnerReceipt(
            com.labteto.dshmobile.uicontract.UiCommandReceipt.Accepted)
        val queued = com.labteto.dshmobile.uicontract.UiDocumentReceipt.OwnerReceipt(
            com.labteto.dshmobile.uicontract.UiCommandReceipt.Queued)
        val rejected = com.labteto.dshmobile.uicontract.UiDocumentReceipt.OwnerReceipt(
            com.labteto.dshmobile.uicontract.UiCommandReceipt.Rejected(
                com.labteto.dshmobile.uicontract.UiCommandRejection.STALE_IDENTITY))
        assertEquals("表单已提交，等待会话处理", nativeV6DocumentReceiptMessage(accepted))
        assertEquals("表单已加入工作队列，稍后处理", nativeV6DocumentReceiptMessage(queued))
        org.junit.Assert.assertTrue(nativeV6DocumentReceiptMessage(rejected).contains("已失效"))
    }

    @Test fun onlyAcceptedOrQueuedSubmissionLocksTheFormAgainstRepeatedTap() {
        val receipt = com.labteto.dshmobile.uicontract.UiDocumentReceipt.OwnerReceipt(
            com.labteto.dshmobile.uicontract.UiCommandReceipt.Accepted,
        )
        val queued = com.labteto.dshmobile.uicontract.UiDocumentReceipt.OwnerReceipt(
            com.labteto.dshmobile.uicontract.UiCommandReceipt.Queued,
        )
        val rejected = com.labteto.dshmobile.uicontract.UiDocumentReceipt.OwnerReceipt(
            com.labteto.dshmobile.uicontract.UiCommandReceipt.Rejected(
                com.labteto.dshmobile.uicontract.UiCommandRejection.LOADING,
            ),
        )
        org.junit.Assert.assertTrue(nativeV6SubmittedToOwner(receipt))
        org.junit.Assert.assertTrue(nativeV6SubmittedToOwner(queued))
        org.junit.Assert.assertFalse(nativeV6SubmittedToOwner(rejected))
        org.junit.Assert.assertFalse(nativeV6SubmittedToOwner(
            com.labteto.dshmobile.uicontract.UiDocumentReceipt.DraftPrepared,
        ))
    }

    @Test fun singleSelectRendersCurrentChoiceWithoutPretendingToBeMultipleChoice() {
        val select = UiDocumentNode.Select("city", "目的地", listOf("成都", "上海"), "成都")
        assertEquals("成都", nativeV6SelectLabel(select, emptyMap()))
        assertEquals("上海", nativeV6SelectLabel(select, mapOf("city" to "上海")))
        assertEquals("请选择", nativeV6SelectLabel(select, mapOf("city" to "无效选项")))
    }
}

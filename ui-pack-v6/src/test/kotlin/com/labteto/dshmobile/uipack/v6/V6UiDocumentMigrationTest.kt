package com.labteto.dshmobile.uipack.v6

import com.labteto.dshmobile.uicontract.MediaUiPort
import com.labteto.dshmobile.uicontract.UiDocumentMediaKind
import com.labteto.dshmobile.uicontract.UiDocumentNode
import com.labteto.dshmobile.uicontract.UiDocumentReceipt
import com.labteto.dshmobile.uicontract.UiMediaAsset
import com.labteto.dshmobile.uicontract.UiCommandReceipt
import com.labteto.dshmobile.uicontract.UiCommandRejection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real media-scope and command receipt semantics for the V6 document renderer. */
class V6UiDocumentMigrationTest {
    private val mediaNode = UiDocumentNode.Media(
        "preview", "private-media-id", UiDocumentMediaKind.IMAGE, "源会话照片")

    @Test fun scopedMediaResolutionMustNotUseUnscopedAssetLookup() {
        var requestedSession: String? = null
        var requestedId: String? = null
        val owner = java.lang.reflect.Proxy.newProxyInstance(
            MediaUiPort::class.java.classLoader, arrayOf(MediaUiPort::class.java)
        ) { _, method, args ->
            when (method.name) {
                "assetForSession" -> {
                    requestedId = args?.get(0) as String
                    requestedSession = args[1] as String
                    UiMediaAsset("private-media-id", "/owner/verified/image.png", null, null,
                        UiDocumentMediaKind.IMAGE)
                }
                "asset" -> error("Unscoped media lookup is forbidden in V6 documents")
                else -> error("Unexpected owner call: ${method.name}")
            }
        } as MediaUiPort
        val result = resolveV6DocumentMediaAsset(owner, mediaNode, "session-42")
        assertEquals("session-42", requestedSession)
        assertEquals("private-media-id", requestedId)
        assertEquals(UiDocumentMediaKind.IMAGE, result?.kind)
    }

    @Test fun wrongKindAndMissingOwnerDoNotDecodeMedia() {
        assertNull(resolveV6DocumentMediaAsset(null, mediaNode, "session-42"))
        val owner = java.lang.reflect.Proxy.newProxyInstance(
            MediaUiPort::class.java.classLoader, arrayOf(MediaUiPort::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "assetForSession" -> UiMediaAsset("private-media-id",
                    "/owner/verified/mismatch.mp4", null, null, UiDocumentMediaKind.VIDEO)
                else -> error("Unexpected owner call")
            }
        } as MediaUiPort
        assertNull(resolveV6DocumentMediaAsset(owner, mediaNode, "session-42"))
    }

    @Test fun publishedDoesNotClaimModelExecutionCompleted() {
        assertTrue(describeV6DocumentReceipt(UiDocumentReceipt.Published("published-card"))
            .contains("文档发布"))
        assertTrue(describeV6DocumentReceipt(UiDocumentReceipt.DraftPrepared)
            .contains("手动发送"))
        assertFalse(describeV6DocumentReceipt(UiDocumentReceipt.OwnerReceipt(
            UiCommandReceipt.OutcomeUnknown)).contains("已完成"))
    }

    @Test fun ownerRejectionAndFallbackRemainVisibleAsFailure() {
        val rejected = describeV6DocumentReceipt(
            UiDocumentReceipt.Rejected(UiCommandRejection.STALE_IDENTITY))
        assertTrue(rejected.contains("STALE_IDENTITY"))
        assertTrue(describeV6DocumentReceipt(
            UiDocumentReceipt.InvalidFields(setOf("amount"))).contains("amount"))
        assertTrue(describeV6DocumentReceipt(
            UiDocumentReceipt.Fallback("raw text", "invalid layout")).contains("raw text"))
    }
}

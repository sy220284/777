package com.labteto.dshmobile.ui.screens.local

import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.labteto.dshmobile.ui.theme.DshTheme
import com.labteto.dshmobile.uicontract.UiDocument
import com.labteto.dshmobile.uicontract.UiDocumentSource
import com.labteto.dshmobile.uicontract.UiDocumentSnapshot
import com.labteto.dshmobile.uicontract.UiDocumentProvenance
import com.labteto.dshmobile.uicontract.UiDocumentOrigin
import org.junit.Rule
import org.junit.Test

class NativeV6DocumentModalHostTest {
    @get:Rule val compose = createComposeRule()

    @Test fun opensOnDemandAndReturnsToCompactMessageAfterClosing() {
        val document = UiDocument(
            1, "reply-ui:msg",
            UiDocumentSource("session", "msg", 0L, null, null, false),
            0L, "选择回复", emptyList(), emptyList(), "备用文本",
        )
        val snapshot = UiDocumentSnapshot(
            document,
            UiDocumentProvenance(null, "", false, UiDocumentOrigin.LOCAL),
            emptyMap(),
        )
        compose.setContent {
            DshTheme {
                NativeV6DocumentModalHost("msg", listOf(snapshot)) { _, _ ->
                    Text("交互表单内容")
                }
            }
        }
        compose.onNodeWithText("交互表单内容").assertDoesNotExist()
        compose.onNodeWithTag("reply_document_open:reply-ui:msg").performClick()
        compose.onNodeWithText("交互表单内容").assertExists()
        compose.onNodeWithText("关闭").performClick()
        compose.onNodeWithText("交互表单内容").assertDoesNotExist()
        compose.onNodeWithTag("reply_document_open:reply-ui:msg").assertExists()
    }
}

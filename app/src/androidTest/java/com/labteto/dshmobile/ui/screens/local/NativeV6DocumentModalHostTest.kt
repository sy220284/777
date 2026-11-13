package com.labteto.dshmobile.ui.screens.local

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.assertCountEquals
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
        compose.onNodeWithTag("reply_document_open:msg").performClick()
        compose.onNodeWithText("交互表单内容").assertExists()
        compose.onNodeWithText("关闭").performClick()
        compose.onNodeWithText("交互表单内容").assertDoesNotExist()
        compose.onNodeWithTag("reply_document_open:msg").assertExists()
    }
    @Test fun multipleDocumentsShareOneLauncherAndSwitchInsideSheet() {
        fun card(id: String, title: String) = UiDocumentSnapshot(
            UiDocument(1, id,
                UiDocumentSource("session", "msg", 0L, null, null, false),
                0L, title, emptyList(), emptyList(), "备用文本"),
            UiDocumentProvenance(null, "", false, UiDocumentOrigin.LOCAL),
            emptyMap(),
        )
        compose.setContent {
            DshTheme {
                NativeV6DocumentModalHost("msg", listOf(
                    card("reply-ui:one", "第一份"),
                    card("reply-ui:two", "第二份"),
                )) { snapshot, _ ->
                    Text("当前内容：${snapshot.document.title}")
                }
            }
        }
        compose.onAllNodesWithTag("reply_document_open:msg").assertCountEquals(1)
        compose.onNodeWithTag("reply_document_open:msg").performClick()
        compose.onNodeWithText("当前内容：第二份").assertExists()
        compose.onNodeWithText("第一份").performClick()
        compose.onNodeWithText("当前内容：第一份").assertExists()
        compose.onNodeWithText("关闭").performClick()
        compose.onNodeWithText("当前内容：第一份").assertDoesNotExist()
    }

    @Test fun deletingSelectedDocumentNeverSilentlyOpensAnother() {
        fun snapshot(id: String) = UiDocumentSnapshot(
            UiDocument(1, id,
                UiDocumentSource("session", "msg", 0L, null, null, false),
                0L, id, emptyList(), emptyList(), ""),
            UiDocumentProvenance(null, "", false, UiDocumentOrigin.LOCAL),
            emptyMap(),
        )
        val first = snapshot("reply-ui:first")
        val second = snapshot("reply-ui:second")
        var documents by mutableStateOf(listOf(first, second))
        compose.setContent {
            DshTheme {
                NativeV6DocumentModalHost("msg", documents) { selected, _ ->
                    Text("正在查看：${selected.document.documentId}")
                }
            }
        }
        compose.onNodeWithTag("reply_document_open:msg").performClick()
        compose.onNodeWithText("正在查看：reply-ui:second").assertExists()
        compose.runOnIdle { documents = listOf(first) }
        compose.onNodeWithText("正在查看：reply-ui:first").assertDoesNotExist()
        compose.onNodeWithText("正在查看：reply-ui:second").assertDoesNotExist()
        compose.onNodeWithTag("reply_document_open:msg").assertExists()
    }

}

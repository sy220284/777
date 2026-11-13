package com.labteto.dshmobile.ui.screens.local

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.ui.theme.DshTheme
import com.labteto.dshmobile.ui.theme.ThemePreference
import com.labteto.dshmobile.uicontract.UiDocument
import com.labteto.dshmobile.uicontract.UiDocumentNode
import com.labteto.dshmobile.uicontract.UiDocumentProvenance
import com.labteto.dshmobile.uicontract.UiDocumentSnapshot
import com.labteto.dshmobile.uicontract.UiDocumentSource
import com.shenyan.designsystem.foundation.LocalSyColors
import java.io.File
import org.junit.Rule
import org.junit.Test

class LocalReplyDocumentInlineVisualTest {
    @get:Rule val compose = createComposeRule()
    private val doc = UiDocument(
        schemaVersion = 1, documentId = "document", revision = 1,
        source = UiDocumentSource("session", "reply", 0L, null, null),
        title = "项目分析报告",
        nodes = listOf(UiDocumentNode.Text("first", "已完成代码和界面检查。")),
        actions = emptyList(), fallbackText = "可用历史报告",
    )
    private val snapshot = UiDocumentSnapshot(
        document = doc,
        provenance = UiDocumentProvenance(null, "local", false),
        actions = emptyMap(),
    )

    @Test fun replyShowsReadablePreviewAndOpensOriginalFullDetails() {
        compose.setContent {
            DshTheme(preference = ThemePreference.LIGHT) {
                Column(Modifier.width(320.dp).background(LocalSyColors.current.canvas).padding(12.dp)) {
                    Text("已生成分析报告：")
                    NativeV6DocumentModalHost("reply", listOf(snapshot)) { value, _ ->
                        Text("详细内容：${value.document.fallbackText}")
                    }
                }
            }
        }
        compose.onNodeWithText("项目分析报告").assertIsDisplayed()
        compose.onNodeWithText("已完成代码和界面检查。").assertIsDisplayed()
        compose.onNodeWithText("查看回复卡片").assertDoesNotExist()
        saveScreenshot("inline-reply-document-320dp")
        compose.onNodeWithTag("reply_document_open:reply").performClick()
        compose.onNodeWithText("详细内容：可用历史报告").assertIsDisplayed()
    }

    private fun saveScreenshot(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(context.getExternalFilesDir(null), "work-timeline-review")
        check(dir.exists() || dir.mkdirs())
        File(dir, "$name.png").outputStream().use {
            check(compose.onRoot().captureToImage().asAndroidBitmap()
                .compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }
}

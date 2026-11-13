package com.labteto.dshmobile.ui.screens.local

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.labteto.dshmobile.ui.theme.DshTheme
import com.labteto.dshmobile.uicontract.UiDocumentReceipt
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class NativeV6DocumentGenerationDialogTest {
    @get:Rule val compose = createComposeRule()

    @Test fun openingAndCancelingDoesNotChargeOrCallModel() {
        val requests = AtomicInteger(0)
        var visible by mutableStateOf(true)
        compose.setContent {
            DshTheme {
                if (visible) NativeV6DocumentGenerationDialog(
                    messageId = "reply-1",
                    onDismiss = { visible = false },
                    onGenerate = { _, _ ->
                        requests.incrementAndGet()
                        UiDocumentReceipt.Published("reply-ui:reply-1")
                    },
                )
            }
        }
        compose.onNodeWithText("生成会额外请求一次模型并产生相应消耗，仅在确认后执行。").assertExists()
        compose.runOnIdle { assertEquals(0, requests.get()) }
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { assertEquals(0, requests.get()) }
        compose.onNodeWithText("确认生成").assertDoesNotExist()
    }

    @Test fun explicitConfirmationGeneratesOnceAndClosesAfterPublishedReceipt() {
        val requests = AtomicInteger(0)
        var visible by mutableStateOf(true)
        compose.setContent {
            DshTheme {
                if (visible) NativeV6DocumentGenerationDialog(
                    messageId = "reply-2",
                    onDismiss = { visible = false },
                    onGenerate = { id, prompt ->
                        assertEquals("reply-2", id)
                        assertEquals("把这条回复整理成可交互的文档", prompt)
                        requests.incrementAndGet()
                        UiDocumentReceipt.Published("reply-ui:reply-2")
                    },
                )
            }
        }
        compose.onNodeWithText("确认生成").performClick()
        compose.waitUntil(5_000) { requests.get() == 1 && !visible }
        compose.onNodeWithText("确认生成").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, requests.get()) }
    }
}

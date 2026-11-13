package com.labteto.dshmobile.ui.screens.local

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.labteto.dshmobile.local.interaction.LocalQuestion
import com.labteto.dshmobile.local.interaction.LocalQuestionFormField
import com.labteto.dshmobile.local.interaction.LOCAL_MCP_QUESTION_DECLINE
import com.labteto.dshmobile.ui.theme.DshTheme
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class McpElicitationDialogTest {
    @get:Rule val compose = createComposeRule()

    @Test fun formAnswerRequiresFieldsAndReturnsTypedJson() {
        var sent: String? = null
        compose.setContent {
            DshTheme {
                McpElicitationDialog(
                    question = LocalQuestion(
                        callId = "mcp-one", question = "MCP 服务「sample」请求用户名",
                        requestId = "request-one",
                        mcpFormFields = listOf(LocalQuestionFormField(
                            name = "name", title = "账户名", kind = "string", required = true,
                        )),
                    ),
                    onAnswer = { sent = it; true },
                    onDismiss = {},
                )
            }
        }
        compose.onNodeWithTag("mcp-form-submit").assertIsNotEnabled()
        compose.onNodeWithTag("mcp-form-name").performTextInput("test-user")
        compose.onNodeWithTag("mcp-form-submit").performClick()
        compose.runOnIdle {
            assertEquals("test-user", Json.parseToJsonElement(requireNotNull(sent))
                .jsonObject.getValue("name").jsonPrimitive.content)
        }
    }


    @Test fun multipleChoicesSubmitArrayAndRespectMaxItems() {
        var answer: String? = null
        compose.setContent {
            DshTheme {
                McpElicitationDialog(
                    question = LocalQuestion(
                        callId = "mcp-multi", question = "请选择",
                        requestId = "request-multi",
                        mcpFormFields = listOf(LocalQuestionFormField(
                            name = "colors", title = "颜色", kind = "array", required = true,
                            options = listOf("r", "g", "b"),
                            optionLabels = mapOf("r" to "红", "g" to "绿", "b" to "蓝"),
                            minItems = 1, maxItems = 2,
                        )),
                    ),
                    onAnswer = { answer = it; true },
                    onDismiss = {},
                )
            }
        }
        compose.onNodeWithTag("mcp-form-submit").assertIsNotEnabled()
        compose.onNodeWithText("红").performClick()
        compose.onNodeWithText("绿").performClick()
        compose.onNodeWithText("蓝").performClick()
        compose.onNodeWithTag("mcp-form-submit").assertIsNotEnabled()
        compose.onNodeWithText("蓝").performClick()
        compose.onNodeWithTag("mcp-form-submit").performClick()
        compose.runOnIdle {
            val colors = Json.parseToJsonElement(requireNotNull(answer)).jsonObject
                .getValue("colors").jsonArray.map { it.jsonPrimitive.content }
            assertEquals(listOf("r", "g"), colors)
        }
    }

    @Test fun refusingFormReturnsSeparateDeclineAction() {
        var response: String? = null
        compose.setContent {
            DshTheme {
                McpElicitationDialog(
                    question = LocalQuestion(
                        callId = "mcp-two", question = "MCP 服务「sample」需要信息",
                        requestId = "request-two",
                        mcpFormFields = listOf(LocalQuestionFormField(
                            name = "answer", title = "答复", kind = "string", required = false,
                        )),
                    ),
                    onAnswer = { response = it; true },
                    onDismiss = {},
                )
            }
        }
        compose.onNodeWithTag("mcp-form-decline").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(LOCAL_MCP_QUESTION_DECLINE, response) }
    }

    @Test fun urlModeShowsOriginAndWaitsForExplicitUserDecision() {
        var response: String? = null
        var cancelled = 0
        compose.setContent {
            DshTheme {
                McpElicitationDialog(
                    question = LocalQuestion(
                        callId = "mcp-url", question = "MCP 服务「sample」请求网页登录",
                        requestId = "request-url",
                        mcpUrl = "https://example.org/authorize",
                    ),
                    onAnswer = { response = it; true },
                    onDismiss = { cancelled++ },
                )
            }
        }
        compose.onNodeWithText("此操作将在浏览器打开：example.org。密码、密钥与支付信息仅在对方网页填写。")
            .assertIsDisplayed()
        compose.onNodeWithTag("mcp-form-submit").assertIsNotEnabled()
        compose.onNodeWithTag("mcp-form-cancel").performClick()
        compose.runOnIdle {
            assertEquals(1, cancelled)
            assertNull(response)
        }
    }
}

package com.labteto.dshmobile.ui.screens.local

import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.foundation.clickable
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.labteto.dshmobile.local.tools.LocalTaskCapabilityKind
import com.labteto.dshmobile.local.tools.LocalTaskCapabilityReadiness
import com.labteto.dshmobile.local.tools.LocalTaskCapabilityState
import com.labteto.dshmobile.ui.theme.DshTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class LocalWorkCapabilitySheetRegressionTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun handoffDraftCanBeEditedAndCancelDoesNotStartWork() {
        val prompt = mutableStateOf("old target")
        val summary = mutableStateOf("old summary")
        var entered = 0
        var dismissed = 0
        compose.setContent {
            DshTheme {
                LocalWorkCapabilitySheet(
                    false, false, true, { entered++ }, { dismissed++ },
                    prompt = prompt.value, summary = summary.value,
                    onPromptChange = { prompt.value = it }, onSummaryChange = { summary.value = it },
                )
            }
        }
        compose.onNodeWithText("old target").performTextReplacement("new target")
        compose.onNodeWithText("old summary").performTextReplacement("materials/report.md")
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle {
            assertEquals("new target", prompt.value)
            assertEquals("materials/report.md", summary.value)
            assertEquals(0, entered)
            assertEquals(1, dismissed)
        }
    }

    @Test
    fun taskCapabilityReadinessIsVisibleAndDoesNotBlockNormalHandoff() {
        var entered = 0
        compose.setContent {
            DshTheme {
                LocalWorkCapabilitySheet(
                    switching = false, failed = false, enabled = true,
                    onContinue = { entered++ }, onDismiss = {},
                    prompt = "检查 GitHub PR 并联网查询",
                    capabilities = listOf(
                        LocalTaskCapabilityReadiness(LocalTaskCapabilityKind.GITHUB,
                            LocalTaskCapabilityState.CONNECTION_REQUIRED),
                        LocalTaskCapabilityReadiness(LocalTaskCapabilityKind.WEB_SEARCH,
                            LocalTaskCapabilityState.DISABLED),
                    ),
                )
            }
        }
        compose.onNodeWithText("GitHub · 尚未配置连接，可在能力中心设置").assertIsDisplayed()
        compose.onNodeWithText("联网搜索 · 当前已关闭，可在能力中心开启").assertIsDisplayed()
        compose.onNodeWithText("进入工作模式").performClick()
        compose.runOnIdle { assertEquals(1, entered) }
    }

    @Test
    fun enteringWorkRequiresAnExplicitUserAction() {
        var entered = 0
        compose.setContent {
            DshTheme {
                LocalWorkCapabilitySheet(false, false, true, { entered++ }, {})
            }
        }
        compose.runOnIdle { assertEquals(0, entered) }
        compose.onNodeWithText("进入工作模式").performClick()
        compose.runOnIdle { assertEquals(1, entered) }
    }

    @Test
    fun configurationReturnAndRefreshPreserveDraftWithoutStartingWork() {
        val configuring = mutableStateOf(false)
        val configured = mutableStateOf(false)
        val prompt = mutableStateOf("检查 GitHub PR")
        val summary = mutableStateOf("材料引用")
        var entered = 0
        var refreshed = 0
        compose.setContent {
            DshTheme {
                if (configuring.value) {
                    Text("返回交接", modifier = Modifier.clickable { configuring.value = false })
                } else {
                    LocalWorkCapabilitySheet(
                        false, false, true, { entered++ }, {},
                        prompt = prompt.value, summary = summary.value,
                        onPromptChange = { prompt.value = it }, onSummaryChange = { summary.value = it },
                        capabilities = listOf(LocalTaskCapabilityReadiness(
                            LocalTaskCapabilityKind.GITHUB,
                            if (configured.value) LocalTaskCapabilityState.CONFIGURED
                            else LocalTaskCapabilityState.CONNECTION_REQUIRED,
                        )),
                        onConfigureCapabilities = { configuring.value = true },
                        onRefreshCapabilities = { refreshed++; configured.value = true },
                    )
                }
            }
        }
        compose.onNodeWithText("检查 GitHub PR").performTextReplacement("修改 GitHub PR")
        compose.onNodeWithText("材料引用").performTextReplacement("materials/requirements.md")
        compose.onNodeWithText("前往能力中心配置").performClick()
        compose.onNodeWithText("返回交接").performClick()
        compose.onNodeWithText("修改 GitHub PR").assertIsDisplayed()
        compose.onNodeWithText("materials/requirements.md").assertIsDisplayed()
        compose.onNodeWithText("刷新").performClick()
        compose.onNodeWithText("GitHub · 配置已开启；实际调用仍需验证").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(0, entered)
            assertEquals(1, refreshed)
        }
    }

    @Test
    fun selectedHistoryRequiresUserClickAndNeverStartsWorkAutomatically() {
        val selected = mutableStateOf<List<String>>(emptyList())
        var started = 0
        val old = com.labteto.dshmobile.local.session.LocalHarnessMessage(
            id = "prior-1", role = "user", content = "此前已确认的要求", createdAt = 1L,
        )
        compose.setContent {
            DshTheme {
                LocalWorkCapabilitySheet(
                    switching = false, failed = false, enabled = true,
                    onContinue = { started++ }, onDismiss = {},
                    summary = "用户手写摘要",
                    selectableMessages = listOf(old),
                    selectedMessageIds = selected.value,
                    onSelectedMessageIdsChange = { selected.value = it },
                )
            }
        }
        compose.runOnIdle {
            assertEquals(emptyList<String>(), selected.value)
            assertEquals(0, started)
        }
        compose.onNodeWithTag("handoff_message_prior-1").performClick()
        compose.runOnIdle {
            assertEquals(listOf("prior-1"), selected.value)
            assertEquals(0, started)
        }
        compose.onNodeWithTag("handoff_message_prior-1").performClick()
        compose.runOnIdle { assertEquals(emptyList<String>(), selected.value) }
    }

    @Test
    fun unconfiguredModelCanOpenSettingsWithoutStartingWork() {
        var configured = 0
        var started = 0
        compose.setContent {
            DshTheme {
                LocalWorkCapabilitySheet(
                    switching = false, failed = false, enabled = true,
                    onContinue = { started++ }, onDismiss = {},
                    capabilities = listOf(LocalTaskCapabilityReadiness(
                        LocalTaskCapabilityKind.MODEL, LocalTaskCapabilityState.CONNECTION_REQUIRED,
                    )),
                    onConfigureModel = { configured++ },
                )
            }
        }
        compose.onNodeWithText("当前模型 · 尚未配置模型，可前往模型设置").assertIsDisplayed()
        compose.onNodeWithText("前往模型设置").performClick()
        compose.runOnIdle {
            assertEquals(1, configured)
            assertEquals(0, started)
        }
    }

    @Test
    fun missingDevicePermissionOpensSettingsAndDoesNotStartWork() {
        var openedSettings = 0
        var started = 0
        compose.setContent {
            DshTheme {
                LocalWorkCapabilitySheet(
                    switching = false, failed = false, enabled = true,
                    onContinue = { started++ }, onDismiss = {},
                    capabilities = listOf(LocalTaskCapabilityReadiness(
                        LocalTaskCapabilityKind.ACCESSIBILITY, LocalTaskCapabilityState.CONNECTION_REQUIRED,
                    )),
                    onConfigureDevice = { openedSettings++ },
                )
            }
        }
        compose.onNodeWithText("设备无障碍服务 · 当前未激活，请检查系统授权").assertIsDisplayed()
        compose.onNodeWithText("前往设备权限设置").performClick()
        compose.runOnIdle {
            assertEquals(1, openedSettings)
            assertEquals(0, started)
        }
    }

    @Test
    fun searchAndLoadEarlierKeepExplicitHandoffSelection() {
        val selected = mutableStateOf<List<String>>(emptyList())
        val query = mutableStateOf("")
        var loaded = 0
        var entered = 0
        val messages = (1..15).map { index ->
            com.labteto.dshmobile.local.session.LocalHarnessMessage(
                id = "msg-$index", role = "user", content = "第${index}条旧记录", createdAt = index.toLong(),
            )
        }
        compose.setContent {
            DshTheme {
                LocalWorkCapabilitySheet(
                    switching = false, failed = false, enabled = true,
                    onContinue = { entered++ }, onDismiss = {},
                    selectableMessages = messages,
                    selectedMessageIds = selected.value,
                    onSelectedMessageIdsChange = { selected.value = it },
                    messageSearch = query.value,
                    onMessageSearchChange = { query.value = it },
                    hasEarlierMessages = true,
                    onLoadEarlierMessages = { loaded++ },
                )
            }
        }
        compose.onNodeWithTag("handoff_search").performTextReplacement("第1条旧记录")
        compose.onNodeWithTag("handoff_message_msg-1").performClick()
        compose.runOnIdle { assertEquals(listOf("msg-1"), selected.value) }
        compose.onNodeWithTag("handoff_search").performTextReplacement("")
        compose.onNodeWithTag("handoff_message_msg-1").assertIsDisplayed()
        compose.onNodeWithTag("handoff_load_older").performClick()
        compose.runOnIdle {
            assertEquals(1, loaded)
            assertEquals(0, entered)
            assertEquals(listOf("msg-1"), selected.value)
        }
    }

    @Test
    fun pendingModeTransitionCannotBeSubmittedAgainOrDismissed() {
        compose.setContent {
            DshTheme {
                LocalWorkCapabilitySheet(true, false, true, {}, {})
            }
        }
        compose.onNodeWithText("进入工作模式").assertIsNotEnabled()
        compose.onNodeWithText("取消").assertIsNotEnabled()
    }
}

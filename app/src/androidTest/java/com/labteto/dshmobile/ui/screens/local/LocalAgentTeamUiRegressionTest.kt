package com.labteto.dshmobile.ui.screens.local

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.labteto.dshmobile.local.work.LocalAgentTeamMemberUiState
import com.labteto.dshmobile.local.work.LocalAgentTeamTaskUiState
import com.labteto.dshmobile.local.work.LocalAgentTeamUiState
import com.labteto.dshmobile.ui.theme.DshTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class LocalAgentTeamUiRegressionTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun rebuildingStatusDoesNotPretendToRecruitMembers() {
        compose.setContent {
            DshTheme {
                LocalAgentTeamStatusBar(LocalAgentTeamUiState(rebuilding = true), false, {})
            }
        }
        compose.onNodeWithText("正在恢复团队状态").assertExists()
        compose.onNodeWithText("正在匹配人员中").assertDoesNotExist()
    }

    @Test
    fun inactiveMemberDisablesSendingAndOffersLeadHandoff() {
        var handedToLead: String? = null
        setSheet(
            LocalAgentTeamUiState(members = listOf(member().copy(phase = "disabled", activity = "disabled"))),
            onLeadFollowup = { handedToLead = it },
        )
        compose.onNodeWithText("核验助手").performScrollTo().performClick()
        compose.onAllNodesWithText("已停用")[0].assertExists()
        compose.onNodeWithContentDescription("发送给助手").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("给助手发消息…").assertIsNotEnabled()
        compose.onNodeWithText("让主智能体处理异常…").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("核验助手", handedToLead) }
    }

    @Test
    fun stoppingStatusDoesNotPretendToWaitForWork() {
        compose.setContent {
            DshTheme {
                LocalAgentTeamStatusBar(LocalAgentTeamUiState(members = listOf(member().copy(activity = "stopping"))), false, {})
            }
        }
        compose.onNodeWithText("1 个助手正在停止").assertExists()
        compose.onNodeWithText("等待任务").assertDoesNotExist()
    }

    @Test
    fun memberOutputFailureShowsRetryAndKeepsThePanelAvailable() {
        val attempts = java.util.concurrent.atomic.AtomicInteger()
        setSheet(
            team = LocalAgentTeamUiState(members = listOf(member())),
            readOutput = {
                if (attempts.incrementAndGet() == 1) error("private-path")
                "恢复后的输出"
            },
        )
        compose.onNodeWithText("核验助手", substring = false).performScrollTo().performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("暂时无法读取助手输出，已保留上次成功内容。请重试。")
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("重试").performScrollTo().performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("恢复后的输出").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun expandingOutputAndTaskDetailsRevealsHiddenContent() {
        val output = "开头核验证据" + "中间资料".repeat(450) + "末尾结论"
        setSheet(
            LocalAgentTeamUiState(
                members = listOf(member()),
                tasks = listOf(LocalAgentTeamTaskUiState(
                    id = "task", subject = "核查引用", description = "完整任务说明",
                    status = "pending", ownerName = "核验助手", blockedByTitles = listOf("先收集资料"), writeConflict = true,
                )),
            ),
            output = output,
        )
        compose.onNodeWithText("核验助手", substring = false).performScrollTo().performClick()
        compose.onNodeWithText("展开当前输出").performScrollTo().performClick()
        compose.onNodeWithText(output).assertExists()
        compose.onNodeWithText("收起输出").performScrollTo().performClick()
        compose.onNodeWithText(output).assertDoesNotExist()
        compose.onNodeWithText("核查引用").performScrollTo().performClick()
        compose.onNodeWithText("完整任务说明").assertExists()
        compose.onNodeWithText("负责人：核验助手").assertExists()
        compose.onNodeWithText("等待：先收集资料").assertExists()
        compose.onNodeWithText("存在并发写入范围冲突").assertExists()
    }

    private fun setSheet(
        team: LocalAgentTeamUiState,
        output: String = "",
        onLeadFollowup: (String) -> Unit = {},
        readOutput: (String) -> String = { output },
    ) {
        compose.setContent {
            DshTheme {
                LocalAgentTeamSheet(
                    team = team, onMemberOutput = readOutput,
                    onSendMemberMessage = { _, _ -> LocalWorkUiActionResult(true, "已投递") },
                    onStopMember = { LocalWorkUiActionResult(true, "已停止") },
                    onStopAll = { LocalWorkUiActionResult(true, "已停止") },
                    onLeadFollowup = onLeadFollowup, onDismiss = {},
                )
            }
        }
    }

    private fun member() = LocalAgentTeamMemberUiState(
        id = "member", jobId = "job", name = "核验助手", description = "核验资料",
        phase = "active", activity = "completed",
    )
}

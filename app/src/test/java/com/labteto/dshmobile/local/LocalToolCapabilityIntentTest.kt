package com.labteto.dshmobile.local

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalToolCapabilityIntentTest {
    @Test
    fun followUpKeepsRecentGithubIntentWithoutReadingSyntheticCheckpoints() {
        val history = listOf(
            message("user", "检查 GitHub PR 394 的 CI 并处理失败"),
            message("assistant", "正在处理"),
            message("user", "<work-checkpoint>GitHub 不应由检查点触发</work-checkpoint>"),
        )

        val followUp = LocalToolCapabilityIntent.from("继续", history)
        val unrelated = LocalToolCapabilityIntent.from(
            "继续修改本地页面",
            listOf(message("user", "只改本地布局，不涉及远程仓库")),
        )

        assertTrue(followUp.requestsGitHub)
        assertFalse(unrelated.requestsGitHub)
        assertFalse(followUp.context.contains("<work-checkpoint>"))
    }

    @Test
    fun explicitPrIntentIsDetectedWithoutRequiringGithubWord() {
        assertTrue(LocalToolCapabilityIntent.from("把 PR #42 的冲突处理掉", emptyList()).requestsGitHub)
        assertTrue(LocalToolCapabilityIntent.from("检查 pull request 42", emptyList()).requestsGitHub)
        assertFalse(LocalToolCapabilityIntent.from("修复 process 调度", emptyList()).requestsGitHub)
    }

    private fun message(role: String, content: String) = buildJsonObject {
        put("role", role)
        put("content", content)
    }
}

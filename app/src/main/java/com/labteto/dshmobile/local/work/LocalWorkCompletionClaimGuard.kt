package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.LocalHarnessState
import com.labteto.dshmobile.local.LocalSessionEventLog
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.quality.LocalOutputQualityContext
import com.labteto.dshmobile.local.quality.LocalOutputQualityGuard
import com.labteto.dshmobile.local.quality.LocalOutputQualityResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object LocalWorkCompletionClaimGuard : LocalOutputQualityGuard {
    override fun inspect(
        text: String,
        context: LocalOutputQualityContext,
    ): LocalOutputQualityResult {
        if (
            text.isBlank() ||
            !COMPLETION_CLAIM.containsMatchIn(text) ||
            NEGATED_COMPLETION.containsMatchIn(text)
        ) {
            return LocalOutputQualityResult(text)
        }
        val state = context.state ?: return LocalOutputQualityResult(text)
        val findings = mutableListOf<String>()
        val openTodos = state.todos.count { it.status == "pending" || it.status == "in_progress" }
        if (openTodos > 0) findings += "完成声明与未完成任务清单冲突"
        if (state.goal?.status == "blocked") findings += "完成声明与阻塞目标状态冲突"
        return LocalOutputQualityResult(text = text, findings = findings)
    }

    private val COMPLETION_CLAIM = Regex(
        """(?:已(?:经)?完成|全部完成|处理完毕|任务完成|工作完成|\bdone\b|\bcompleted\b|\bfinished\b)""",
        RegexOption.IGNORE_CASE,
    )
    private val NEGATED_COMPLETION = Regex(
        """(?:未完成|尚未完成|还没完成|没有完成|无法完成|不能完成|not\s+(?:done|completed|finished)|unfinished)""",
        RegexOption.IGNORE_CASE,
    )
}

internal fun recordWorkCompletionQuality(
    text: String,
    state: LocalHarnessState,
    eventLog: LocalSessionEventLog,
) {
    if (state.usageMode != LocalUsageMode.WORK) return
    val result = LocalWorkCompletionClaimGuard.inspect(
        text,
        LocalOutputQualityContext(usageMode = state.usageMode, state = state),
    )
    if (result.findings.isEmpty()) return
    eventLog.append("work/output-quality", buildJsonObject {
        put("findings", JsonArray(result.findings.map(::JsonPrimitive)))
        put("changed", result.changed)
        put("open_todos", state.todos.count { it.status == "pending" || it.status == "in_progress" })
        state.goal?.status?.let { put("goal_status", it) }
    })
}

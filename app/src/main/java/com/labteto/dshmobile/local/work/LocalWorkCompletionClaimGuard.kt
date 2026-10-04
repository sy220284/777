package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.LocalHarnessState
import com.labteto.dshmobile.local.LocalModelReply
import com.labteto.dshmobile.local.LocalSessionEventLog
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.model.LocalCanonicalContent
import com.labteto.dshmobile.local.quality.LocalOutputQualityContext
import com.labteto.dshmobile.local.quality.LocalOutputQualityGuard
import com.labteto.dshmobile.local.quality.LocalOutputQualityResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
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
        if (findings.isEmpty()) return LocalOutputQualityResult(text)
        return LocalOutputQualityResult(
            text = truthfulIncompleteDeliveryText(openTodos, state.goal?.status == "blocked"),
            findings = findings,
            changed = true,
        )
    }

    private val COMPLETION_CLAIM = Regex(
        """(?im)(?:^|[。！？!?]\s*)(?:(?:全部|所有|整个任务|本次任务|任务|工作)(?:都|已经|已)?(?:完成|处理完毕)|(?:已经|已)?完成(?:了)?(?=\s*(?:[。！？!?，,]|$))|处理完毕(?=\s*(?:[。！？!?，,]|$))|\b(?:done|completed|finished)\b|(?:可以|可)(?:直接)?交付)""",
        RegexOption.IGNORE_CASE,
    )
    private val NEGATED_COMPLETION = Regex(
        """(?:未完成|尚未完成|还没完成|没有完成|无法完成|不能完成|not\s+(?:done|completed|finished)|unfinished)""",
        RegexOption.IGNORE_CASE,
    )
}

internal fun guardWorkCompletionDelivery(
    reply: LocalModelReply,
    state: LocalHarnessState,
    eventLog: LocalSessionEventLog,
): LocalModelReply {
    if (state.usageMode != LocalUsageMode.WORK || reply.toolCalls.isNotEmpty()) return reply
    val original = reply.content?.takeIf(String::isNotBlank) ?: return reply
    val result = LocalWorkCompletionClaimGuard.inspect(
        original,
        LocalOutputQualityContext(usageMode = state.usageMode, state = state),
    )
    if (!result.changed) return reply
    eventLog.append("work/output-quality", buildJsonObject {
        put("findings", JsonArray(result.findings.map(::JsonPrimitive)))
        put("changed", true)
        put("delivery_blocked", true)
        put("open_todos", state.todos.count { it.status == "pending" || it.status == "in_progress" })
        state.goal?.status?.let { put("goal_status", it) }
    })
    val guardedMessage = JsonObject(reply.message + ("content" to JsonPrimitive(result.text)))
    val guardedCanonical = reply.canonicalMessage?.copy(
        content = reply.canonicalMessage.content
            .filterNot { it is LocalCanonicalContent.Text } +
            LocalCanonicalContent.Text(result.text),
    )
    return reply.copy(
        message = guardedMessage,
        content = result.text,
        canonicalMessage = guardedCanonical,
    )
}

private fun truthfulIncompleteDeliveryText(
    openTodos: Int,
    blockedGoal: Boolean,
): String {
    val reasons = buildList {
        if (openTodos > 0) add("仍有 " + openTodos + " 项任务未完成")
        if (blockedGoal) add("目标仍处于阻塞状态")
    }
    return "当前尚未完成：" + reasons.joinToString("；") +
        "。现有进度已保留，完成状态将在剩余事项实际闭环后确认。"
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

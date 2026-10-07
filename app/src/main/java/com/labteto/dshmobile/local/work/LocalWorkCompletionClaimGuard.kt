package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.LocalHarnessState
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.model.LocalCanonicalContent
import com.labteto.dshmobile.local.model.LocalModelReply
import com.labteto.dshmobile.local.quality.LocalOutputQualityResult
import com.labteto.dshmobile.local.session.LocalSessionEventLog
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object LocalWorkCompletionClaimGuard {
    fun inspect(
        text: String,
        workState: LocalWorkState,
    ): LocalOutputQualityResult {
        if (text.isBlank()) return LocalOutputQualityResult(text)
        val corrected = correctGlobalCompletionClaims(text)
        if (corrected == text) return LocalOutputQualityResult(text)
        val findings = mutableListOf<String>()
        val openTodos = workState.todos.count { it.status == "pending" || it.status == "in_progress" }
        val openTeamTasks = workState.team.tasks.count {
            it.status == "pending" || it.status == "in_progress"
        }
        val activeTeamMembers = workState.team.members.count {
            it.phase == "provisioning" || it.activity == "running" || it.activity == "stopping"
        }
        val pendingTeamMessages = workState.team.members.sumOf { it.pendingMessageCount }
        if (openTodos > 0) findings += "完成声明与未完成任务清单冲突"
        if (openTeamTasks > 0) findings += "完成声明与未完成 Agent Team 任务冲突"
        if (activeTeamMembers > 0) findings += "完成声明与仍在运行的 Agent Team 成员冲突"
        if (pendingTeamMessages > 0) findings += "完成声明与待投递 Agent Team 消息冲突"
        if (workState.goal?.status == "blocked") findings += "完成声明与阻塞目标状态冲突"
        if (findings.isEmpty()) return LocalOutputQualityResult(text)
        return LocalOutputQualityResult(
            text = truthfulIncompleteDeliveryText(
                openTodos = openTodos,
                blockedGoal = workState.goal?.status == "blocked",
                openTeamTasks = openTeamTasks,
                activeTeamMembers = activeTeamMembers,
            ) + "\n\n" + corrected,
            findings = findings,
            changed = true,
        )
    }

    private val CLAIM = Regex(
        """(?:全部|所有(?:任务|工作|事项)?|整个任务|本次任务)(?:都|已经|已|均|全部)*(?:完成|处理完毕)|^\s*(?:[-+]\s*)?(?:任务|工作|修复)(?:都|已经|已)*(?:全部)?(?:完成|处理完毕)|^\s*(?:[-+]\s*)?(?:已经|已)?完成(?:了)?(?=\s*[。！？!?，,\n]|\s*$)|^\s*(?:[-+]\s*)?(?:可以|可)(?:直接)?交付|^\s*(?:done|completed|finished)\b""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE),
    )
    private val NEGATION_PREFIX = Regex("""(?:尚未|还没|没有|无法|不能|未|不|not)\s*$""", RegexOption.IGNORE_CASE)

    internal fun correctGlobalCompletionClaims(text: String): String {
        // Code and linked artifact names are not the assistant's delivery claim.
        val protected = Regex("(?s)```.*?```|~~~.*?~~~|`[^`\n]+`|\\[[^\\]\n]*\\]\\([^\\)\n]*\\)").findAll(text)
            .map { it.range }.toList()
        return Regex("[^。！？!?，,\n]+[。！？!?，,\n]*|[。！？!?，,\n]+").findAll(text)
            .joinToString("") { part ->
                val raw = part.value
                val offsets = raw.indices.filter { raw[it] !in "*_`#" }
                val clean = offsets.map { raw[it] }.joinToString("")
                val matches = CLAIM.findAll(clean).filter { match ->
                    !NEGATION_PREFIX.containsMatchIn(clean.take(match.range.first)) &&
                        protected.none { (part.range.first + offsets[match.range.first]) in it }
                }.toList()
                var result = raw
                for (match in matches.asReversed()) {
                    result = result.replaceRange(offsets[match.range.first], offsets[match.range.last] + 1,
                        "完成结论待剩余事项闭环确认")
                }
                result
            }
    }

}


internal fun guardWorkCompletionDelivery(
    reply: LocalModelReply,
    state: LocalHarnessState,
    eventLog: LocalSessionEventLog,
): LocalModelReply {
    if (state.usageMode != LocalUsageMode.WORK || reply.toolCalls.isNotEmpty()) return reply
    val original = reply.content?.takeIf(String::isNotBlank) ?: return reply
    val result = LocalWorkCompletionClaimGuard.inspect(original, state.work)
    if (!result.changed) return reply
    eventLog.append("work/output-quality", buildJsonObject {
        put("findings", JsonArray(result.findings.map(::JsonPrimitive)))
        put("changed", true)
        put("delivery_blocked", true)
        put("open_todos", state.work.todos.count { it.status == "pending" || it.status == "in_progress" })
        put("open_team_tasks", state.work.team.tasks.count { it.status == "pending" || it.status == "in_progress" })
        put("active_team_members", state.work.team.members.count {
            it.phase == "provisioning" || it.activity == "running" || it.activity == "stopping"
        })
        put("pending_team_messages", state.work.team.members.sumOf { it.pendingMessageCount })
        state.work.goal?.status?.let { put("goal_status", it) }
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
    openTeamTasks: Int,
    activeTeamMembers: Int,
    pendingTeamMessages: Int,
): String {
    val reasons = buildList {
        if (openTodos > 0) add("仍有 " + openTodos + " 项任务未完成")
        if (openTeamTasks > 0) add("Agent Team 仍有 " + openTeamTasks + " 项任务未完成")
        if (activeTeamMembers > 0) add("仍有 " + activeTeamMembers + " 个智能体在执行")
        if (pendingTeamMessages > 0) add("仍有 " + pendingTeamMessages + " 条 Team 消息待投递")
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
    val result = LocalWorkCompletionClaimGuard.inspect(text, state.work)
    if (result.findings.isEmpty()) return
    eventLog.append("work/output-quality", buildJsonObject {
        put("findings", JsonArray(result.findings.map(::JsonPrimitive)))
        put("changed", result.changed)
        put("open_todos", state.work.todos.count { it.status == "pending" || it.status == "in_progress" })
        put("open_team_tasks", state.work.team.tasks.count { it.status == "pending" || it.status == "in_progress" })
        put("active_team_members", state.work.team.members.count {
            it.phase == "provisioning" || it.activity == "running" || it.activity == "stopping"
        })
        put("pending_team_messages", state.work.team.members.sumOf { it.pendingMessageCount })
        state.work.goal?.status?.let { put("goal_status", it) }
    })
}

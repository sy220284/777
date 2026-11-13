package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.model.DeepSeekUsageTracker
import com.labteto.dshmobile.local.model.LocalModelReply
import com.labteto.dshmobile.local.runtime.LocalAgentRunKind
import com.labteto.dshmobile.local.usage.PromptTokenEstimateCache
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Model reply -> neutral usage context adapters. No statistics storage owner here. */
internal fun TokenPromptBreakdown.calibratedToReportedInput(reportedInputTokens: Long): TokenPromptBreakdown {
        if (reportedInputTokens <= 0L || estimatedInputTokens <= 0L) return this
        val target = reportedInputTokens.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val factor = target.toDouble() / estimatedInputTokens.toDouble()
        fun scaled(value: Int): Int = (value.toDouble() * factor).toInt().coerceAtLeast(0)
        val base = copy(
            systemBaseTokens = scaled(systemBaseTokens),
            personaStateTokens = scaled(personaStateTokens),
            memoryRuleTokens = scaled(memoryRuleTokens),
            historyTokens = scaled(historyTokens),
            currentUserTokens = scaled(currentUserTokens),
            toolDefinitionTokens = scaled(toolDefinitionTokens),
            otherSystemTokens = scaled(otherSystemTokens),
        )
        val remainder = (target - base.estimatedInputTokens.toInt()).coerceAtLeast(0)
        return base.copy(otherSystemTokens = base.otherSystemTokens + remainder)
    }



internal fun buildTokenUsageContext(
    snapshot: LocalHarnessState,
    action: TokenUsageAction,
    turnId: String? = null,
    runId: String? = null,
    parentRunId: String? = null,
    runKind: LocalAgentRunKind? = null,
    agentId: String? = null,
    taskLabel: String? = null,
    step: Int? = null,
    taskRunId: String? = null,
): TokenUsageContext = TokenUsageContext(
    mode = snapshot.usageMode,
    sessionId = snapshot.sessionId.takeIf(String::isNotBlank),
    sessionTitle = snapshot.sessions.firstOrNull { it.id == snapshot.sessionId }?.title
        ?.takeIf(String::isNotBlank),
    turnId = turnId?.takeIf(String::isNotBlank),
    runId = runId?.takeIf(String::isNotBlank),
    parentRunId = parentRunId?.takeIf(String::isNotBlank),
    runKind = runKind?.name?.lowercase(),
    agentId = agentId?.takeIf(String::isNotBlank),
    taskLabel = taskLabel?.trim()?.takeIf(String::isNotBlank)?.take(120),
    step = step,
    action = action,
    taskRunId = taskRunId?.takeIf(String::isNotBlank),
)

internal fun DeepSeekUsageTracker.record(
    model: String,
    reply: LocalModelReply,
    context: TokenUsageContext,
) = record(
    model = model,
    usage = reply.usage,
    requestId = reply.requestId,
    context = context,
    promptBreakdown = if (reply.usage.reported) {
        reply.promptBreakdown.calibratedToReportedInput(reply.usage.promptTokens)
    } else {
        reply.promptBreakdown
    },
    route = reply.routeIdentity,
)

internal fun DeepSeekUsageTracker.record(
    snapshot: LocalHarnessState,
    reply: LocalModelReply,
    context: TokenUsageContext,
) {
    record(
        model = snapshot.modelState.model,
        usage = reply.usage,
        requestId = reply.requestId,
        context = context,
        promptBreakdown = if (reply.usage.reported) {
            reply.promptBreakdown.calibratedToReportedInput(reply.usage.promptTokens)
        } else {
            reply.promptBreakdown
        },
        route = reply.routeIdentity,
    )
}

internal fun DeepSeekUsageTracker.record(
    snapshot: LocalHarnessState,
    reply: LocalModelReply,
    action: TokenUsageAction,
    turnId: String? = null,
    runId: String? = null,
    parentRunId: String? = null,
    runKind: LocalAgentRunKind? = null,
    agentId: String? = null,
    taskLabel: String? = null,
    step: Int? = null,
    taskRunId: String? = null,
) {
    record(
        snapshot = snapshot,
        reply = reply,
        context = buildTokenUsageContext(
            snapshot = snapshot,
            action = action,
            turnId = turnId,
            runId = runId,
            parentRunId = parentRunId,
            runKind = runKind,
            agentId = agentId,
            taskLabel = taskLabel,
            step = step,
            taskRunId = taskRunId,
        ),
    )
}

internal fun buildForegroundTokenUsageContext(
    snapshot: LocalHarnessState,
    action: TokenUsageAction,
    turnId: String? = null,
    runId: String? = null,
    taskLabel: String? = null,
    step: Int? = null,
): TokenUsageContext = buildTokenUsageContext(
    snapshot = snapshot,
    action = action,
    turnId = turnId,
    runId = runId,
    runKind = LocalAgentRunKind.FOREGROUND,
    taskLabel = taskLabel,
    step = step,
)

internal fun DeepSeekUsageTracker.recordForeground(
    snapshot: LocalHarnessState,
    reply: LocalModelReply,
    action: TokenUsageAction,
    turnId: String? = null,
    runId: String? = null,
    taskLabel: String? = null,
    step: Int? = null,
    taskRunId: String? = null,
) = record(
    snapshot = snapshot,
    reply = reply,
    action = action,
    turnId = turnId,
    runId = runId,
    runKind = LocalAgentRunKind.FOREGROUND,
    taskLabel = taskLabel,
    step = step,
    taskRunId = taskRunId,
)

internal fun buildAutomationTokenUsageContext(
    snapshot: LocalHarnessState,
    action: TokenUsageAction,
    taskLabel: String,
    runId: String,
): TokenUsageContext {
    require(runId.isNotBlank()) { "自动化用量必须关联真实运行身份" }
    return buildTokenUsageContext(
        snapshot = snapshot,
        action = action,
        turnId = runId,
        runId = runId,
        taskRunId = runId,
        runKind = LocalAgentRunKind.AUTOMATION,
        taskLabel = taskLabel,
    )
}

internal fun DeepSeekUsageTracker.recordAutomation(
    snapshot: LocalHarnessState,
    reply: LocalModelReply,
    action: TokenUsageAction,
    taskLabel: String,
    runId: String,
) = record(
    snapshot = snapshot,
    reply = reply,
    context = buildAutomationTokenUsageContext(snapshot, action, taskLabel, runId),
)

internal fun estimatePromptBreakdown(
    messages: List<JsonObject>,
    tools: JsonArray,
): TokenPromptBreakdown {
    if (messages.isEmpty() && tools.isEmpty()) return TokenPromptBreakdown()
    val latestUser = messages.indexOfLast { message ->
        message["role"]?.jsonPrimitive?.contentOrNull == "user"
    }
    var systemBase = 0
    var personaState = 0
    var memoryRule = 0
    var history = 0
    var currentUser = 0
    var otherSystem = 0

    messages.forEachIndexed { index, message ->
        val role = message["role"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val estimate = PromptTokenEstimateCache.message(
            value = message,
            memoryClassifier = ::containsMemoryMarkers,
            personaClassifier = ::containsPersonaMarkers,
        )
        when {
            role == "user" && index == latestUser -> currentUser += estimate.tokens
            role != "system" -> history += estimate.tokens
            estimate.memory -> memoryRule += estimate.tokens
            estimate.persona -> personaState += estimate.tokens
            index == 0 -> systemBase += estimate.tokens
            else -> otherSystem += estimate.tokens
        }
    }
    return TokenPromptBreakdown(
        systemBaseTokens = systemBase,
        personaStateTokens = personaState,
        memoryRuleTokens = memoryRule,
        historyTokens = history,
        currentUserTokens = currentUser,
        toolDefinitionTokens = PromptTokenEstimateCache.tools(tools),
        otherSystemTokens = otherSystem,
    )
}

private fun containsMemoryMarkers(content: String): Boolean =
    MEMORY_MARKERS.any(content::contains)

private fun containsPersonaMarkers(content: String): Boolean =
    PERSONA_MARKERS.any(content::contains)

private val MEMORY_MARKERS = listOf(
    "【用户长期规则】",
    "【相关长期记忆】",
    "【上一会话交接】",
)

private val PERSONA_MARKERS = listOf(
    "【角色】",
    "【用户纠正｜最高优先】",
    "【当前状态】",
    "【近期表现去重】",
    "【连续性摘要",
    "【本轮相关背景】",
    "【群聊身份】",
)

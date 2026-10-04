package com.labteto.dshmobile.local.automation

import com.labteto.dshmobile.automation.AutomationScheduleType
import com.labteto.dshmobile.local.DeepSeekUsageTracker
import com.labteto.dshmobile.local.LocalModelException
import com.labteto.dshmobile.local.agent.LocalAgentModelStepRuntime
import com.labteto.dshmobile.local.executeWithModelAdmission
import com.labteto.dshmobile.local.toRunModelSurface
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.TokenUsageAction
import com.labteto.dshmobile.local.TokenUsageContext
import com.labteto.dshmobile.local.model.LocalModelGateway
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

internal data class AutomationPlanningMessage(
    val role: String,
    val content: String,
)

internal data class AutomationPlanningContext(
    val sessionId: String,
    val revision: AutomationPlanningRevision,
    val configured: Boolean,
    val usageMode: LocalUsageMode,
    val groupChatEnabled: Boolean,
    val model: String,
    val baseUrl: String,
    val profileId: String?,
    val personaName: String,
    val recentMessages: List<AutomationPlanningMessage>,
)

internal data class AutomationPlanDraft(
    val sourceSessionId: String,
    val sourceRevision: AutomationPlanningRevision,
    val prompt: String,
    val scheduleType: AutomationScheduleType,
    val firstRunAt: Long,
    val recurringMinutes: Long? = null,
    val silenceMinutes: Long? = null,
    val windowStartMinuteOfDay: Int? = null,
    val windowEndMinuteOfDay: Int? = null,
)

internal data class AutomationSuggestionSet(
    val sourceSessionId: String,
    val sourceRevision: AutomationPlanningRevision,
    val suggestions: List<String>,
)

@Singleton
class AutomationPlanningService @Inject constructor(
    private val runtime: LocalAutomationRuntime,
    private val modelGateway: LocalModelGateway,
    private val usageTracker: DeepSeekUsageTracker,
    private val json: Json,
) {
    private val modelStepRuntime = LocalAgentModelStepRuntime()
    internal suspend fun plan(input: String): AutomationPlanDraft {
        val context = runtime.planningContext()
        validateContext(context)
        val request = input
            .removePrefix("@角色事件规划")
            .trim()
            .take(2_000)
            .takeIf(String::isNotBlank)
            ?: error("先告诉我什么时候、让角色做什么")
        val now = System.currentTimeMillis()
        val reply = complete(
            context = context,
            system = planningSystemPrompt(now),
            user = buildString {
                appendLine("当前角色：${context.personaName.ifBlank { "当前角色" }}")
                appendLine("最近聊天：")
                appendLine(recentConversation(context))
                appendLine()
                append("用户要安排：")
                append(request)
            },
        )
        return parseAutomationPlan(
            raw = reply,
            json = json,
            nowMillis = now,
            sourceSessionId = context.sessionId,
            sourceRevision = context.revision,
        )
    }

    internal suspend fun suggestions(): AutomationSuggestionSet {
        val context = runtime.planningContext()
        validateContext(context)
        if (context.recentMessages.isEmpty()) {
            return AutomationSuggestionSet(context.sessionId, context.revision, emptyList())
        }
        val reply = complete(
            context = context,
            system = """
                你负责根据最近聊天提出 2～3 个“以后由当前角色主动发生”的定时互动建议。
                建议必须延续聊天中真实存在的话题、关系、计划或情绪，不得编造双方已经做过的事或不存在的约定。
                建议要具体到“什么时候/什么条件 + 角色做什么”，一句话能让用户直接修改。
                避免重复相同切入点，避免营销口吻。
                只返回 JSON：{"suggestions":["建议1","建议2","建议3"]}。
            """.trimIndent(),
            user = buildString {
                appendLine("当前角色：${context.personaName.ifBlank { "当前角色" }}")
                appendLine("最近聊天：")
                append(recentConversation(context))
            },
        )
        return AutomationSuggestionSet(
            sourceSessionId = context.sessionId,
            sourceRevision = context.revision,
            suggestions = parseAutomationSuggestions(reply, json),
        )
    }

    internal fun isCurrent(draft: AutomationPlanDraft): Boolean =
        resolveAutomationPlanningRevision(runtime.planningContext().revision, draft.sourceRevision).accepted

    internal fun isCurrent(suggestions: AutomationSuggestionSet): Boolean =
        resolveAutomationPlanningRevision(runtime.planningContext().revision, suggestions.sourceRevision).accepted

    private suspend fun complete(
        context: AutomationPlanningContext,
        system: String,
        user: String,
    ): String {
        val profile = modelGateway.profileForRoute(
            context.profileId,
            context.model,
            context.baseUrl,
        )
        val surface = profile.toRunModelSurface()
        val messages = listOf(
            buildJsonObject {
                put("role", "system")
                put("content", system)
            },
            buildJsonObject {
                put("role", "user")
                put("content", user)
            },
        )
        val reply = modelStepRuntime.execute(
            initialMessages = messages,
            maxAttempts = 2,
            retryable = { error ->
                (error as? LocalModelException)?.retryable == true || error is java.io.IOException
            },
            backoffMillis = { failedAttempt, error ->
                (error as? LocalModelException)?.providerRetryAfterMs?.coerceIn(0L, 30_000L)
                    ?: (750L shl (failedAttempt - 1).coerceIn(0, 10))
            },
        ) { activeMessages ->
            executeWithModelAdmission(
                control = null,
                routeFingerprint = surface.routeFingerprint,
                model = surface.model,
                baseUrl = surface.baseUrl,
                contextWindowTokensOverride = surface.contextWindowTokensOverride,
                messages = activeMessages,
                tools = JsonArray(emptyList()),
            ) {
                modelGateway.complete(
                    model = surface.model,
                    baseUrl = surface.baseUrl,
                    messages = activeMessages,
                    tools = JsonArray(emptyList()),
                    temperature = 0.35,
                    profile = surface.profile,
                )
            }
        }
        withContext(Dispatchers.IO) {
            usageTracker.record(
                model = context.model,
                usage = reply.usage,
                requestId = reply.requestId,
                context = TokenUsageContext(
                    mode = LocalUsageMode.CHAT,
                    sessionId = context.sessionId,
                    action = TokenUsageAction.AUTOMATION_CHAT,
                    taskLabel = "角色事件规划",
                ),
                promptBreakdown = reply.promptBreakdown,
                route = reply.routeIdentity,
            )
        }
        return reply.content?.trim()?.takeIf(String::isNotBlank)
            ?: error("模型没有生成可用的定时事件")
    }

    private fun validateContext(context: AutomationPlanningContext) {
        require(context.configured) { "请先配置可用的聊天模型" }
        require(context.usageMode == LocalUsageMode.CHAT) { "请先回到聊天模式再规划角色事件" }
        require(!context.groupChatEnabled) { "群聊暂不支持自动规划角色定时事件" }
        require(context.sessionId.isNotBlank()) { "当前聊天尚未准备好" }
    }

    private fun recentConversation(context: AutomationPlanningContext): String =
        context.recentMessages
            .takeLast(12)
            .joinToString("\n") { message ->
                val speaker = if (message.role == "assistant") {
                    context.personaName.ifBlank { "角色" }
                } else {
                    "用户"
                }
                "$speaker：${message.content.replace("\n", " ").take(500)}"
            }
            .ifBlank { "暂无可用聊天记录" }
}

internal fun parseAutomationSuggestions(raw: String, json: Json): List<String> {
    val root = json.parseToJsonElement(extractPlannerJson(raw)).jsonObject
    return root["suggestions"]
        ?.jsonArray
        ?.mapNotNull { it.jsonPrimitive.contentOrNull?.trim()?.takeIf(String::isNotBlank) }
        ?.distinct()
        ?.take(3)
        .orEmpty()
}

internal fun parseAutomationPlan(
    raw: String,
    json: Json,
    nowMillis: Long,
    sourceSessionId: String,
    sourceRevision: AutomationPlanningRevision = AutomationPlanningRevision(
        sessionId = sourceSessionId,
        usageMode = LocalUsageMode.CHAT,
        groupChatEnabled = false,
        personaId = "",
        galleryId = null,
        galleryStoryId = null,
        latestDialogueMessageId = null,
        chatContextGeneration = 0L,
    ),
): AutomationPlanDraft {
    val root = json.parseToJsonElement(extractPlannerJson(raw)).jsonObject
    val task = root["task"]?.jsonObject ?: root
    val prompt = task.string("prompt").take(2_000).takeIf(String::isNotBlank)
        ?: error("事件内容为空")
    val scheduleType = runCatching {
        AutomationScheduleType.valueOf(task.string("scheduleType").uppercase())
    }.getOrElse { error("无法识别事件时间安排") }
    require(scheduleType != AutomationScheduleType.LEGACY) { "事件时间安排无效" }

    val minimumFuture = nowMillis + 60_000L
    val requestedFirstRun = task.long("firstRunAtMillis") ?: minimumFuture
    val recurring = when (scheduleType) {
        AutomationScheduleType.DAILY -> 24L * 60L
        AutomationScheduleType.WEEKLY -> 7L * 24L * 60L
        AutomationScheduleType.INTERVAL -> (task.long("recurringMinutes") ?: 0L)
            .coerceAtLeast(60L)
        else -> null
    }
    val silenceMinutes = if (scheduleType == AutomationScheduleType.SILENCE) {
        (task.long("silenceMinutes") ?: 60L).coerceAtLeast(60L)
    } else {
        null
    }
    val windowStart = if (scheduleType == AutomationScheduleType.WINDOW) {
        (task.long("windowStartMinuteOfDay") ?: error("缺少时间窗开始时间")).toInt()
    } else {
        null
    }
    val windowEnd = if (scheduleType == AutomationScheduleType.WINDOW) {
        (task.long("windowEndMinuteOfDay") ?: error("缺少时间窗结束时间")).toInt()
    } else {
        null
    }
    if (scheduleType == AutomationScheduleType.WINDOW) {
        require(windowStart in 0 until 24 * 60) { "时间窗开始时间无效" }
        require(windowEnd in 0 until 24 * 60) { "时间窗结束时间无效" }
        require(windowStart != windowEnd) { "时间窗不能覆盖整天" }
    }
    return AutomationPlanDraft(
        sourceSessionId = sourceSessionId,
        sourceRevision = sourceRevision,
        prompt = prompt,
        scheduleType = scheduleType,
        firstRunAt = if (
            scheduleType == AutomationScheduleType.SILENCE ||
            scheduleType == AutomationScheduleType.WINDOW
        ) minimumFuture else requestedFirstRun.coerceAtLeast(minimumFuture),
        recurringMinutes = recurring,
        silenceMinutes = silenceMinutes,
        windowStartMinuteOfDay = windowStart,
        windowEndMinuteOfDay = windowEnd,
    )
}

private fun planningSystemPrompt(nowMillis: Long): String {
    val zone = ZoneId.systemDefault()
    val now = Instant.ofEpochMilli(nowMillis).atZone(zone)
    return """
        你负责把用户自然语言转换成一个可执行的角色定时互动事件。
        当前本地时间：$now，时区：${zone.id}，当前毫秒时间戳：$nowMillis。

        可用 scheduleType：
        - ONCE：只执行一次，必须给 firstRunAtMillis。
        - DAILY：每天重复，给第一次 firstRunAtMillis。
        - WEEKLY：每周重复，给第一次 firstRunAtMillis。
        - INTERVAL：按固定间隔重复，给 firstRunAtMillis 和 recurringMinutes（至少 60）。
        - SILENCE：用户沉默一段时间后触发，给 silenceMinutes（至少 60）。
        - WINDOW：每天在一个时间窗内择机主动互动，给 windowStartMinuteOfDay / windowEndMinuteOfDay（0～1439）。

        prompt 要写成给角色执行时看的自然指令，延续已有聊天事实，不编造双方未发生的经历或约定。
        用户没有给精确时间时，根据语义选择一个自然且保守的未来时间；不得安排到过去。
        只返回 JSON，不解释：
        {"task":{"prompt":"...","scheduleType":"ONCE","firstRunAtMillis":0,"recurringMinutes":null,"silenceMinutes":null,"windowStartMinuteOfDay":null,"windowEndMinuteOfDay":null}}
    """.trimIndent()
}

private fun JsonObject.string(key: String): String =
    this[key]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()

private fun JsonObject.long(key: String): Long? =
    this[key]?.jsonPrimitive?.longOrNull

private fun extractPlannerJson(raw: String): String {
    val start = raw.indexOf('{')
    val end = raw.lastIndexOf('}')
    require(start >= 0 && end > start) { "模型返回的事件格式异常" }
    return raw.substring(start, end + 1)
}

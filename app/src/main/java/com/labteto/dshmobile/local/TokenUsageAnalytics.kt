package com.labteto.dshmobile.local

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import com.labteto.dshmobile.local.usage.PromptTokenEstimateCache
import com.labteto.dshmobile.local.usage.TokenUsageDatabase
import com.labteto.dshmobile.local.usage.UsageLifetimeTotals
import com.labteto.dshmobile.local.usage.boundedForStorage
import com.labteto.dshmobile.local.usage.distinctForAccounting
import com.labteto.dshmobile.local.usage.normalizedForAccounting
import com.labteto.dshmobile.local.usage.nonNegativeUsageDifference
import com.labteto.dshmobile.local.usage.saturatingUsageAdd
import com.labteto.dshmobile.local.usage.saturatingUsageCostAdd
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val RECENT_TOKEN_LOG_RECORDS = 200

@Serializable
enum class TokenUsageAction {
    CHAT_REPLY,
    CHAT_REPAIR,
    CHAT_STATE_REFRESH,
    REPLY_SUGGESTIONS,
    GROUP_REPLY,
    GROUP_STATE_REFRESH,
    GROUP_ANNOUNCEMENT,
    PERSONA_AUTOFILL,
    PERSONA_INSPECTION,
    WORK_MAIN,
    WORK_SUBAGENT,
    AUTOMATION,
    AUTOMATION_CHAT,
    WEB_SEARCH,
    VISION,
    OTHER,
}

@Serializable
data class TokenPromptBreakdown(
    val systemBaseTokens: Int = 0,
    val personaStateTokens: Int = 0,
    val memoryRuleTokens: Int = 0,
    val historyTokens: Int = 0,
    val currentUserTokens: Int = 0,
    val toolDefinitionTokens: Int = 0,
    val otherSystemTokens: Int = 0,
) {
    val estimatedInputTokens: Long
        get() = listOf(
            systemBaseTokens,
            personaStateTokens,
            memoryRuleTokens,
            historyTokens,
            currentUserTokens,
            toolDefinitionTokens,
            otherSystemTokens,
        ).fold(0L) { total, value ->
            saturatingUsageAdd(total, value.toLong())
        }
}

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


@Serializable
data class TokenUsageContext(
    val mode: LocalUsageMode? = null,
    val sessionId: String? = null,
    val sessionTitle: String? = null,
    val turnId: String? = null,
    val runId: String? = null,
    val parentRunId: String? = null,
    val runKind: String? = null,
    val agentId: String? = null,
    val taskLabel: String? = null,
    val step: Int? = null,
    val action: TokenUsageAction = TokenUsageAction.OTHER,
)

data class ForegroundTokenUsageSeed(
    val turnId: String? = null,
    val runId: String? = null,
    val taskLabel: String? = null,
)

@Serializable
data class TokenUsageRecord(
    val requestId: String,
    val timestamp: Long,
    val model: String,
    val context: TokenUsageContext = TokenUsageContext(),
    val route: LocalModelRouteIdentity? = null,
    val inputTokens: Long = 0L,
    val cacheHitTokens: Long = 0L,
    val cacheMissTokens: Long = 0L,
    val outputTokens: Long = 0L,
    val reasoningTokens: Long = 0L,
    val estimatedCostCny: Double = 0.0,
    val reported: Boolean = false,
    val promptBreakdown: TokenPromptBreakdown = TokenPromptBreakdown(),
) {
    val totalTokens: Long get() = saturatingUsageAdd(inputTokens, outputTokens)
}

@Serializable
data class TokenUsageAggregate(
    val inputTokens: Long = 0L,
    val cacheHitTokens: Long = 0L,
    val cacheMissTokens: Long = 0L,
    val outputTokens: Long = 0L,
    val reasoningTokens: Long = 0L,
    val requestCount: Long = 0L,
    val unreportedRequestCount: Long = 0L,
    val estimatedCostCny: Double = 0.0,
) {
    val totalTokens: Long get() = saturatingUsageAdd(inputTokens, outputTokens)
    val cacheMeasuredTokens: Long get() = saturatingUsageAdd(cacheHitTokens, cacheMissTokens)
    val cacheHitRate: Double
        get() = if (cacheMeasuredTokens <= 0L) 0.0 else cacheHitTokens.toDouble() / cacheMeasuredTokens.toDouble()
}

data class TokenUsageDailyBucket(
    val epochDay: Long,
    val chat: TokenUsageAggregate = TokenUsageAggregate(),
    val work: TokenUsageAggregate = TokenUsageAggregate(),
    val other: TokenUsageAggregate = TokenUsageAggregate(),
) {
    fun forMode(mode: LocalUsageMode): TokenUsageAggregate = when (mode) {
        LocalUsageMode.CHAT -> chat
        LocalUsageMode.WORK -> work
    }
}

data class TokenUsageActionSummary(
    val action: TokenUsageAction,
    val aggregate: TokenUsageAggregate,
)

data class TokenUsageGroupSummary(
    val key: String,
    val title: String,
    val mode: LocalUsageMode,
    val aggregate: TokenUsageAggregate,
    val lastUsedAt: Long,
    val turnCount: Int = 0,
    val mainTokens: Long = 0L,
    val subagentTokens: Long = 0L,
)

data class TokenUsageModeAnalytics(
    val aggregate: TokenUsageAggregate = TokenUsageAggregate(),
    val turnCount: Int = 0,
    val averageInputPerTurn: Long = 0L,
    val averageOutputPerTurn: Long = 0L,
    val averageBackgroundPerTurn: Long = 0L,
    val mainTokens: Long = 0L,
    val subagentTokens: Long = 0L,
    val actions: List<TokenUsageActionSummary> = emptyList(),
)

data class TokenUsageAnalyticsSnapshot(
    val trackedSince: Long = 0L,
    val tracked: TokenUsageAggregate = TokenUsageAggregate(),
    val chat: TokenUsageModeAnalytics = TokenUsageModeAnalytics(),
    val work: TokenUsageModeAnalytics = TokenUsageModeAnalytics(),
    val days: List<TokenUsageDailyBucket> = emptyList(),
    val sessions: List<TokenUsageGroupSummary> = emptyList(),
    val tasks: List<TokenUsageGroupSummary> = emptyList(),
    val recentRecords: List<TokenUsageRecord> = emptyList(),
)

enum class TokenUsageGroupKind {
    SESSION,
    TASK,
}

data class TokenUsageAgentSummary(
    val key: String,
    val title: String,
    val runKind: String,
    val aggregate: TokenUsageAggregate,
)

data class TokenUsageGroupDetail(
    val kind: TokenUsageGroupKind,
    val key: String,
    val title: String,
    val aggregate: TokenUsageAggregate,
    val actions: List<TokenUsageActionSummary>,
    val agents: List<TokenUsageAgentSummary>,
    val records: List<TokenUsageRecord>,
)

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
)

internal fun buildToolTokenUsageContext(
    snapshot: LocalHarnessState,
    eventLog: LocalSessionEventLog,
    sessionId: String,
    callId: String?,
    action: TokenUsageAction,
    fallbackTaskLabel: String? = null,
): TokenUsageContext {
    val checkpoint = callId?.takeIf(String::isNotBlank)?.let { targetCallId ->
        eventLog.latestMatching(TOOL_USAGE_CHECKPOINT_TYPES) { data ->
            data["call_id"]?.jsonPrimitive?.contentOrNull == targetCallId
        }?.data
    }
    val runId = checkpoint?.get("run_id")?.jsonPrimitive?.contentOrNull
    val parentRunId = checkpoint?.get("parent_run_id")?.jsonPrimitive?.contentOrNull
    val agentId = checkpoint?.get("agent_id")?.jsonPrimitive?.contentOrNull
    val runKind = checkpoint?.get("run_kind")?.jsonPrimitive?.contentOrNull?.let { value ->
        LocalAgentRunKind.entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
    }
    val taskLabel = checkpoint?.get("input")?.jsonPrimitive?.contentOrNull
        ?.takeIf(String::isNotBlank)
        ?: fallbackTaskLabel
    return buildTokenUsageContext(
        snapshot = snapshot,
        action = action,
        turnId = runId,
        runId = runId,
        parentRunId = parentRunId,
        runKind = runKind,
        agentId = agentId,
        taskLabel = taskLabel,
        step = checkpoint?.get("step")?.jsonPrimitive?.intOrNull,
    ).copy(sessionId = sessionId)
}

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
        model = snapshot.model,
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
) = record(
    snapshot = snapshot,
    reply = reply,
    action = action,
    turnId = turnId,
    runId = runId,
    runKind = LocalAgentRunKind.FOREGROUND,
    taskLabel = taskLabel,
    step = step,
)

internal fun DeepSeekUsageTracker.recordAutomation(
    snapshot: LocalHarnessState,
    reply: LocalModelReply,
    action: TokenUsageAction,
    taskLabel: String,
) = record(
    snapshot = snapshot,
    reply = reply,
    action = action,
    runKind = LocalAgentRunKind.AUTOMATION,
    taskLabel = taskLabel,
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

@Singleton
class TokenUsageAnalyticsStore @Inject constructor(
    @ApplicationContext context: Context,
    private val json: Json,
) {
    private val lock = Any()
    private val directory = File(context.filesDir, "usage").apply { mkdirs() }
    private val legacy = LocalSessionEventLog(File(directory, "token_usage.jsonl"), json)
    private val database = TokenUsageDatabase(context, json)
    private var accumulator: TokenUsageAccumulator? = null
    private var accumulatorZone: ZoneId? = null
    private var lifetime = UsageLifetimeTotals()
    private var retainedDay = Long.MIN_VALUE
    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision.asStateFlow()

    fun append(record: TokenUsageRecord): Boolean = synchronized(lock) {
        ensureLoaded()
        val safe = record.boundedForStorage()
        val normalized = if (safe.requestId.isBlank()) safe.copy(requestId = UUID.randomUUID().toString()) else safe
        val result = database.append(normalized)
        if (!result.inserted) return@synchronized false
        lifetime = result.totals
        retainedDay = System.currentTimeMillis() / 86_400_000L
        if (result.pruned) rebuildAccumulator() else checkNotNull(accumulator).add(normalized)
        _revision.value = _revision.value + 1L
        true
    }

    fun snapshot(): TokenUsageAnalyticsSnapshot = synchronized(lock) {
        ensureLoaded()
        if (accumulatorZone != ZoneId.systemDefault()) rebuildAccumulator()
        checkNotNull(accumulator).snapshot().let { detail ->
            detail.copy(
                trackedSince = lifetime.since,
                tracked = lifetime.tracked,
                chat = detail.chat.copy(aggregate = lifetime.chat),
                work = detail.work.copy(aggregate = lifetime.work),
            )
        }
    }

    private fun ensureLoaded() {
        val today = System.currentTimeMillis() / 86_400_000L
        if (accumulator != null) {
            if (retainedDay != today) {
                if (database.enforceRetention()) rebuildAccumulator()
                retainedDay = today
            }
            return
        }
        database.migrate(legacy)
        lifetime = database.lifetimeTotals()
        database.enforceRetention()
        rebuildAccumulator()
        retainedDay = today
    }
    private fun rebuildAccumulator() {
        val zone = ZoneId.systemDefault()
        val next = TokenUsageAccumulator(zone)
        database.retainedRecords().forEach(next::add)
        accumulator = next
        accumulatorZone = zone
    }

    fun groupDetail(kind: TokenUsageGroupKind, key: String): TokenUsageGroupDetail? {
        val records = allRecords().distinctForAccounting().filter { record ->
            when (kind) {
                TokenUsageGroupKind.SESSION -> record.context.sessionId == key
                TokenUsageGroupKind.TASK ->
                    record.context.parentRunId == key || record.context.runId == key
            }
        }.toList()
        if (records.isEmpty()) return null

        val aggregate = MutableTokenAggregate()
        val actions = linkedMapOf<TokenUsageAction, MutableTokenAggregate>()
        val agents = linkedMapOf<String, MutableAgentGroup>()
        records.forEach { record ->
            aggregate.add(record)
            actions.getOrPut(record.context.action, ::MutableTokenAggregate).add(record)
            val runKind = record.context.runKind.orEmpty()
            val agentKey = record.context.agentId?.takeIf(String::isNotBlank)
                ?: if (runKind == LocalAgentRunKind.SUBAGENT.name.lowercase()) {
                    "subagent"
                } else {
                    "main"
                }
            agents.getOrPut(agentKey) {
                MutableAgentGroup(
                    key = agentKey,
                    title = record.context.agentId?.takeIf(String::isNotBlank)
                        ?: if (runKind == LocalAgentRunKind.SUBAGENT.name.lowercase()) "子代理" else "主代理",
                    runKind = runKind,
                )
            }.aggregate.add(record)
        }
        val latest = records.maxByOrNull(TokenUsageRecord::timestamp) ?: return null
        val mainTaskRecord = records
            .asSequence()
            .filter { it.context.runKind != LocalAgentRunKind.SUBAGENT.name.lowercase() }
            .filter { !it.context.taskLabel.isNullOrBlank() }
            .maxByOrNull(TokenUsageRecord::timestamp)
        val title = when (kind) {
            TokenUsageGroupKind.SESSION -> latest.context.sessionTitle?.takeIf(String::isNotBlank) ?: "对话"
            TokenUsageGroupKind.TASK -> mainTaskRecord?.context?.taskLabel
                ?: latest.context.taskLabel?.takeIf(String::isNotBlank)
                ?: "工作任务"
        }
        return TokenUsageGroupDetail(
            kind = kind,
            key = key,
            title = title,
            aggregate = aggregate.freeze(),
            actions = actions.freezeActions(),
            agents = agents.values.map { value ->
                TokenUsageAgentSummary(
                    key = value.key,
                    title = value.title,
                    runKind = value.runKind,
                    aggregate = value.aggregate.freeze(),
                )
            }.sortedByDescending { it.aggregate.totalTokens },
            records = records.sortedByDescending(TokenUsageRecord::timestamp).take(DETAIL_RECORDS),
        )
    }

    fun recordById(requestId: String): TokenUsageRecord? = synchronized(lock) {
        ensureLoaded()
        database.recordById(requestId)
    }

    private fun allRecords(): Sequence<TokenUsageRecord> = synchronized(lock) {
        ensureLoaded()
        database.retainedRecords().asSequence()
    }

    private companion object { const val DETAIL_RECORDS = 300 }
}

internal fun aggregateTokenUsageRecords(
    records: Sequence<TokenUsageRecord>,
    zone: ZoneId = ZoneId.systemDefault(),
): TokenUsageAnalyticsSnapshot = TokenUsageAccumulator(zone).apply {
    records.distinctForAccounting().forEach(::add)
}.snapshot()

/** Bounded by the retained request window in the store; updates do not reread the archive. */
internal class TokenUsageAccumulator(private val zone: ZoneId) {
    private val total = MutableTokenAggregate()
    private val chat = MutableTokenAggregate()
    private val work = MutableTokenAggregate()
    private val dayBuckets = linkedMapOf<Long, MutableDayBucket>()
    private val sessionGroups = linkedMapOf<String, MutableGroup>()
    private val taskGroups = linkedMapOf<String, MutableGroup>()
    private val chatActions = linkedMapOf<TokenUsageAction, MutableTokenAggregate>()
    private val workActions = linkedMapOf<TokenUsageAction, MutableTokenAggregate>()
    private val chatTurns = linkedSetOf<String>()
    private val workRuns = linkedSetOf<String>()
    private val recent = ArrayDeque<TokenUsageRecord>()
    private var trackedSince = 0L
    private var chatTurnInputTokens = 0L
    private val directVisibleReplies = linkedMapOf<String, TokenUsageRecord>()
    private var groupVisibleReplyOutputTokens = 0L
    private var groupVisibleReplyTotalTokens = 0L
    private var groupVisibleReplyCount = 0
    private var workMainTokens = 0L
    private var workSubagentTokens = 0L
    private var workTaskInputTokens = 0L
    private var workTaskOutputTokens = 0L

    fun add(raw: TokenUsageRecord) {
        val record = raw.normalizedForAccounting()
        if (trackedSince == 0L || record.timestamp < trackedSince) trackedSince = record.timestamp
        total.add(record)
        val epochDay = Instant.ofEpochMilli(record.timestamp).atZone(zone).toLocalDate().toEpochDay()
        val day = dayBuckets.getOrPut(epochDay, ::MutableDayBucket)
        when (record.context.mode) {
            LocalUsageMode.CHAT -> {
                chat.add(record)
                day.chat.add(record)
                chatActions.getOrPut(record.context.action, ::MutableTokenAggregate).add(record)
                record.context.turnId?.takeIf(String::isNotBlank)?.let { turnId ->
                    chatTurns += turnId
                    chatTurnInputTokens = saturatingUsageAdd(chatTurnInputTokens, record.inputTokens)
                    when (record.context.action) {
                        TokenUsageAction.CHAT_REPLY,
                        TokenUsageAction.CHAT_REPAIR,
                        -> {
                            val previous = directVisibleReplies[turnId]
                            if (previous == null || record.timestamp >= previous.timestamp) {
                                directVisibleReplies[turnId] = record
                            }
                        }
                        else -> Unit
                    }
                }
                if (record.context.action == TokenUsageAction.GROUP_REPLY) {
                    groupVisibleReplyOutputTokens = saturatingUsageAdd(groupVisibleReplyOutputTokens, record.outputTokens)
                    groupVisibleReplyTotalTokens = saturatingUsageAdd(groupVisibleReplyTotalTokens, record.totalTokens)
                    groupVisibleReplyCount += 1
                }
                record.context.sessionId?.takeIf(String::isNotBlank)?.let { sessionId ->
                    sessionGroups.getOrPut(sessionId) {
                        MutableGroup(
                            key = sessionId,
                            title = record.context.sessionTitle.orEmpty(),
                            mode = LocalUsageMode.CHAT,
                        )
                    }.add(record)
                }
            }
            LocalUsageMode.WORK -> {
                work.add(record)
                day.work.add(record)
                workActions.getOrPut(record.context.action, ::MutableTokenAggregate).add(record)
                val taskRunId = record.context.parentRunId?.takeIf(String::isNotBlank)
                    ?: record.context.runId?.takeIf(String::isNotBlank)
                taskRunId?.let { runId ->
                    workRuns += runId
                    workTaskInputTokens = saturatingUsageAdd(workTaskInputTokens, record.inputTokens)
                    workTaskOutputTokens = saturatingUsageAdd(workTaskOutputTokens, record.outputTokens)
                    taskGroups.getOrPut(runId) {
                        MutableGroup(
                            key = runId,
                            title = record.context.taskLabel.orEmpty(),
                            mode = LocalUsageMode.WORK,
                        )
                    }.add(record)
                }
                when (record.context.runKind) {
                    LocalAgentRunKind.FOREGROUND.name.lowercase() ->
                        workMainTokens = saturatingUsageAdd(workMainTokens, record.totalTokens)
                    LocalAgentRunKind.SUBAGENT.name.lowercase() ->
                        workSubagentTokens = saturatingUsageAdd(workSubagentTokens, record.totalTokens)
                }
            }
            null -> day.other.add(record)
        }
        recent.addLast(record)
        if (recent.size > RECENT_TOKEN_LOG_RECORDS) recent.removeFirst()
    }

    fun snapshot(): TokenUsageAnalyticsSnapshot {
        val chatTurnCount = chatTurns.size
        val directReplyOutputTokens = directVisibleReplies.values.fold(0L) { total, record ->
            saturatingUsageAdd(total, record.outputTokens)
        }
        val directReplyTotalTokens = directVisibleReplies.values.fold(0L) { total, record ->
            saturatingUsageAdd(total, record.totalTokens)
        }
        val visibleReplyCount = directVisibleReplies.size + groupVisibleReplyCount
        val visibleReplyOutputTokens = saturatingUsageAdd(directReplyOutputTokens, groupVisibleReplyOutputTokens)
        val visibleReplyTotalTokens = saturatingUsageAdd(directReplyTotalTokens, groupVisibleReplyTotalTokens)
        val chatBackgroundTokens = nonNegativeUsageDifference(
            saturatingUsageAdd(chat.inputTokens, chat.outputTokens),
            visibleReplyTotalTokens,
        )
        return TokenUsageAnalyticsSnapshot(
            trackedSince = trackedSince,
            tracked = total.freeze(),
            chat = TokenUsageModeAnalytics(
                aggregate = chat.freeze(),
                turnCount = chatTurnCount,
                averageInputPerTurn = average(chatTurnInputTokens, chatTurnCount),
                averageOutputPerTurn = average(visibleReplyOutputTokens, visibleReplyCount),
                averageBackgroundPerTurn = average(chatBackgroundTokens, chatTurnCount),
                actions = chatActions.freezeActions(),
            ),
            work = TokenUsageModeAnalytics(
                aggregate = work.freeze(),
                turnCount = workRuns.size,
                averageInputPerTurn = average(workTaskInputTokens, workRuns.size),
                averageOutputPerTurn = average(workTaskOutputTokens, workRuns.size),
                mainTokens = workMainTokens,
                subagentTokens = workSubagentTokens,
                actions = workActions.freezeActions(),
            ),
            days = dayBuckets.entries.map { (day, value) ->
                TokenUsageDailyBucket(
                    epochDay = day,
                    chat = value.chat.freeze(),
                    work = value.work.freeze(),
                    other = value.other.freeze(),
                )
            }.sortedBy(TokenUsageDailyBucket::epochDay),
            sessions = sessionGroups.values.map(MutableGroup::freeze)
                .sortedByDescending(TokenUsageGroupSummary::lastUsedAt),
            tasks = taskGroups.values.map(MutableGroup::freeze)
                .sortedByDescending(TokenUsageGroupSummary::lastUsedAt),
            recentRecords = recent.toList().asReversed(),
        )
    }
}

private class MutableTokenAggregate {
    var inputTokens: Long = 0L
    var cacheHitTokens: Long = 0L
    var cacheMissTokens: Long = 0L
    var outputTokens: Long = 0L
    var reasoningTokens: Long = 0L
    var requestCount: Long = 0L
    var unreportedRequestCount: Long = 0L
    var estimatedCostCny: Double = 0.0

    fun add(record: TokenUsageRecord) {
        inputTokens = saturatingUsageAdd(inputTokens, record.inputTokens)
        cacheHitTokens = saturatingUsageAdd(cacheHitTokens, record.cacheHitTokens)
        cacheMissTokens = saturatingUsageAdd(cacheMissTokens, record.cacheMissTokens)
        outputTokens = saturatingUsageAdd(outputTokens, record.outputTokens)
        reasoningTokens = saturatingUsageAdd(reasoningTokens, record.reasoningTokens)
        if (record.reported) {
            requestCount = saturatingUsageAdd(requestCount, 1L)
        } else {
            unreportedRequestCount = saturatingUsageAdd(unreportedRequestCount, 1L)
        }
        estimatedCostCny = saturatingUsageCostAdd(estimatedCostCny, record.estimatedCostCny)
    }

    fun freeze() = TokenUsageAggregate(
        inputTokens = inputTokens,
        cacheHitTokens = cacheHitTokens,
        cacheMissTokens = cacheMissTokens,
        outputTokens = outputTokens,
        reasoningTokens = reasoningTokens,
        requestCount = requestCount,
        unreportedRequestCount = unreportedRequestCount,
        estimatedCostCny = estimatedCostCny,
    )
}

private class MutableDayBucket {
    val chat = MutableTokenAggregate()
    val work = MutableTokenAggregate()
    val other = MutableTokenAggregate()
}

private class MutableGroup(
    val key: String,
    var title: String,
    val mode: LocalUsageMode,
) {
    private val aggregate = MutableTokenAggregate()
    private val turns = linkedSetOf<String>()
    private var lastUsedAt = 0L
    private var mainTokens = 0L
    private var subagentTokens = 0L

    fun add(record: TokenUsageRecord) {
        aggregate.add(record)
        val preferredTaskTitle = record.context.taskLabel?.takeIf(String::isNotBlank)
        if (
            mode == LocalUsageMode.WORK &&
            record.context.runKind != LocalAgentRunKind.SUBAGENT.name.lowercase() &&
            preferredTaskTitle != null
        ) {
            title = preferredTaskTitle
        } else if (title.isBlank()) {
            title = record.context.sessionTitle?.takeIf(String::isNotBlank)
                ?: preferredTaskTitle
                ?: title
        }
        record.context.turnId?.takeIf(String::isNotBlank)?.let(turns::add)
        lastUsedAt = maxOf(lastUsedAt, record.timestamp)
        when (record.context.runKind) {
            LocalAgentRunKind.FOREGROUND.name.lowercase() ->
                mainTokens = saturatingUsageAdd(mainTokens, record.totalTokens)
            LocalAgentRunKind.SUBAGENT.name.lowercase() ->
                subagentTokens = saturatingUsageAdd(subagentTokens, record.totalTokens)
        }
    }

    fun freeze() = TokenUsageGroupSummary(
        key = key,
        title = title.ifBlank { if (mode == LocalUsageMode.CHAT) "对话" else "工作任务" },
        mode = mode,
        aggregate = aggregate.freeze(),
        lastUsedAt = lastUsedAt,
        turnCount = turns.size,
        mainTokens = mainTokens,
        subagentTokens = subagentTokens,
    )
}

private class MutableAgentGroup(
    val key: String,
    val title: String,
    val runKind: String,
) {
    val aggregate = MutableTokenAggregate()
}

private fun Map<TokenUsageAction, MutableTokenAggregate>.freezeActions(): List<TokenUsageActionSummary> =
    entries.map { (action, aggregate) ->
        TokenUsageActionSummary(action, aggregate.freeze())
    }.sortedByDescending { it.aggregate.totalTokens }

private fun average(value: Long, count: Int): Long =
    if (count <= 0) 0L else value / count.toLong()

private val TOOL_USAGE_CHECKPOINT_TYPES = setOf(
    LOCAL_AGENT_RUN_CHECKPOINT_EVENT,
    LOCAL_SUBAGENT_RUN_CHECKPOINT_EVENT,
    LOCAL_AUTOMATION_RUN_CHECKPOINT_EVENT,
)

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

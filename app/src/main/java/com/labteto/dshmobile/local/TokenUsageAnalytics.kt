package com.labteto.dshmobile.local

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
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
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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
        get() = (
            systemBaseTokens +
                personaStateTokens +
                memoryRuleTokens +
                historyTokens +
                currentUserTokens +
                toolDefinitionTokens +
                otherSystemTokens
            ).toLong()
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

@Serializable
data class TokenUsageRecord(
    val requestId: String,
    val timestamp: Long,
    val model: String,
    val context: TokenUsageContext = TokenUsageContext(),
    val inputTokens: Long = 0L,
    val cacheHitTokens: Long = 0L,
    val cacheMissTokens: Long = 0L,
    val outputTokens: Long = 0L,
    val reasoningTokens: Long = 0L,
    val estimatedCostCny: Double = 0.0,
    val reported: Boolean = false,
    val promptBreakdown: TokenPromptBreakdown = TokenPromptBreakdown(),
) {
    val totalTokens: Long get() = inputTokens + outputTokens
}

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
    val totalTokens: Long get() = inputTokens + outputTokens
    val cacheMeasuredTokens: Long get() = cacheHitTokens + cacheMissTokens
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
        promptBreakdown = reply.promptBreakdown,
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
        val encoded = message.toString()
        val tokens = estimateModelTokens(encoded)
        val content = message["content"]?.toString().orEmpty()
        when {
            role == "user" && index == latestUser -> currentUser += tokens
            role != "system" -> history += tokens
            containsMemoryMarkers(content) -> memoryRule += tokens
            containsPersonaMarkers(content) -> personaState += tokens
            index == 0 -> systemBase += tokens
            else -> otherSystem += tokens
        }
    }
    return TokenPromptBreakdown(
        systemBaseTokens = systemBase,
        personaStateTokens = personaState,
        memoryRuleTokens = memoryRule,
        historyTokens = history,
        currentUserTokens = currentUser,
        toolDefinitionTokens = if (tools.isEmpty()) 0 else estimateModelTokens(tools.toString()),
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
    private val seenRequestIds = LinkedHashSet<String>()
    private val directory = File(context.filesDir, "usage").apply { mkdirs() }
    private val ledger = LocalSessionEventLog(
        file = File(directory, "token_usage.jsonl"),
        json = json,
        maxBytes = MAX_LEDGER_SEGMENT_BYTES,
    )
    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision.asStateFlow()

    init {
        ledger.pageBefore(limit = RECENT_DEDUPE_IDS).forEach { event ->
            decode(event)?.requestId?.takeIf(String::isNotBlank)?.let(seenRequestIds::add)
        }
    }

    fun append(record: TokenUsageRecord): Boolean = synchronized(lock) {
        val id = record.requestId.ifBlank { UUID.randomUUID().toString() }
        if (!seenRequestIds.add(id)) return@synchronized false
        trimSeenIds()
        val normalized = if (id == record.requestId) record else record.copy(requestId = id)
        ledger.append(
            USAGE_EVENT_TYPE,
            json.encodeToJsonElement(TokenUsageRecord.serializer(), normalized).jsonObject,
        )
        _revision.value = _revision.value + 1L
        true
    }

    fun snapshot(): TokenUsageAnalyticsSnapshot {
        val zone = ZoneId.systemDefault()
        val total = MutableTokenAggregate()
        val chat = MutableTokenAggregate()
        val work = MutableTokenAggregate()
        val dayBuckets = linkedMapOf<Long, MutableDayBucket>()
        val sessionGroups = linkedMapOf<String, MutableGroup>()
        val taskGroups = linkedMapOf<String, MutableGroup>()
        val chatActions = linkedMapOf<TokenUsageAction, MutableTokenAggregate>()
        val workActions = linkedMapOf<TokenUsageAction, MutableTokenAggregate>()
        val chatTurns = linkedSetOf<String>()
        val workRuns = linkedSetOf<String>()
        val recent = ArrayDeque<TokenUsageRecord>()
        var trackedSince = 0L
        var chatBackgroundTokens = 0L
        var chatTurnInputTokens = 0L
        var chatTurnOutputTokens = 0L
        var workMainTokens = 0L
        var workSubagentTokens = 0L

        allRecords().forEach { record ->
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
                        chatTurnInputTokens += record.inputTokens
                        chatTurnOutputTokens += record.outputTokens
                    }
                    if (record.context.action.isChatBackground()) chatBackgroundTokens += record.totalTokens
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
                    record.context.runId?.takeIf(String::isNotBlank)?.let { runId ->
                        workRuns += runId
                        taskGroups.getOrPut(runId) {
                            MutableGroup(
                                key = runId,
                                title = record.context.taskLabel.orEmpty(),
                                mode = LocalUsageMode.WORK,
                            )
                        }.add(record)
                    }
                    if (record.context.runKind == LocalAgentRunKind.SUBAGENT.name.lowercase()) {
                        workSubagentTokens += record.totalTokens
                    } else {
                        workMainTokens += record.totalTokens
                    }
                }
                null -> day.other.add(record)
            }
            recent.addLast(record)
            if (recent.size > RECENT_LOG_RECORDS) recent.removeFirst()
        }

        val chatTurnCount = chatTurns.size
        return TokenUsageAnalyticsSnapshot(
            trackedSince = trackedSince,
            tracked = total.freeze(),
            chat = TokenUsageModeAnalytics(
                aggregate = chat.freeze(),
                turnCount = chatTurnCount,
                averageInputPerTurn = average(chatTurnInputTokens, chatTurnCount),
                averageOutputPerTurn = average(chatTurnOutputTokens, chatTurnCount),
                averageBackgroundPerTurn = average(chatBackgroundTokens, chatTurnCount),
                actions = chatActions.freezeActions(),
            ),
            work = TokenUsageModeAnalytics(
                aggregate = work.freeze(),
                turnCount = workRuns.size,
                averageInputPerTurn = average(work.inputTokens, workRuns.size),
                averageOutputPerTurn = average(work.outputTokens, workRuns.size),
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

    fun groupDetail(kind: TokenUsageGroupKind, key: String): TokenUsageGroupDetail? {
        val records = allRecords().filter { record ->
            when (kind) {
                TokenUsageGroupKind.SESSION -> record.context.sessionId == key
                TokenUsageGroupKind.TASK -> record.context.runId == key
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
        val title = when (kind) {
            TokenUsageGroupKind.SESSION -> latest.context.sessionTitle?.takeIf(String::isNotBlank) ?: "对话"
            TokenUsageGroupKind.TASK -> latest.context.taskLabel?.takeIf(String::isNotBlank) ?: "工作任务"
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

    fun recordById(requestId: String): TokenUsageRecord? =
        allRecords().firstOrNull { it.requestId == requestId }

    private fun allRecords(): Sequence<TokenUsageRecord> =
        ledger.events().mapNotNull(::decode)

    private fun decode(event: LocalSessionEventLog.Event): TokenUsageRecord? {
        if (event.type != USAGE_EVENT_TYPE) return null
        return runCatching {
            json.decodeFromJsonElement(TokenUsageRecord.serializer(), event.data)
        }.getOrNull()
    }

    private fun trimSeenIds() {
        while (seenRequestIds.size > RECENT_DEDUPE_IDS) {
            val iterator = seenRequestIds.iterator()
            if (!iterator.hasNext()) return
            iterator.next()
            iterator.remove()
        }
    }

    private companion object {
        const val USAGE_EVENT_TYPE = "usage/request"
        const val RECENT_DEDUPE_IDS = 1_024
        const val RECENT_LOG_RECORDS = 200
        const val DETAIL_RECORDS = 300
        const val MAX_LEDGER_SEGMENT_BYTES = 8L * 1024L * 1024L
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
        inputTokens += record.inputTokens
        cacheHitTokens += record.cacheHitTokens
        cacheMissTokens += record.cacheMissTokens
        outputTokens += record.outputTokens
        reasoningTokens += record.reasoningTokens
        if (record.reported) requestCount += 1L else unreportedRequestCount += 1L
        estimatedCostCny += record.estimatedCostCny
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
        if (title.isBlank()) {
            title = record.context.sessionTitle?.takeIf(String::isNotBlank)
                ?: record.context.taskLabel?.takeIf(String::isNotBlank)
                ?: title
        }
        record.context.turnId?.takeIf(String::isNotBlank)?.let(turns::add)
        lastUsedAt = maxOf(lastUsedAt, record.timestamp)
        if (record.context.runKind == LocalAgentRunKind.SUBAGENT.name.lowercase()) {
            subagentTokens += record.totalTokens
        } else {
            mainTokens += record.totalTokens
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

private fun TokenUsageAction.isChatBackground(): Boolean = when (this) {
    TokenUsageAction.CHAT_REPLY,
    TokenUsageAction.CHAT_REPAIR,
    TokenUsageAction.GROUP_REPLY,
    -> false
    else -> true
}

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

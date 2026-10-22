package com.labteto.dshmobile.local

import android.content.Context
import com.labteto.dshmobile.local.model.LocalModelRouteIdentity
import com.labteto.dshmobile.local.runtime.LOCAL_AGENT_RUN_CHECKPOINT_EVENT
import com.labteto.dshmobile.local.runtime.LOCAL_AUTOMATION_RUN_CHECKPOINT_EVENT
import com.labteto.dshmobile.local.runtime.LOCAL_SUBAGENT_RUN_CHECKPOINT_EVENT
import com.labteto.dshmobile.local.runtime.LocalAgentRunKind
import com.labteto.dshmobile.local.session.LocalSessionEventLog
import com.labteto.dshmobile.local.usage.TokenUsageDatabase
import com.labteto.dshmobile.local.usage.LegacyUsageCounters
import com.labteto.dshmobile.local.usage.UsageLifetimeTotals
import com.labteto.dshmobile.local.usage.boundedForStorage
import com.labteto.dshmobile.local.usage.distinctForAccounting
import com.labteto.dshmobile.local.usage.nonNegativeUsageDifference
import com.labteto.dshmobile.local.usage.normalizedForAccounting
import com.labteto.dshmobile.local.usage.saturatingUsageAdd
import com.labteto.dshmobile.local.usage.saturatingUsageCostAdd
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
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val RECENT_TOKEN_LOG_RECORDS = 200


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

    /** SQLite owns all post-migration usage facts; the unpriced delta commits atomically. */
    fun append(record: TokenUsageRecord, unpricedTokens: Long = 0L): Boolean = synchronized(lock) {
        ensureLoaded()
        val safe = record.boundedForStorage()
        val normalized = if (safe.requestId.isBlank()) safe.copy(requestId = UUID.randomUUID().toString()) else safe
        val result = database.append(normalized, unpricedTokens)
        if (!result.inserted) return@synchronized false
        lifetime = result.totals
        retainedDay = System.currentTimeMillis() / 86_400_000L
        if (result.pruned) rebuildAccumulator() else checkNotNull(accumulator).add(normalized)
        _revision.value = _revision.value + 1L
        true
    }

    /**
     * Freeze the historical pre-SQLite gap once in database metadata, never repeatedly merge
     * old SharedPreferences counters with new requests. Safe to retry after process death.
     */
    fun adoptLegacyUsageOnce(legacy: LegacyUsageCounters): LegacyUsageCounters = synchronized(lock) {
        ensureLoaded()
        database.adoptLegacyUsageOnce(legacy)
    }

    fun legacyUsageProjection(): LegacyUsageCounters = synchronized(lock) {
        ensureLoaded()
        database.legacyUsageProjection()
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
                byModel = (detail.byModel.keys + lifetime.models.keys).associateWith { model ->
                    val retained = detail.byModel[model] ?: TokenUsageAnalyticsSnapshot()
                    val lifetimeModel = lifetime.models[model]
                    if (lifetimeModel == null) retained else retained.copy(
                        tracked = lifetimeModel.tracked,
                        chat = retained.chat.copy(aggregate = lifetimeModel.chat),
                        work = retained.work.copy(aggregate = lifetimeModel.work),
                    )
                },
                modelHistoryPartial = lifetime.modelHistoryPartial,
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
        database.backfillModelTotalsIfNeeded()
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

    fun groupDetail(
        kind: TokenUsageGroupKind,
        key: String,
        recordLimit: Int = DETAIL_RECORDS,
        sessionId: String? = null,
        modelFilter: String? = null,
    ): TokenUsageGroupDetail? {
        require(kind != TokenUsageGroupKind.TURN || !sessionId.isNullOrBlank()) {
            "Turn usage requires a source session"
        }
        val detailLimit = recordLimit.coerceIn(1, MAX_DETAIL_RECORDS)
        val records = allRecords().distinctForAccounting().filter { record ->
            (modelFilter == null || record.model == modelFilter) && when (kind) {
                TokenUsageGroupKind.TURN -> record.context.mode == LocalUsageMode.CHAT &&
                    record.context.sessionId == sessionId && record.context.turnId == key
                TokenUsageGroupKind.SESSION -> record.context.sessionId == key
                TokenUsageGroupKind.TASK ->
                    record.context.taskRunId == key || record.context.parentRunId == key || record.context.runId == key
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
            TokenUsageGroupKind.TURN -> "本轮用量"
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
            records = records.sortedByDescending(TokenUsageRecord::timestamp).take(detailLimit),
        )
    }

    /** Stable request-ID cursor into the retained SQLite ledger, independent of the short dashboard tail. */
    fun historyPage(
        model: String?,
        sinceMillis: Long?,
        cursor: String?,
        limit: Int,
    ): com.labteto.dshmobile.uicontract.UiUsageHistoryPage = synchronized(lock) {
        ensureLoaded()
        require(limit in 1..100) { "单页查询数量不合法" }
        val retained = database.retainedRecords().asReversed().filter { record ->
            (model == null || record.model == model) &&
                (sinceMillis == null || record.timestamp >= sinceMillis)
        }
        val start = if (cursor == null) 0 else {
            val index = retained.indexOfFirst { it.requestId == cursor }
            require(index >= 0) { "历史记录已变化，请从第一页重新加载" }
            index + 1
        }
        val items = retained.drop(start).take(limit)
        com.labteto.dshmobile.uicontract.UiUsageHistoryPage(
            records = items,
            nextCursor = items.lastOrNull()?.requestId?.takeIf { start + items.size < retained.size },
            totalMatching = retained.size,
        )
    }

    fun recordById(requestId: String): TokenUsageRecord? = synchronized(lock) {
        ensureLoaded()
        database.recordById(requestId)
    }

    fun turnSummaries(sessionId: String): Map<String, TokenUsageAggregate> =
        aggregateChatTurnUsage(allRecords(), sessionId)

    fun sessionSnapshot(sessionId: String): TokenUsageAnalyticsSnapshot =
        aggregateTokenUsageRecords(allRecords().filter { it.context.sessionId == sessionId })

    private fun allRecords(): Sequence<TokenUsageRecord> = synchronized(lock) {
        ensureLoaded()
        database.retainedRecords().asSequence()
    }

    private companion object {
        const val DETAIL_RECORDS = 300
        const val MAX_DETAIL_RECORDS = 2_000
    }
}

internal fun aggregateTokenUsageRecords(
    records: Sequence<TokenUsageRecord>,
    zone: ZoneId = ZoneId.systemDefault(),
): TokenUsageAnalyticsSnapshot = TokenUsageAccumulator(zone).apply {
    records.distinctForAccounting().forEach(::add)
}.snapshot()

/** Bounded by the retained request window in the store; updates do not reread the archive. */
internal class TokenUsageAccumulator(
    private val zone: ZoneId,
    private val collectModels: Boolean = true,
) {
    private val models = linkedMapOf<String, TokenUsageAccumulator>()
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
        if (collectModels) {
            models.getOrPut(record.model.trim().ifEmpty { "未知模型" }) {
                TokenUsageAccumulator(zone, collectModels = false)
            }.add(record)
        }
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
                val taskRunId = record.context.taskRunId?.takeIf(String::isNotBlank)
                    ?: record.context.parentRunId?.takeIf(String::isNotBlank)
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
            byModel = models.mapValues { (_, accumulator) -> accumulator.snapshot() },
        )
    }
}

private class MutableTokenAggregate {
    var inputTokens: Long = 0L
    var cacheHitTokens: Long = 0L
    var cacheMissTokens: Long = 0L
    var cacheWriteTokens: Long = 0L
    var outputTokens: Long = 0L
    var reasoningTokens: Long = 0L
    var requestCount: Long = 0L
    var unreportedRequestCount: Long = 0L
    var estimatedCostCny: Double = 0.0

    fun add(record: TokenUsageRecord) {
        inputTokens = saturatingUsageAdd(inputTokens, record.inputTokens)
        cacheHitTokens = saturatingUsageAdd(cacheHitTokens, record.cacheHitTokens)
        cacheMissTokens = saturatingUsageAdd(cacheMissTokens, record.cacheMissTokens)
        cacheWriteTokens = saturatingUsageAdd(cacheWriteTokens, record.cacheWriteTokens)
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
        cacheWriteTokens = cacheWriteTokens,
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


/** A turn is identified by both its source session and user message, including late background usage. */
internal fun aggregateChatTurnUsage(records: Sequence<TokenUsageRecord>, sessionId: String): Map<String, TokenUsageAggregate> {
    val turns = linkedMapOf<String, MutableTokenAggregate>()
    records.distinctForAccounting().filter {
        it.context.sessionId == sessionId && it.context.mode == LocalUsageMode.CHAT
    }.forEach { record ->
        record.context.turnId?.takeIf(String::isNotBlank)?.let { turn ->
            turns.getOrPut(turn, ::MutableTokenAggregate).add(record)
        }
    }
    return turns.mapValues { it.value.freeze() }
}

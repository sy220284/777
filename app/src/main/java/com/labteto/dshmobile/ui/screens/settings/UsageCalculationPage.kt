package com.labteto.dshmobile.ui.screens.settings

import com.shenyan.designsystem.foundation.SyMetric




import com.labteto.dshmobile.ui.shenyan.SyProgressBar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.TokenPromptBreakdown
import com.labteto.dshmobile.local.TokenUsageAction
import com.labteto.dshmobile.local.TokenUsageAggregate
import com.labteto.dshmobile.local.TokenUsageAnalyticsSnapshot
import com.labteto.dshmobile.local.TokenUsageDailyBucket
import com.labteto.dshmobile.local.TokenUsageGroupDetail
import com.labteto.dshmobile.local.TokenUsageGroupKind
import com.labteto.dshmobile.local.TokenUsageGroupSummary
import com.labteto.dshmobile.local.TokenUsageModeAnalytics
import com.labteto.dshmobile.local.TokenUsageRecord
import com.labteto.dshmobile.local.model.DeepSeekUsageSnapshot
import com.labteto.dshmobile.local.web.LocalSearchUsageSnapshot
import com.labteto.dshmobile.ui.shenyan.SyButton
import com.labteto.dshmobile.ui.shenyan.SyButtonVariant
import com.labteto.dshmobile.ui.shenyan.SySegmentedTabs
import com.labteto.dshmobile.ui.shenyan.SyPopupMenu
import com.labteto.dshmobile.ui.shenyan.SyMenuItem
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.withReadingWeight
import java.text.DateFormat
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import kotlin.math.max

internal sealed interface UsageDetailSelection {
    data class Group(
        val kind: TokenUsageGroupKind,
        val key: String,
        val title: String,
    ) : UsageDetailSelection

    data class Request(val requestId: String) : UsageDetailSelection
}

private enum class UsageRange(val days: Int) {
    WEEK(7),
    MONTH(30),
    QUARTER(90),
}

/**
 * Human-facing token overview.
 *
 * Provider usage is the only source for totals. Prompt composition is diagnostic only and lives in
 * request detail, so injected prompts/cache/reasoning can never be counted twice.
 */
@Composable
internal fun UsageCalculationPage(
    usage: DeepSeekUsageSnapshot,
    onOpenPricing: () -> Unit,
    analytics: TokenUsageAnalyticsSnapshot = TokenUsageAnalyticsSnapshot(),
    searchUsage: LocalSearchUsageSnapshot = LocalSearchUsageSnapshot(),
    onOpenGroup: (TokenUsageGroupKind, String, String) -> Unit = { _, _, _ -> },
    configuredModels: List<String> = emptyList(),
    selectedModel: String? = null,
    onSelectModel: (String?) -> Unit = {},
) {
    val availableModels = (configuredModels + analytics.byModel.keys).distinct().sorted()
    val visibleAnalytics = selectedModel?.let { analytics.byModel[it] ?: TokenUsageAnalyticsSnapshot() } ?: analytics
    val visibleUsage = selectedModel?.let { visibleAnalytics.tracked.asUsageSnapshot() } ?: usage
    var modelMenuExpanded by remember { androidx.compose.runtime.mutableStateOf(false) }
    var modeIndex by rememberSaveable { mutableIntStateOf(0) }
    var rangeIndex by rememberSaveable { mutableIntStateOf(0) }
    var showAllGroups by rememberSaveable(modeIndex) {
        androidx.compose.runtime.mutableStateOf(false)
    }
    var selectedEpochDay by rememberSaveable { mutableLongStateOf(LocalDate.now().toEpochDay()) }
    val mode = if (modeIndex == 0) LocalUsageMode.CHAT else LocalUsageMode.WORK
    val modeAnalytics = if (mode == LocalUsageMode.CHAT) visibleAnalytics.chat else visibleAnalytics.work
    val range = UsageRange.entries[rangeIndex.coerceIn(0, UsageRange.entries.lastIndex)]
    val days = remember(visibleAnalytics.days, mode, range) {
        dailyWindow(visibleAnalytics.days, mode, range.days)
    }
    val selectedDay = days.firstOrNull { it.first == selectedEpochDay } ?: days.lastOrNull()
    val colors = com.shenyan.designsystem.foundation.LocalSyColors.current
    val allModelsLabel = stringResource(R.string.usage_model_all)

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.usage_model_filter_label),
            style = DsType.std14Strong.withReadingWeight(),
            color = colors.text,
            modifier = Modifier.weight(1f),
        )
        Box {
            SyButton(
                text = (selectedModel ?: stringResource(R.string.usage_model_all)) + " ▾",
                onClick = { modelMenuExpanded = true },
                variant = SyButtonVariant.Ghost,
            )
            SyPopupMenu(
                expanded = modelMenuExpanded,
                onDismiss = { modelMenuExpanded = false },
                items = (listOf<String?>(null) + availableModels).map { model ->
                    SyMenuItem(
                        text = model ?: allModelsLabel,
                        onClick = { onSelectModel(model) },
                    )
                },
            )
        }
    }
    DeviceUsageHero(visibleUsage, showEstimatedCost = selectedModel == null || visibleUsage.estimatedCostCny > 0.0)
    if (selectedModel != null && analytics.modelHistoryPartial) {
        Text(
            stringResource(R.string.usage_model_history_partial),
            style = DsType.caption11.withReadingWeight(),
            color = colors.textTertiary,
        )
    }

    SySegmentedTabs(
        labels = listOf(
            stringResource(R.string.usage_mode_chat),
            stringResource(R.string.usage_mode_work),
            stringResource(R.string.usage_mode_search),
        ),
        selectedIndex = modeIndex,
        onSelect = { modeIndex = it },
        modifier = Modifier.fillMaxWidth().testTag("usage-mode-tabs"),
    )

    if (modeIndex == 2) {
        UsageSearchSection(searchUsage, range.days)
        UsageRangeSelector(selectedIndex = rangeIndex, onSelect = { rangeIndex = it })
        return
    }

    SectionHeading(stringResource(R.string.usage_mode_detail))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(SyMetric.gapMd),
    ) {
        CompactMetric(
            label = stringResource(R.string.usage_calculation_total_tokens),
            value = formatTokens(modeAnalytics.aggregate.totalTokens),
            modifier = Modifier.weight(1f),
            testTag = "usagePrimaryMetric",
        )
        CompactMetric(
            label = stringResource(R.string.usage_today),
            value = formatTokens(todayAggregate(days).totalTokens),
            modifier = Modifier.weight(1f),
            testTag = "usagePrimaryMetric",
        )
    }

    if (selectedModel == null && analytics.byModel.isNotEmpty()) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(SyMetric.gapMd),
        ) {
            Text(stringResource(R.string.fluid_model_composition), style = DsType.std14Strong,
                color = colors.text)
            val total = analytics.byModel.values.sumOf { it.tracked.totalTokens.toDouble() }.coerceAtLeast(1.0)
            androidx.compose.foundation.lazy.LazyColumn(
                Modifier.fillMaxWidth().heightIn(max = 260.dp),
                verticalArrangement = Arrangement.spacedBy(SyMetric.gapLg),
            ) {
                analytics.byModel.entries.sortedByDescending { it.value.tracked.totalTokens }.forEach { entry ->
                    item(key = entry.key) {
                        // Design #04: the model-composition rows are functional filter
                        // entrances backed by the same selectedModel source as the menu.
                        Surface(
                            onClick = { onSelectModel(entry.key) },
                            modifier = Modifier.fillMaxWidth()
                                .testTag("usage-model-select:" + entry.key),
                            shape = DsShapes.row,
                            color = androidx.compose.ui.graphics.Color.Transparent,
                        ) {
                            Column(
                                modifier = Modifier.padding(SyMetric.gapMd),
                                verticalArrangement = Arrangement.spacedBy(SyMetric.gapMd),
                            ) {
                                Row {
                                    Text(
                                        entry.key,
                                        style = DsType.small13Strong.withReadingWeight(),
                                        modifier = Modifier.weight(1f),
                                        color = colors.text,
                                    )
                                    Text(
                                        formatTokens(entry.value.tracked.totalTokens),
                                        style = DsType.caption11.copy(fontFeatureSettings = "tnum"),
                                        color = colors.textSecondary,
                                    )
                                }
                                SyProgressBar(
                                    (entry.value.tracked.totalTokens.toDouble() / total).toFloat(),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(SyMetric.gapLg),
            verticalArrangement = Arrangement.spacedBy(SyMetric.gapLg),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(SyMetric.gapMd)) {
                Text(
                    stringResource(R.string.usage_daily_title),
                    style = DsType.std14Strong.withReadingWeight(),
                    color = colors.text,
                    modifier = Modifier.fillMaxWidth(),
                )
                UsageRangeSelector(
                    selectedIndex = rangeIndex,
                    onSelect = { rangeIndex = it },
                )
            }
            UsageDayChart(
                days = days,
                selectedEpochDay = selectedDay?.first ?: selectedEpochDay,
                onSelect = { selectedEpochDay = it },
            )
            selectedDay?.let { (day, aggregate) ->
                Column(verticalArrangement = Arrangement.spacedBy(SyMetric.gapSm)) {
                    Text(
                        formatEpochDay(day),
                        style = DsType.small13Strong.withReadingWeight(),
                        color = colors.text,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "${stringResource(R.string.usage_calculation_input)} ${formatTokens(aggregate.inputTokens)} · " +
                            "${stringResource(R.string.usage_calculation_output)} ${formatTokens(aggregate.outputTokens)}",
                        style = DsType.caption11.withReadingWeight(),
                        color = colors.textSecondary,
                    )
                }
            }
        }
    }

    UsageEfficiency(mode, modeAnalytics)

    val groups = if (mode == LocalUsageMode.CHAT) visibleAnalytics.sessions else visibleAnalytics.tasks
    SectionHeading(
        if (mode == LocalUsageMode.CHAT) {
            stringResource(R.string.usage_conversations)
        } else {
            stringResource(R.string.usage_tasks)
        },
    )
    if (groups.isEmpty()) {
        EmptyUsageDetail()
    } else {
        Column(modifier = Modifier.fillMaxWidth()) {
            Column {
                val visibleGroups = if (showAllGroups) groups else groups.take(12)
                visibleGroups.forEachIndexed { index, group ->
                    UsageGroupRow(
                        group = group,
                        onClick = {
                            onOpenGroup(
                                if (mode == LocalUsageMode.CHAT) {
                                    TokenUsageGroupKind.SESSION
                                } else {
                                    TokenUsageGroupKind.TASK
                                },
                                group.key,
                                group.title,
                            )
                        },
                    )
                    if (index != visibleGroups.lastIndex) UsageDivider()
                }
            }
        }
        if (groups.size > 12) {
            SyButton(
                text = if (showAllGroups) {
                    stringResource(R.string.usage_show_recent)
                } else {
                    stringResource(R.string.usage_show_all, groups.size)
                },
                onClick = { showAllGroups = !showAllGroups },
                variant = SyButtonVariant.Ghost,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    if (selectedModel == null && usage.totalTokens > analytics.tracked.totalTokens) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = DsShapes.row,
            color = colors.selected,
        ) {
            Column(
                modifier = Modifier.padding(SyMetric.gapLg),
                verticalArrangement = Arrangement.spacedBy(SyMetric.gapSm),
            ) {
                if (analytics.trackedSince > 0L) {
                    Text(
                        stringResource(
                            R.string.usage_detailed_since,
                            DateFormat.getDateInstance(DateFormat.MEDIUM)
                                .format(Date(analytics.trackedSince)),
                        ),
                        style = DsType.small13Strong.withReadingWeight(),
                        color = colors.text,
                    )
                }
                Text(
                    stringResource(R.string.usage_legacy_notice),
                    style = DsType.caption11.withReadingWeight(),
                    color = colors.textSecondary,
                )
            }
        }
    }

    if (visibleUsage.unreportedRequestCount > 0L || (selectedModel == null && usage.unpricedTokens > 0L)) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = DsShapes.row,
            color = colors.warning.copy(alpha = .18f),
        ) {
            Column(
                modifier = Modifier.padding(SyMetric.gapLg),
                verticalArrangement = Arrangement.spacedBy(SyMetric.gapSm),
            ) {
                Text(
                    stringResource(R.string.usage_calculation_partial),
                    style = DsType.small13Strong.withReadingWeight(),
                    color = colors.warningInk,
                )
                if (visibleUsage.unreportedRequestCount > 0L) {
                    Text(
                        stringResource(R.string.local_usage_unreported, visibleUsage.unreportedRequestCount),
                        style = DsType.caption11.withReadingWeight(),
                        color = colors.textSecondary,
                    )
                }
                if (selectedModel == null && usage.unpricedTokens > 0L) {
                    Text(
                        stringResource(R.string.local_usage_unpriced, formatNumber(usage.unpricedTokens)),
                        style = DsType.caption11.withReadingWeight(),
                        color = colors.textSecondary,
                    )
                }
            }
        }
    }

    Text(
        stringResource(R.string.usage_calculation_disclaimer),
        style = DsType.caption11.withReadingWeight(),
        color = colors.textTertiary,
    )
    SyButton(
        text = stringResource(R.string.usage_calculation_view_prices),
        onClick = onOpenPricing,
        variant = SyButtonVariant.Ghost,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
internal fun UsageLogPage(
    analytics: TokenUsageAnalyticsSnapshot,
    onOpenRequest: (String) -> Unit,
    selectedModel: String? = null,
) {
    val colors = com.shenyan.designsystem.foundation.LocalSyColors.current
    val visibleAnalytics = selectedModel?.let { analytics.byModel[it] ?: TokenUsageAnalyticsSnapshot() } ?: analytics
    if (visibleAnalytics.recentRecords.isEmpty()) {
        EmptyUsageDetail(message = stringResource(R.string.usage_log_empty))
        return
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Column {
            val records = visibleAnalytics.recentRecords.take(120)
            records.forEachIndexed { index, record ->
                UsageRequestRow(record = record, onClick = { onOpenRequest(record.requestId) })
                if (index != records.lastIndex) UsageDivider()
            }
        }
    }
}

@Composable
internal fun UsageGroupDetailPage(
    detail: TokenUsageGroupDetail?,
    onOpenRequest: (String) -> Unit,
) {
    if (detail == null) {
        EmptyUsageDetail()
        return
    }
    val colors = com.shenyan.designsystem.foundation.LocalSyColors.current
    UsageAggregateHero(
        title = detail.title,
        aggregate = detail.aggregate,
    )

    if (detail.agents.size > 1 || detail.agents.any { it.runKind.isNotBlank() }) {
        SectionHeading(stringResource(R.string.usage_agents))
        Column(modifier = Modifier.fillMaxWidth()) {
            Column {
                detail.agents.forEachIndexed { index, agent ->
                    UsageValueLine(agent.title, formatTokens(agent.aggregate.totalTokens))
                    if (index != detail.agents.lastIndex) UsageDivider()
                }
            }
        }
    }

    if (detail.actions.isNotEmpty()) {
        SectionHeading(stringResource(R.string.usage_actions))
        Column(modifier = Modifier.fillMaxWidth()) {
            Column {
                detail.actions.forEachIndexed { index, item ->
                    UsageValueLine(actionLabel(item.action), formatTokens(item.aggregate.totalTokens))
                    if (index != detail.actions.lastIndex) UsageDivider()
                }
            }
        }
    }

    SectionHeading(stringResource(R.string.usage_log_title))
    Column(modifier = Modifier.fillMaxWidth()) {
        Column {
            val records = detail.records.take(120)
            records.forEachIndexed { index, record ->
                UsageRequestRow(record = record, onClick = { onOpenRequest(record.requestId) })
                if (index != records.lastIndex) UsageDivider()
            }
        }
    }
}

@Composable
internal fun UsageRequestDetailPage(record: TokenUsageRecord?) {
    if (record == null) {
        EmptyUsageDetail()
        return
    }
    val colors = com.shenyan.designsystem.foundation.LocalSyColors.current
    UsageAggregateHero(
        title = actionLabel(record.context.action),
        aggregate = TokenUsageAggregate(
            inputTokens = record.inputTokens,
            cacheHitTokens = record.cacheHitTokens,
            cacheMissTokens = record.cacheMissTokens,
            cacheWriteTokens = record.cacheWriteTokens,
            outputTokens = record.outputTokens,
            reasoningTokens = record.reasoningTokens,
            requestCount = if (record.reported) 1 else 0,
            unreportedRequestCount = if (record.reported) 0 else 1,
            estimatedCostCny = record.estimatedCostCny,
        ),
        badge = stringResource(R.string.usage_exact_badge),
    )

    Column(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(SyMetric.gapLg),
            verticalArrangement = Arrangement.spacedBy(SyMetric.gapMd),
        ) {
            UsageValueLine(
                stringResource(R.string.usage_calculation_input),
                formatNumber(record.inputTokens),
                padded = false,
            )
            UsageValueLine(
                stringResource(R.string.usage_calculation_hit),
                formatNumber(record.cacheHitTokens),
                padded = false,
            )
            UsageValueLine(
                stringResource(R.string.usage_calculation_miss),
                formatNumber(record.cacheMissTokens),
                padded = false,
            )
            UsageValueLine(
                stringResource(R.string.usage_calculation_cache_write),
                formatNumber(record.cacheWriteTokens),
                padded = false,
            )
            UsageValueLine(
                stringResource(R.string.usage_calculation_output),
                formatNumber(record.outputTokens),
                padded = false,
            )
            Text(
                stringResource(
                    R.string.usage_calculation_reasoning_note,
                    formatNumber(record.reasoningTokens),
                ),
                style = DsType.caption11.withReadingWeight(),
                color = colors.textTertiary,
            )
        }
    }

    PromptBreakdownCard(record.promptBreakdown)

    val modelStep = record.context.step?.let { step ->
        stringResource(R.string.usage_model_step, record.model, step)
    } ?: record.model
    Text(
        "$modelStep · ${formatTime(record.timestamp)}",
        style = DsType.caption11.withReadingWeight(),
        color = colors.textTertiary,
    )
}

/** A UI-only projection; server-reported request accounting remains in TokenUsageAnalyticsStore. */
private fun TokenUsageAggregate.asUsageSnapshot() = DeepSeekUsageSnapshot(
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

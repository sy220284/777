package com.labteto.dshmobile.ui.screens.settings

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.DeepSeekUsageSnapshot
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
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.components.DsSegmentedTabs
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
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
    onOpenGroup: (TokenUsageGroupKind, String, String) -> Unit = { _, _, _ -> },
) {
    var modeIndex by rememberSaveable { mutableIntStateOf(0) }
    var rangeIndex by rememberSaveable { mutableIntStateOf(0) }
    var selectedEpochDay by rememberSaveable { mutableLongStateOf(LocalDate.now().toEpochDay()) }
    val mode = if (modeIndex == 0) LocalUsageMode.CHAT else LocalUsageMode.WORK
    val modeAnalytics = if (mode == LocalUsageMode.CHAT) analytics.chat else analytics.work
    val range = UsageRange.entries[rangeIndex.coerceIn(0, UsageRange.entries.lastIndex)]
    val days = remember(analytics.days, mode, range) {
        dailyWindow(analytics.days, mode, range.days)
    }
    val selectedDay = days.firstOrNull { it.first == selectedEpochDay } ?: days.lastOrNull()
    val colors = DsTheme.colors

    DeviceUsageHero(usage)

    DsSegmentedTabs(
        labels = listOf(
            stringResource(R.string.usage_mode_chat),
            stringResource(R.string.usage_mode_work),
        ),
        selectedIndex = modeIndex,
        onSelect = { modeIndex = it },
    )

    SectionHeading(stringResource(R.string.usage_mode_detail))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
    ) {
        CompactMetric(
            label = stringResource(R.string.usage_calculation_total_tokens),
            value = formatTokens(modeAnalytics.aggregate.totalTokens),
            modifier = Modifier.weight(1f),
        )
        CompactMetric(
            label = stringResource(R.string.usage_today),
            value = formatTokens(todayAggregate(days).totalTokens),
            modifier = Modifier.weight(1f),
        )
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = DsShapes.block,
        color = colors.bgLayer1,
    ) {
        Column(
            modifier = Modifier.padding(DsSpacing.medium),
            verticalArrangement = Arrangement.spacedBy(DsSpacing.medium),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.usage_daily_title),
                    style = DsType.std14Strong,
                    color = colors.labelPrimary,
                    modifier = Modifier.weight(1f),
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        formatEpochDay(day),
                        style = DsType.small13Strong,
                        color = colors.labelPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "${stringResource(R.string.usage_calculation_input)} ${formatTokens(aggregate.inputTokens)} · " +
                            "${stringResource(R.string.usage_calculation_output)} ${formatTokens(aggregate.outputTokens)}",
                        style = DsType.caption11,
                        color = colors.labelSecondary,
                    )
                }
            }
        }
    }

    UsageEfficiency(mode, modeAnalytics)

    val groups = if (mode == LocalUsageMode.CHAT) analytics.sessions else analytics.tasks
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
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = DsShapes.block,
            color = colors.bgLayer1,
        ) {
            Column {
                val visibleGroups = groups.take(12)
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
    }

    if (usage.totalTokens > analytics.tracked.totalTokens) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = DsShapes.row,
            color = colors.accentTertiary,
        ) {
            Column(
                modifier = Modifier.padding(DsSpacing.medium),
                verticalArrangement = Arrangement.spacedBy(DsSpacing.xsmall),
            ) {
                if (analytics.trackedSince > 0L) {
                    Text(
                        stringResource(
                            R.string.usage_detailed_since,
                            DateFormat.getDateInstance(DateFormat.MEDIUM)
                                .format(Date(analytics.trackedSince)),
                        ),
                        style = DsType.small13Strong,
                        color = colors.labelPrimary,
                    )
                }
                Text(
                    stringResource(R.string.usage_legacy_notice),
                    style = DsType.caption11,
                    color = colors.labelSecondary,
                )
            }
        }
    }

    if (usage.unreportedRequestCount > 0L || usage.unpricedTokens > 0L) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = DsShapes.row,
            color = colors.warnTertiary,
        ) {
            Column(
                modifier = Modifier.padding(DsSpacing.medium),
                verticalArrangement = Arrangement.spacedBy(DsSpacing.xsmall),
            ) {
                Text(
                    stringResource(R.string.usage_calculation_partial),
                    style = DsType.small13Strong,
                    color = colors.warnLabel,
                )
                if (usage.unreportedRequestCount > 0L) {
                    Text(
                        stringResource(R.string.local_usage_unreported, usage.unreportedRequestCount),
                        style = DsType.caption11,
                        color = colors.labelSecondary,
                    )
                }
                if (usage.unpricedTokens > 0L) {
                    Text(
                        stringResource(R.string.local_usage_unpriced, formatNumber(usage.unpricedTokens)),
                        style = DsType.caption11,
                        color = colors.labelSecondary,
                    )
                }
            }
        }
    }

    Text(
        stringResource(R.string.usage_calculation_disclaimer),
        style = DsType.caption11,
        color = colors.labelTertiary,
    )
    DsButton(
        text = stringResource(R.string.usage_calculation_view_prices),
        onClick = onOpenPricing,
        variant = DsButtonVariant.Ghost,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
internal fun UsageLogPage(
    analytics: TokenUsageAnalyticsSnapshot,
    onOpenRequest: (String) -> Unit,
) {
    val colors = DsTheme.colors
    if (analytics.recentRecords.isEmpty()) {
        EmptyUsageDetail(message = stringResource(R.string.usage_log_empty))
        return
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = DsShapes.block,
        color = colors.bgLayer1,
    ) {
        Column {
            val records = analytics.recentRecords.take(120)
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
    val colors = DsTheme.colors
    UsageAggregateHero(
        title = detail.title,
        aggregate = detail.aggregate,
    )

    if (detail.agents.size > 1 || detail.agents.any { it.runKind.isNotBlank() }) {
        SectionHeading(stringResource(R.string.usage_agents))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = DsShapes.block,
            color = colors.bgLayer1,
        ) {
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
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = DsShapes.block,
            color = colors.bgLayer1,
        ) {
            Column {
                detail.actions.forEachIndexed { index, item ->
                    UsageValueLine(actionLabel(item.action), formatTokens(item.aggregate.totalTokens))
                    if (index != detail.actions.lastIndex) UsageDivider()
                }
            }
        }
    }

    SectionHeading(stringResource(R.string.usage_log_title))
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = DsShapes.block,
        color = colors.bgLayer1,
    ) {
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
    val colors = DsTheme.colors
    UsageAggregateHero(
        title = actionLabel(record.context.action),
        aggregate = TokenUsageAggregate(
            inputTokens = record.inputTokens,
            cacheHitTokens = record.cacheHitTokens,
            cacheMissTokens = record.cacheMissTokens,
            outputTokens = record.outputTokens,
            reasoningTokens = record.reasoningTokens,
            requestCount = if (record.reported) 1 else 0,
            unreportedRequestCount = if (record.reported) 0 else 1,
            estimatedCostCny = record.estimatedCostCny,
        ),
        badge = stringResource(R.string.usage_exact_badge),
    )

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = DsShapes.block,
        color = colors.bgLayer1,
    ) {
        Column(
            modifier = Modifier.padding(DsSpacing.medium),
            verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
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
                stringResource(R.string.usage_calculation_output),
                formatNumber(record.outputTokens),
                padded = false,
            )
            Text(
                stringResource(
                    R.string.usage_calculation_reasoning_note,
                    formatNumber(record.reasoningTokens),
                ),
                style = DsType.caption11,
                color = colors.labelTertiary,
            )
        }
    }

    PromptBreakdownCard(record.promptBreakdown)

    val modelStep = record.context.step?.let { step ->
        stringResource(R.string.usage_model_step, record.model, step)
    } ?: record.model
    Text(
        "$modelStep · ${formatTime(record.timestamp)}",
        style = DsType.caption11,
        color = colors.labelTertiary,
    )
}

@Composable
private fun DeviceUsageHero(usage: DeepSeekUsageSnapshot) {
    val colors = DsTheme.colors
    val cacheValue = if (usage.cacheMeasuredTokens > 0L) {
        String.format(Locale.US, "%.1f%%", usage.cacheHitRate * 100.0)
    } else {
        stringResource(R.string.usage_calculation_no_data)
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = DsShapes.dialog,
        color = colors.accentTertiary,
    ) {
        Column(
            modifier = Modifier.padding(DsSpacing.large),
            verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
        ) {
            Text(
                stringResource(R.string.usage_device_total),
                style = DsType.small13Strong,
                color = colors.labelSecondary,
            )
            Text(
                formatTokens(usage.totalTokens),
                style = DsType.display24,
                color = colors.labelPrimary,
            )
            Text(
                "${stringResource(R.string.usage_calculation_input)} ${formatTokens(usage.inputTokens)} · " +
                    "${stringResource(R.string.usage_calculation_output)} ${formatTokens(usage.outputTokens)} · " +
                    "¥${String.format(Locale.US, "%.4f", usage.estimatedCostCny)}",
                style = DsType.small13,
                color = colors.labelSecondary,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.local_usage_cache_hit_rate),
                    style = DsType.caption11,
                    color = colors.labelTertiary,
                    modifier = Modifier.weight(1f),
                )
                Text(cacheValue, style = DsType.small13Strong, color = colors.labelPrimary)
            }
        }
    }
}

@Composable
private fun UsageEfficiency(mode: LocalUsageMode, analytics: TokenUsageModeAnalytics) {
    SectionHeading(
        if (mode == LocalUsageMode.CHAT) {
            stringResource(R.string.usage_mode_chat)
        } else {
            stringResource(R.string.usage_mode_work)
        },
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
    ) {
        if (mode == LocalUsageMode.CHAT) {
            CompactMetric(
                stringResource(R.string.usage_avg_sent),
                formatTokens(analytics.averageInputPerTurn),
                Modifier.weight(1f),
            )
            CompactMetric(
                stringResource(R.string.usage_avg_received),
                formatTokens(analytics.averageOutputPerTurn),
                Modifier.weight(1f),
            )
        } else {
            CompactMetric(
                stringResource(R.string.usage_main_agent),
                formatTokens(analytics.mainTokens),
                Modifier.weight(1f),
            )
            CompactMetric(
                stringResource(R.string.usage_subagent),
                formatTokens(analytics.subagentTokens),
                Modifier.weight(1f),
            )
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
    ) {
        CompactMetric(
            if (mode == LocalUsageMode.CHAT) {
                stringResource(R.string.usage_avg_background)
            } else {
                stringResource(R.string.usage_avg_task_input)
            },
            if (mode == LocalUsageMode.CHAT) {
                formatTokens(analytics.averageBackgroundPerTurn)
            } else {
                formatTokens(analytics.averageInputPerTurn)
            },
            Modifier.weight(1f),
        )
        CompactMetric(
            stringResource(R.string.local_usage_cache_hit_rate),
            if (analytics.aggregate.cacheMeasuredTokens > 0L) {
                String.format(Locale.US, "%.1f%%", analytics.aggregate.cacheHitRate * 100.0)
            } else {
                stringResource(R.string.usage_calculation_no_data)
            },
            Modifier.weight(1f),
        )
    }
}

@Composable
private fun UsageRangeSelector(selectedIndex: Int, onSelect: (Int) -> Unit) {
    val colors = DsTheme.colors
    val labels = listOf(
        stringResource(R.string.usage_range_7),
        stringResource(R.string.usage_range_30),
        stringResource(R.string.usage_range_90),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(DsSpacing.tiny)) {
        labels.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            Text(
                text = label,
                style = DsType.caption11,
                color = if (selected) colors.accent else colors.labelTertiary,
                modifier = Modifier
                    .clip(DsShapes.pillFull)
                    .background(if (selected) colors.accentTertiary else colors.bgLayer2)
                    .clickable { onSelect(index) }
                    .padding(horizontal = DsSpacing.small, vertical = DsSpacing.tiny),
            )
        }
    }
}

@Composable
private fun UsageDayChart(
    days: List<Pair<Long, TokenUsageAggregate>>,
    selectedEpochDay: Long,
    onSelect: (Long) -> Unit,
) {
    val colors = DsTheme.colors
    val scrollState = rememberScrollState()
    val maxTokens = max(1L, days.maxOfOrNull { it.second.totalTokens } ?: 1L)
    LaunchedEffect(days.size, scrollState.maxValue) {
        if (scrollState.maxValue > 0) scrollState.scrollTo(scrollState.maxValue)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(136.dp)
            .horizontalScroll(scrollState),
        horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
        verticalAlignment = Alignment.Bottom,
    ) {
        days.forEach { (epochDay, aggregate) ->
            val selected = epochDay == selectedEpochDay
            val totalFraction = aggregate.totalTokens.toFloat() / maxTokens.toFloat()
            val totalHeight = 92.dp * totalFraction.coerceIn(0.04f, 1f)
            val inputFraction = if (aggregate.totalTokens > 0L) {
                aggregate.inputTokens.toFloat() / aggregate.totalTokens.toFloat()
            } else {
                0f
            }
            Column(
                modifier = Modifier
                    .width(28.dp)
                    .clickable { onSelect(epochDay) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(DsSpacing.tiny),
            ) {
                Box(
                    modifier = Modifier
                        .height(96.dp)
                        .width(18.dp),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    if (aggregate.totalTokens == 0L) {
                        Box(
                            Modifier
                                .height(4.dp)
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(5.dp))
                                .background(colors.bgModulePlatform),
                        )
                    } else {
                        Column(
                            modifier = Modifier
                                .height(totalHeight)
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(5.dp)),
                        ) {
                            if (aggregate.inputTokens > 0L) {
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .weight(inputFraction.coerceAtLeast(0.05f))
                                        .background(if (selected) colors.accent else colors.accentHover),
                                )
                            }
                            if (aggregate.outputTokens > 0L) {
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .weight((1f - inputFraction).coerceAtLeast(0.05f))
                                        .background(colors.characterAccent),
                                )
                            }
                        }
                    }
                }
                Text(
                    LocalDate.ofEpochDay(epochDay).dayOfMonth.toString(),
                    style = DsType.caption11,
                    color = if (selected) colors.labelPrimary else colors.labelTertiary,
                )
            }
        }
    }
}

@Composable
private fun UsageGroupRow(group: TokenUsageGroupSummary, onClick: () -> Unit) {
    val colors = DsTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = DsSpacing.medium, vertical = DsSpacing.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(DsSpacing.tiny),
        ) {
            Text(
                group.title,
                style = DsType.std14Strong,
                color = colors.labelPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (group.mode == LocalUsageMode.WORK) {
                    stringResource(
                        R.string.usage_main_sub_summary,
                        formatTokens(group.mainTokens),
                        formatTokens(group.subagentTokens),
                    )
                } else {
                    stringResource(R.string.usage_turn_count, group.turnCount)
                },
                style = DsType.caption11,
                color = colors.labelTertiary,
                maxLines = 1,
            )
        }
        Text(
            formatTokens(group.aggregate.totalTokens),
            style = DsType.small13Strong,
            color = colors.labelPrimary,
        )
        Spacer(Modifier.width(DsSpacing.small))
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = colors.labelTertiary,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun UsageRequestRow(record: TokenUsageRecord, onClick: () -> Unit) {
    val colors = DsTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = DsSpacing.medium, vertical = DsSpacing.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(DsSpacing.tiny),
        ) {
            Text(
                actionLabel(record.context.action),
                style = DsType.small13Strong,
                color = colors.labelPrimary,
                maxLines = 1,
            )
            Text(
                "${formatTime(record.timestamp)} · ${record.model}" +
                    record.context.agentId?.let { " · $it" }.orEmpty(),
                style = DsType.caption11,
                color = colors.labelTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(formatTokens(record.totalTokens), style = DsType.small13Strong, color = colors.labelPrimary)
            Text(
                "↓${formatTokens(record.inputTokens)}  ↑${formatTokens(record.outputTokens)}",
                style = DsType.caption11,
                color = colors.labelSecondary,
            )
        }
    }
}

@Composable
private fun PromptBreakdownCard(breakdown: TokenPromptBreakdown) {
    val colors = DsTheme.colors
    SectionHeading(stringResource(R.string.usage_prompt_composition))
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = DsShapes.block,
        color = colors.characterAccentTertiary,
    ) {
        Column(
            modifier = Modifier.padding(DsSpacing.medium),
            verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
        ) {
            Text(
                stringResource(R.string.usage_estimated_badge),
                style = DsType.caption11,
                color = colors.characterAccent,
            )
            PromptLine(stringResource(R.string.usage_prompt_system), breakdown.systemBaseTokens)
            PromptLine(stringResource(R.string.usage_prompt_persona), breakdown.personaStateTokens)
            PromptLine(stringResource(R.string.usage_prompt_memory), breakdown.memoryRuleTokens)
            PromptLine(stringResource(R.string.usage_prompt_history), breakdown.historyTokens)
            PromptLine(stringResource(R.string.usage_prompt_current), breakdown.currentUserTokens)
            PromptLine(stringResource(R.string.usage_prompt_tools), breakdown.toolDefinitionTokens)
            PromptLine(stringResource(R.string.usage_prompt_other), breakdown.otherSystemTokens)
            UsageDivider()
            UsageValueLine(
                stringResource(R.string.usage_estimated_input, formatNumber(breakdown.estimatedInputTokens)),
                "",
                padded = false,
            )
            Text(
                stringResource(R.string.usage_prompt_note),
                style = DsType.caption11,
                color = colors.labelTertiary,
            )
        }
    }
}

@Composable
private fun PromptLine(label: String, value: Int) {
    if (value <= 0) return
    UsageValueLine(label, "≈${formatNumber(value.toLong())}", padded = false)
}

@Composable
private fun UsageAggregateHero(
    title: String,
    aggregate: TokenUsageAggregate,
    badge: String? = null,
) {
    val colors = DsTheme.colors
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = DsShapes.dialog,
        color = colors.accentTertiary,
    ) {
        Column(
            modifier = Modifier.padding(DsSpacing.large),
            verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = DsType.small13Strong,
                    color = colors.labelSecondary,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                badge?.let {
                    Text(it, style = DsType.caption11, color = colors.accent)
                }
            }
            Text(formatTokens(aggregate.totalTokens), style = DsType.display24, color = colors.labelPrimary)
            Text(
                "${stringResource(R.string.usage_calculation_input)} ${formatTokens(aggregate.inputTokens)} · " +
                    "${stringResource(R.string.usage_calculation_output)} ${formatTokens(aggregate.outputTokens)} · " +
                    "¥${String.format(Locale.US, "%.4f", aggregate.estimatedCostCny)}",
                style = DsType.small13,
                color = colors.labelSecondary,
            )
        }
    }
}

@Composable
private fun CompactMetric(label: String, value: String, modifier: Modifier = Modifier) {
    val colors = DsTheme.colors
    Surface(modifier = modifier, shape = DsShapes.row, color = colors.bgLayer1) {
        Column(
            modifier = Modifier.padding(DsSpacing.medium),
            verticalArrangement = Arrangement.spacedBy(DsSpacing.tiny),
        ) {
            Text(label, style = DsType.caption11, color = colors.labelTertiary, maxLines = 1)
            Text(
                value,
                style = DsType.large20,
                color = colors.labelPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SectionHeading(text: String) {
    Text(text, style = DsType.std14Strong, color = DsTheme.colors.labelPrimary)
}

@Composable
private fun EmptyUsageDetail(message: String? = null) {
    val colors = DsTheme.colors
    val resolvedMessage = message ?: stringResource(R.string.usage_empty_detail)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = DsShapes.row,
        color = colors.bgLayer1,
    ) {
        Text(
            resolvedMessage,
            modifier = Modifier.padding(DsSpacing.medium),
            style = DsType.small13,
            color = colors.labelSecondary,
        )
    }
}

@Composable
private fun UsageValueLine(label: String, value: String, padded: Boolean = true) {
    val colors = DsTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (padded) Modifier.padding(horizontal = DsSpacing.medium, vertical = DsSpacing.small) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = DsType.small13, color = colors.labelSecondary, modifier = Modifier.weight(1f))
        if (value.isNotEmpty()) {
            Text(value, style = DsType.small13Strong, color = colors.labelPrimary)
        }
    }
}

@Composable
private fun UsageDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(DsTheme.colors.borderL1),
    )
}

@Composable
private fun actionLabel(action: TokenUsageAction): String = stringResource(
    when (action) {
        TokenUsageAction.CHAT_REPLY -> R.string.usage_action_chat_reply
        TokenUsageAction.CHAT_REPAIR -> R.string.usage_action_chat_repair
        TokenUsageAction.CHAT_STATE_REFRESH -> R.string.usage_action_chat_state_refresh
        TokenUsageAction.REPLY_SUGGESTIONS -> R.string.usage_action_reply_suggestions
        TokenUsageAction.GROUP_REPLY -> R.string.usage_action_group_reply
        TokenUsageAction.GROUP_STATE_REFRESH -> R.string.usage_action_group_state_refresh
        TokenUsageAction.GROUP_ANNOUNCEMENT -> R.string.usage_action_group_announcement
        TokenUsageAction.PERSONA_AUTOFILL -> R.string.usage_action_persona_autofill
        TokenUsageAction.PERSONA_INSPECTION -> R.string.usage_action_persona_inspection
        TokenUsageAction.WORK_MAIN -> R.string.usage_action_work_main
        TokenUsageAction.WORK_SUBAGENT -> R.string.usage_action_work_subagent
        TokenUsageAction.AUTOMATION -> R.string.usage_action_automation
        TokenUsageAction.AUTOMATION_CHAT -> R.string.usage_action_automation_chat
        TokenUsageAction.WEB_SEARCH -> R.string.usage_action_web_search
        TokenUsageAction.VISION -> R.string.usage_action_vision
        TokenUsageAction.OTHER -> R.string.usage_action_other
    },
)

private fun dailyWindow(
    source: List<TokenUsageDailyBucket>,
    mode: LocalUsageMode,
    days: Int,
): List<Pair<Long, TokenUsageAggregate>> {
    val byDay = source.associateBy(TokenUsageDailyBucket::epochDay)
    val today = LocalDate.now().toEpochDay()
    return ((days - 1) downTo 0).map { offset ->
        val epochDay = today - offset
        epochDay to (byDay[epochDay]?.forMode(mode) ?: TokenUsageAggregate())
    }
}

private fun todayAggregate(days: List<Pair<Long, TokenUsageAggregate>>): TokenUsageAggregate =
    days.lastOrNull()?.second ?: TokenUsageAggregate()

private fun formatEpochDay(epochDay: Long): String =
    LocalDate.ofEpochDay(epochDay).format(DateTimeFormatter.ofPattern("MM/dd"))

private fun formatTime(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("MM/dd HH:mm"))

private fun formatNumber(value: Long): String =
    NumberFormat.getIntegerInstance().format(value)

private fun formatTokens(value: Long): String = when {
    value >= 1_000_000_000L -> String.format(Locale.US, "%.2fB", value / 1_000_000_000.0)
    value >= 1_000_000L -> String.format(Locale.US, "%.2fM", value / 1_000_000.0)
    value >= 10_000L -> String.format(Locale.US, "%.1fK", value / 1_000.0)
    else -> formatNumber(value)
}

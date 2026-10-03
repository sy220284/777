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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import com.labteto.dshmobile.ui.components.DsSegment
import com.labteto.dshmobile.ui.components.DsSegmented
import com.labteto.dshmobile.ui.components.DsSegmentedTabs
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.WallpaperSurfaceLevel
import com.labteto.dshmobile.ui.theme.wallpaperSurface
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
    onOpenGroup: (TokenUsageGroupKind, String, String) -> Unit = { _, _, _ -> },
) {
    var modeIndex by rememberSaveable { mutableIntStateOf(0) }
    var rangeIndex by rememberSaveable { mutableIntStateOf(0) }
    var showAllGroups by rememberSaveable(modeIndex) {
        androidx.compose.runtime.mutableStateOf(false)
    }
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
        color = colors.wallpaperSurface(WallpaperSurfaceLevel.CARD),
    ) {
        Column(
            modifier = Modifier.padding(DsSpacing.medium),
            verticalArrangement = Arrangement.spacedBy(DsSpacing.medium),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.usage_daily_title),
                    style = DsType.std14Strong.withReadingWeight(),
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
                        style = DsType.small13Strong.withReadingWeight(),
                        color = colors.labelPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "${stringResource(R.string.usage_calculation_input)} ${formatTokens(aggregate.inputTokens)} · " +
                            "${stringResource(R.string.usage_calculation_output)} ${formatTokens(aggregate.outputTokens)}",
                        style = DsType.caption11.withReadingWeight(),
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
            color = colors.wallpaperSurface(WallpaperSurfaceLevel.CARD),
        ) {
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
            DsButton(
                text = if (showAllGroups) {
                    stringResource(R.string.usage_show_recent)
                } else {
                    stringResource(R.string.usage_show_all, groups.size)
                },
                onClick = { showAllGroups = !showAllGroups },
                variant = DsButtonVariant.Ghost,
                modifier = Modifier.fillMaxWidth(),
            )
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
                        style = DsType.small13Strong.withReadingWeight(),
                        color = colors.labelPrimary,
                    )
                }
                Text(
                    stringResource(R.string.usage_legacy_notice),
                    style = DsType.caption11.withReadingWeight(),
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
                    style = DsType.small13Strong.withReadingWeight(),
                    color = colors.warnLabel,
                )
                if (usage.unreportedRequestCount > 0L) {
                    Text(
                        stringResource(R.string.local_usage_unreported, usage.unreportedRequestCount),
                        style = DsType.caption11.withReadingWeight(),
                        color = colors.labelSecondary,
                    )
                }
                if (usage.unpricedTokens > 0L) {
                    Text(
                        stringResource(R.string.local_usage_unpriced, formatNumber(usage.unpricedTokens)),
                        style = DsType.caption11.withReadingWeight(),
                        color = colors.labelSecondary,
                    )
                }
            }
        }
    }

    Text(
        stringResource(R.string.usage_calculation_disclaimer),
        style = DsType.caption11.withReadingWeight(),
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
        color = colors.wallpaperSurface(WallpaperSurfaceLevel.CARD),
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
            color = colors.wallpaperSurface(WallpaperSurfaceLevel.CARD),
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
            color = colors.wallpaperSurface(WallpaperSurfaceLevel.CARD),
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
        color = colors.wallpaperSurface(WallpaperSurfaceLevel.CARD),
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
        color = colors.wallpaperSurface(WallpaperSurfaceLevel.CARD),
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
                style = DsType.caption11.withReadingWeight(),
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
        style = DsType.caption11.withReadingWeight(),
        color = colors.labelTertiary,
    )
}

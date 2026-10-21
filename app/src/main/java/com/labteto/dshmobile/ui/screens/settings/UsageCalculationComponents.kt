package com.labteto.dshmobile.ui.screens.settings

import com.shenyan.designsystem.foundation.SyMetric



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
import com.labteto.dshmobile.ui.components.FeatherIcons
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
import com.labteto.dshmobile.ui.shenyan.SySegment
import com.labteto.dshmobile.ui.shenyan.SySegmented
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

@Composable
internal fun DeviceUsageHero(usage: DeepSeekUsageSnapshot, showEstimatedCost: Boolean = true) {
    val colors = com.shenyan.designsystem.foundation.LocalSyColors.current
    val cacheValue = if (usage.cacheMeasuredTokens > 0L) {
        String.format(Locale.US, "%.1f%%", usage.cacheHitRate * 100.0)
    } else {
        stringResource(R.string.usage_calculation_no_data)
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(SyMetric.gapSection),
            verticalArrangement = Arrangement.spacedBy(SyMetric.gapMd),
        ) {
            Text(
                stringResource(R.string.usage_device_total),
                style = DsType.small13Strong.withReadingWeight(),
                color = colors.textSecondary,
            )
            Text(
                formatTokens(usage.totalTokens),
                style = DsType.display24.withReadingWeight(),
                color = colors.text,
            )
            Text(
                "${stringResource(R.string.usage_calculation_input)} ${formatTokens(usage.inputTokens)} · " +
                    "${stringResource(R.string.usage_calculation_output)} ${formatTokens(usage.outputTokens)}" +
                    if (showEstimatedCost) " · ${stringResource(R.string.usage_deepseek_cost_label)} ¥${String.format(Locale.US, "%.4f", usage.estimatedCostCny)}" else "",
                style = DsType.small13.withReadingWeight(),
                color = colors.textSecondary,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.local_usage_cache_hit_rate),
                    style = DsType.caption11.withReadingWeight(),
                    color = colors.textTertiary,
                    modifier = Modifier.weight(1f),
                )
                Text(cacheValue, style = DsType.small13Strong.withReadingWeight(), color = colors.text)
            }
        }
    }
}

@Composable
internal fun UsageEfficiency(mode: LocalUsageMode, analytics: TokenUsageModeAnalytics) {
    SectionHeading(
        if (mode == LocalUsageMode.CHAT) {
            stringResource(R.string.usage_mode_chat)
        } else {
            stringResource(R.string.usage_mode_work)
        },
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(SyMetric.gapMd),
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
        horizontalArrangement = Arrangement.spacedBy(SyMetric.gapMd),
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
                stringResource(R.string.usage_no_measured_cache)
            },
            Modifier.weight(1f),
        )
    }
}

@Composable
internal fun UsageRangeSelector(selectedIndex: Int, onSelect: (Int) -> Unit) {
    val labels = listOf(
        stringResource(R.string.usage_range_7),
        stringResource(R.string.usage_range_30),
        stringResource(R.string.usage_range_90),
    )
    SySegmented(
        segments = labels.mapIndexed { index, label -> SySegment(index.toString(), label) },
        selectedKey = selectedIndex.toString(),
        onSelect = { key -> key.toIntOrNull()?.let(onSelect) },
    )
}
@Composable
internal fun UsageDayChart(
    days: List<Pair<Long, TokenUsageAggregate>>,
    selectedEpochDay: Long,
    onSelect: (Long) -> Unit,
) {
    val colors = com.shenyan.designsystem.foundation.LocalSyColors.current
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
        horizontalArrangement = Arrangement.spacedBy(SyMetric.gapMd),
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
                    .width(SyMetric.touch)
                    .clickable { onSelect(epochDay) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(SyMetric.gapXs),
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
                                .background(colors.materialL3),
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
                                        .background(if (selected) colors.primary else colors.primaryPressed),
                                )
                            }
                            if (aggregate.outputTokens > 0L) {
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .weight((1f - inputFraction).coerceAtLeast(0.05f))
                                        .background(colors.textTertiary),
                                )
                            }
                        }
                    }
                }
                Text(
                    LocalDate.ofEpochDay(epochDay).dayOfMonth.toString(),
                    style = DsType.caption11.withReadingWeight(),
                    color = if (selected) colors.text else colors.textTertiary,
                )
            }
        }
    }
}

@Composable
internal fun UsageGroupRow(group: TokenUsageGroupSummary, onClick: () -> Unit) {
    val colors = com.shenyan.designsystem.foundation.LocalSyColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = SyMetric.touch)
            .clickable(onClick = onClick)
            .padding(horizontal = SyMetric.gapLg, vertical = SyMetric.gapLg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(SyMetric.gapXs),
        ) {
            Text(
                group.title,
                style = DsType.std14Strong.withReadingWeight(),
                color = colors.text,
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
                style = DsType.caption11.withReadingWeight(),
                color = colors.textTertiary,
                maxLines = 1,
            )
        }
        Text(
            formatTokens(group.aggregate.totalTokens),
            style = DsType.small13Strong.withReadingWeight(),
            color = colors.text,
        )
        Spacer(Modifier.width(SyMetric.gapMd))
        Icon(
            imageVector = FeatherIcons.ChevronRight,
            contentDescription = null,
            tint = colors.textTertiary,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
internal fun UsageRequestRow(record: TokenUsageRecord, onClick: () -> Unit) {
    val colors = com.shenyan.designsystem.foundation.LocalSyColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = SyMetric.touch)
            .clickable(onClick = onClick)
            .padding(horizontal = SyMetric.gapLg, vertical = SyMetric.gapMd),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(SyMetric.gapXs),
        ) {
            Text(
                actionLabel(record.context.action),
                style = DsType.small13Strong.withReadingWeight(),
                color = colors.text,
                maxLines = 1,
            )
            Text(
                "${formatTime(record.timestamp)} · ${record.model}" +
                    record.context.agentId?.let { " · $it" }.orEmpty(),
                style = DsType.caption11.withReadingWeight(),
                color = colors.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(formatTokens(record.totalTokens), style = DsType.small13Strong.withReadingWeight(), color = colors.text)
            Text(
                "↓${formatTokens(record.inputTokens)}  ↑${formatTokens(record.outputTokens)}",
                style = DsType.caption11.withReadingWeight(),
                color = colors.textSecondary,
            )
        }
    }
}

@Composable
internal fun PromptBreakdownCard(breakdown: TokenPromptBreakdown) {
    val colors = com.shenyan.designsystem.foundation.LocalSyColors.current
    SectionHeading(stringResource(R.string.usage_prompt_composition))
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = DsShapes.block,
        color = colors.lilac.copy(alpha = .18f),
    ) {
        Column(
            modifier = Modifier.padding(SyMetric.gapLg),
            verticalArrangement = Arrangement.spacedBy(SyMetric.gapMd),
        ) {
            Text(
                stringResource(R.string.usage_estimated_badge),
                style = DsType.caption11.withReadingWeight(),
                color = colors.lilac,
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
                style = DsType.caption11.withReadingWeight(),
                color = colors.textTertiary,
            )
        }
    }
}

@Composable
internal fun PromptLine(label: String, value: Int) {
    if (value <= 0) return
    UsageValueLine(label, "≈${formatNumber(value.toLong())}", padded = false)
}

@Composable
internal fun UsageAggregateHero(
    title: String,
    aggregate: TokenUsageAggregate,
    badge: String? = null,
) {
    val colors = com.shenyan.designsystem.foundation.LocalSyColors.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(SyMetric.gapSection),
            verticalArrangement = Arrangement.spacedBy(SyMetric.gapMd),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = DsType.small13Strong.withReadingWeight(),
                    color = colors.textSecondary,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                badge?.let {
                    Text(it, style = DsType.caption11.withReadingWeight(), color = colors.primary)
                }
            }
            Text(formatTokens(aggregate.totalTokens), style = DsType.display24.withReadingWeight(), color = colors.text)
            Text(
                "${stringResource(R.string.usage_calculation_input)} ${formatTokens(aggregate.inputTokens)} · " +
                    "${stringResource(R.string.usage_calculation_output)} ${formatTokens(aggregate.outputTokens)} · " +
                    "¥${String.format(Locale.US, "%.4f", aggregate.estimatedCostCny)}",
                style = DsType.small13.withReadingWeight(),
                color = colors.textSecondary,
            )
        }
    }
}

@Composable
internal fun CompactMetric(label: String, value: String, modifier: Modifier = Modifier, testTag: String = "usageCompactMetric") {
    val colors = com.shenyan.designsystem.foundation.LocalSyColors.current
    // Metric columns share their parent section instead of nesting another glass tile.
    Column(
        modifier = modifier.testTag(testTag)
            .padding(horizontal = SyMetric.gapMd, vertical = SyMetric.gapSm),
        verticalArrangement = Arrangement.spacedBy(SyMetric.gapXs),
    ) {
        Text(label, style = DsType.caption11.withReadingWeight(), color = colors.textTertiary)
        Text(
            value,
            style = DsType.large20.withReadingWeight().copy(fontFeatureSettings = "tnum"),
            color = colors.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun SectionHeading(text: String) {
    com.shenyan.designsystem.components.SySectionHeader(text)
}

@Composable
internal fun EmptyUsageDetail(message: String? = null) {
    val colors = com.shenyan.designsystem.foundation.LocalSyColors.current
    val resolvedMessage = message ?: stringResource(R.string.usage_empty_detail)
    Text(
        resolvedMessage,
        modifier = Modifier.fillMaxWidth().padding(SyMetric.gapLg),
        style = DsType.small13.withReadingWeight(),
        color = colors.textSecondary,
    )
}

@Composable
internal fun UsageValueLine(label: String, value: String, padded: Boolean = true) {
    val colors = com.shenyan.designsystem.foundation.LocalSyColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (padded) Modifier.padding(horizontal = SyMetric.gapLg, vertical = SyMetric.gapMd) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = DsType.small13.withReadingWeight(), color = colors.textSecondary, modifier = Modifier.weight(1f))
        if (value.isNotEmpty()) {
            Text(value, style = DsType.small13Strong.withReadingWeight(), color = colors.text)
        }
    }
}

@Composable
internal fun UsageDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(com.shenyan.designsystem.foundation.LocalSyColors.current.border),
    )
}

@Composable
internal fun actionLabel(action: TokenUsageAction): String = stringResource(
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
        TokenUsageAction.WORK_SUMMARY -> R.string.usage_action_work_summary
        TokenUsageAction.WORK_MAIN -> R.string.usage_action_work_main
        TokenUsageAction.WORK_SUBAGENT -> R.string.usage_action_work_subagent
        TokenUsageAction.AUTOMATION -> R.string.usage_action_automation
        TokenUsageAction.AUTOMATION_CHAT -> R.string.usage_action_automation_chat
        TokenUsageAction.WEB_SEARCH -> R.string.usage_action_web_search
        TokenUsageAction.VISION -> R.string.usage_action_vision
        TokenUsageAction.UI_STRUCTURE_GENERATION -> R.string.usage_action_other
        TokenUsageAction.OTHER -> R.string.usage_action_other
    },
)

internal fun dailyWindow(
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

internal fun todayAggregate(days: List<Pair<Long, TokenUsageAggregate>>): TokenUsageAggregate =
    days.lastOrNull()?.second ?: TokenUsageAggregate()

internal fun formatEpochDay(epochDay: Long): String =
    LocalDate.ofEpochDay(epochDay).format(DateTimeFormatter.ofPattern("MM/dd"))

internal fun formatTime(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("MM/dd HH:mm"))

internal fun formatNumber(value: Long): String =
    NumberFormat.getIntegerInstance().format(value)

internal fun formatTokens(value: Long): String = when {
    value >= 1_000_000_000L -> String.format(Locale.US, "%.2fB", value / 1_000_000_000.0)
    value >= 1_000_000L -> String.format(Locale.US, "%.2fM", value / 1_000_000.0)
    value >= 10_000L -> String.format(Locale.US, "%.1fK", value / 1_000.0)
    else -> formatNumber(value)
}

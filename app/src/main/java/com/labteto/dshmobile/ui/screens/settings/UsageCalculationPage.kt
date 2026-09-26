package com.labteto.dshmobile.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.DeepSeekUsageSnapshot
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date
import java.util.Locale

/** Device-wide DeepSeek API usage; the tracker already persists and streams these totals. */
@Composable
internal fun UsageCalculationPage(usage: DeepSeekUsageSnapshot, onOpenPricing: () -> Unit) {
    val colors = DsTheme.colors
    val countFormat = NumberFormat.getIntegerInstance()
    val hitRate = if (usage.cacheMeasuredTokens > 0L) usage.cacheHitRate else null
    val updated = if (usage.updatedAt > 0L) {
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(usage.updatedAt))
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
                stringResource(R.string.usage_calculation_estimate),
                style = DsType.small13Strong,
                color = colors.labelSecondary,
            )
            Text(
                String.format(Locale.US, "¥%.4f", usage.estimatedCostCny),
                style = DsType.display24,
                color = colors.labelPrimary,
            )
            Text(
                stringResource(R.string.usage_calculation_scope),
                style = DsType.caption11,
                color = colors.labelSecondary,
            )
            Text(
                stringResource(R.string.usage_calculation_updated, updated),
                style = DsType.caption11,
                color = colors.labelTertiary,
            )
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
        UsageMetric(
            label = stringResource(R.string.usage_calculation_total_tokens),
            value = countFormat.format(usage.totalTokens),
            modifier = Modifier.weight(1f),
        )
        UsageMetric(
            label = stringResource(R.string.usage_calculation_requests),
            value = countFormat.format(usage.totalRequestCount),
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
            verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
        ) {
            Text(stringResource(R.string.usage_calculation_token_breakdown), style = DsType.std14Strong, color = colors.labelPrimary)
            UsageValueRow(stringResource(R.string.usage_calculation_input), countFormat.format(usage.inputTokens))
            UsageValueRow(stringResource(R.string.usage_calculation_output), countFormat.format(usage.outputTokens))
            Text(
                stringResource(R.string.usage_calculation_reasoning_note, countFormat.format(usage.reasoningTokens)),
                style = DsType.caption11,
                color = colors.labelTertiary,
            )
        }
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = DsShapes.block,
        color = colors.bgLayer1,
    ) {
        Column(
            modifier = Modifier.padding(DsSpacing.medium),
            verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.local_usage_cache_hit_rate),
                    style = DsType.std14Strong,
                    color = colors.labelPrimary,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    hitRate?.let { String.format(Locale.US, "%.1f%%", it * 100.0) }
                        ?: stringResource(R.string.usage_calculation_no_data),
                    style = DsType.large20,
                    color = colors.accent,
                )
            }
            Box(
                Modifier.fillMaxWidth().height(9.dp).clip(DsShapes.pillFull)
                    .background(colors.bgModulePlatform),
            ) {
                if (hitRate != null && hitRate > 0.0) {
                    Box(
                        Modifier.fillMaxWidth(hitRate.toFloat().coerceIn(0f, 1f))
                            .fillMaxHeight().background(colors.accent),
                    )
                }
            }
            UsageValueRow(stringResource(R.string.usage_calculation_hit), countFormat.format(usage.cacheHitTokens))
            UsageValueRow(stringResource(R.string.usage_calculation_miss), countFormat.format(usage.cacheMissTokens))
            Text(
                stringResource(R.string.usage_calculation_cache_note),
                style = DsType.caption11,
                color = colors.labelTertiary,
            )
        }
    }

    if (usage.unreportedRequestCount > 0L || usage.unpricedTokens > 0L) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = DsShapes.block,
            color = colors.warnTertiary,
        ) {
            Column(
                modifier = Modifier.padding(DsSpacing.medium),
                verticalArrangement = Arrangement.spacedBy(DsSpacing.xsmall),
            ) {
                Text(stringResource(R.string.usage_calculation_partial), style = DsType.small13Strong, color = colors.warnLabel)
                if (usage.unreportedRequestCount > 0L) {
                    Text(
                        stringResource(R.string.local_usage_unreported, usage.unreportedRequestCount),
                        style = DsType.small13,
                        color = colors.labelSecondary,
                    )
                }
                if (usage.unpricedTokens > 0L) {
                    Text(
                        stringResource(R.string.local_usage_unpriced, countFormat.format(usage.unpricedTokens)),
                        style = DsType.small13,
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
private fun UsageMetric(label: String, value: String, modifier: Modifier = Modifier) {
    val colors = DsTheme.colors
    Surface(modifier = modifier, shape = DsShapes.block, color = colors.bgLayer1) {
        Column(
            modifier = Modifier.padding(DsSpacing.medium),
            verticalArrangement = Arrangement.spacedBy(DsSpacing.xsmall),
        ) {
            Text(label, style = DsType.caption11, color = colors.labelTertiary)
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
private fun UsageValueRow(label: String, value: String) {
    val colors = DsTheme.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = DsType.small13, color = colors.labelSecondary, modifier = Modifier.weight(1f))
        Text(value, style = DsType.small13Strong, color = colors.labelPrimary)
    }
}

package com.labteto.dshmobile.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.web.LocalSearchUsageSnapshot
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.withReadingWeight
import com.shenyan.designsystem.foundation.LocalSyColors
import com.shenyan.designsystem.foundation.SyMetric
import java.time.LocalDate
import java.util.Locale

/** Search requests are not model tokens: render them on their own statistics surface. */
@Composable
internal fun UsageSearchSection(usage: LocalSearchUsageSnapshot, rangeDays: Int) {
    val colors = LocalSyColors.current
    val today = LocalDate.now().toEpochDay()
    val todayUsage = usage.days.firstOrNull { it.epochDay == today }
    SectionHeading(stringResource(R.string.usage_mode_detail))
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(SyMetric.gapMd),
    ) {
        CompactMetric(
            stringResource(R.string.usage_search_request_count),
            formatNumber(usage.requests),
            Modifier.weight(1f),
            testTag = "usage-search-total",
        )
        CompactMetric(
            stringResource(R.string.usage_search_today_count),
            formatNumber(todayUsage?.requests ?: 0L),
            Modifier.weight(1f),
            testTag = "usage-search-today",
        )
    }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(SyMetric.gapMd),
    ) {
        CompactMetric(
            stringResource(R.string.usage_search_fee),
            "¥" + String.format(Locale.US, "%.4f", usage.estimatedCostCny),
            Modifier.weight(1f),
            testTag = "usage-search-fee",
        )
        CompactMetric(
            stringResource(R.string.usage_search_deepseek_search),
            formatNumber(usage.deepSeekSearch),
            Modifier.weight(1f),
        )
    }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = SyMetric.gapSm),
        verticalArrangement = Arrangement.spacedBy(SyMetric.gapMd),
    ) {
        UsageValueLine(
            stringResource(R.string.usage_search_deepseek_fetch),
            formatNumber(usage.deepSeekFetch),
            padded = false,
        )
        UsageValueLine(
            stringResource(R.string.usage_search_bing),
            formatNumber(usage.bingSearch),
            padded = false,
        )
        Text(
            stringResource(R.string.usage_search_independent),
            style = DsType.caption11.withReadingWeight(),
            color = colors.textSecondary,
        )
    }
    SectionHeading(stringResource(R.string.usage_search_daily))
    val window = rangeDays.coerceIn(7, 90)
    val byDay = usage.days.associateBy { it.epochDay }
    val points = (window - 1 downTo 0).map { offset ->
        val day = today - offset
        day to (byDay[day]?.requests ?: 0L)
    }
    val maximum = (points.maxOfOrNull { it.second } ?: 0L).coerceAtLeast(1L)
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            .testTag("usage-search-chart"),
        horizontalArrangement = Arrangement.spacedBy(SyMetric.gapSm),
        verticalAlignment = Alignment.Bottom,
    ) {
        points.forEach { (day, count) ->
            Column(
                modifier = Modifier.width(42.dp),
                verticalArrangement = Arrangement.spacedBy(SyMetric.gapXs),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    if (count > 0) formatNumber(count) else "",
                    style = DsType.caption11.withReadingWeight(),
                    color = colors.textSecondary,
                )
                Box(
                    Modifier.height(100.dp).fillMaxWidth(),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    Box(
                        Modifier.fillMaxWidth()
                            .height(if (count == 0L) 3.dp else (92f * count / maximum).dp.coerceAtLeast(5.dp))
                            .background(colors.selected, RoundedCornerShape(topStart = 5.dp, topEnd = 5.dp)),
                    )
                }
                Text(
                    formatEpochDay(day),
                    style = DsType.caption11.withReadingWeight(),
                    color = colors.textTertiary,
                )
            }
        }
    }
    if (usage.requests == 0L) {
        Text(
            stringResource(R.string.usage_search_no_data),
            style = DsType.small13.withReadingWeight(),
            color = colors.textSecondary,
        )
    }
}

package com.labteto.dshmobile.ui.screens.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import com.labteto.dshmobile.R
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.withReadingWeight
import com.shenyan.designsystem.foundation.LocalSyColors
import com.shenyan.designsystem.foundation.SyMetric

internal data class PricePeriodValues(
    val label: String,
    val cacheHit: String,
    val cacheMiss: String,
    val output: String,
    val active: Boolean,
) {
    val values: List<String> get() = listOf(cacheHit, cacheMiss, output)
}

/** Keep comparison columns aligned and decimal values intact at the user's actual text size. */
@Composable
internal fun PriceComparisonTable(offPeak: PricePeriodValues, peak: PricePeriodValues) {
    val colors = LocalSyColors.current
    val rows = listOf(offPeak, peak)
    val headers = listOf(stringResource(R.string.pricing_col_cache_hit),
        stringResource(R.string.pricing_col_cache_miss), stringResource(R.string.pricing_col_output))
    val headerStyle = DsType.caption11.withReadingWeight()
    val valueStyle = DsType.small13.withReadingWeight().copy(fontFeatureSettings = "tnum")
    val labelStyle = DsType.small13.withReadingWeight()
    val activeStyle = DsType.small13Strong.withReadingWeight()
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val periodWidth = rows.maxOf { row -> with(density) {
        measurer.measure(row.label, style = if (row.active) activeStyle else labelStyle,
            softWrap = false).size.width.toDp()
    } } + SyMetric.gapMd
    val valueWidths = headers.indices.map { index ->
        val header = measurer.measure(headers[index], style = headerStyle, softWrap = false).size.width
        val value = rows.maxOf { measurer.measure(it.values[index], style = valueStyle, softWrap = false).size.width }
        with(density) { maxOf(header, value).toDp() } + SyMetric.gapMd
    }
    val requiredWidth = valueWidths.fold(periodWidth) { total, width -> total + width }
    Text(
        text = "价格单位：人民币元 / 100 万 Token",
        style = DsType.caption11.withReadingWeight(),
        color = colors.textSecondary,
        modifier = Modifier.fillMaxWidth(),
    )
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val tableWidth = maxOf(maxWidth, requiredWidth)
        val extra = (tableWidth - requiredWidth) / headers.size
        Column(Modifier.horizontalScroll(rememberScrollState()).width(tableWidth),
            verticalArrangement = Arrangement.spacedBy(SyMetric.gapXs)) {
            Row(Modifier.fillMaxWidth()) {
                Spacer(Modifier.width(periodWidth))
                headers.forEachIndexed { index, header ->
                    Text(header, modifier = Modifier.weight((valueWidths[index] + extra).value),
                        style = headerStyle, color = colors.textSecondary,
                        softWrap = false, textAlign = TextAlign.End)
                }
            }
            rows.forEach { row ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(row.label, modifier = Modifier.width(periodWidth),
                        style = if (row.active) activeStyle else labelStyle,
                        color = if (row.active) colors.text else colors.textSecondary, softWrap = false)
                    row.values.forEachIndexed { index, value ->
                        Text(value, modifier = Modifier.weight((valueWidths[index] + extra).value),
                            style = valueStyle, color = if (row.active) colors.text else colors.textSecondary,
                            softWrap = false, textAlign = TextAlign.End)
                    }
                }
            }
        }
    }
}

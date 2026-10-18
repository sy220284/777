package com.labteto.dshmobile.uipack.zhaoxi.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.labteto.dshmobile.uipack.zhaoxi.theme.ZhaoxiTheme

/** 表格列宽遵循实际文字宽度与系统字号：中日韩宽字符约占双倍拉丁字符。 */
internal fun zxTableColumnWidthDp(header: String, values: List<String>, fontScale: Float): Float {
    val widest = (sequenceOf(header) + values.asSequence().take(100))
        .map { cell ->
            cell.take(80).sumOf { ch -> if (ch.code >= 0x2E80) 2 else 1 }
        }
        .maxOrNull() ?: 12
    return widest.coerceIn(12, 36) * 7f * fontScale.coerceAtLeast(1f)
}

/** The sole table renderer for both streamed Markdown and versioned UiDocument nodes. */
@Composable
fun ZxDataTable(
    headers: List<String>,
    rows: List<List<String>>,
    modifier: Modifier = Modifier,
) {
    val colors = ZhaoxiTheme.colors
    val clipboard = LocalClipboardManager.current
    val fontScale = LocalDensity.current.fontScale
    var visibleRows by remember(headers, rows) { mutableStateOf(24) }
    var copied by remember(headers, rows) { mutableStateOf(false) }
    val widths = remember(headers, rows, fontScale) {
        headers.indices.map { column ->
            zxTableColumnWidthDp(
                headers[column],
                rows.take(100).map { it.getOrElse(column) { "" } },
                fontScale,
            ).dp
        }
    }
    val limit = minOf(visibleRows, rows.size, 500)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                headers.forEachIndexed { i, header ->
                    Text(header, Modifier.width(widths[i]), color = colors.ink,
                        fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            HorizontalDivider()
            rows.take(limit).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    headers.indices.forEach { i ->
                        Text(row.getOrElse(i) { "" }, Modifier.width(widths[i]),
                            color = colors.ink2, fontSize = 12.sp)
                    }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (limit < minOf(rows.size, 500)) {
                val nextCount = minOf(60, minOf(rows.size, 500) - limit)
                ZxChip("加载更多 · ${nextCount} 行", false) {
                    visibleRows = minOf(500, visibleRows + 60)
                }
            }
            if (visibleRows > 24) {
                ZxChip("收起", false) { visibleRows = 24 }
            }
            ZxChip(if (copied) "已复制" else "复制表格", copied) {
                val csv = (listOf(headers) + rows).joinToString("\n") { record ->
                    record.joinToString(",") { cell -> "\"" + cell.replace("\"", "\"\"") + "\"" }
                }
                clipboard.setText(AnnotatedString(csv))
                copied = true
            }
        }
        if (rows.size > 500 && visibleRows >= 500)
            Text("已显示前 500 行 · 复制可获取全部内容",
                fontSize = 11.sp, color = colors.ink2)
    }
}

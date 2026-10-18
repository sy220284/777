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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.labteto.dshmobile.uipack.zhaoxi.theme.ZhaoxiTheme

/** The sole table renderer for both streamed Markdown and versioned UiDocument nodes. */
@Composable
fun ZxDataTable(
    headers: List<String>,
    rows: List<List<String>>,
    modifier: Modifier = Modifier,
) {
    val colors = ZhaoxiTheme.colors
    val clipboard = LocalClipboardManager.current
    var expanded by remember(headers, rows) { mutableStateOf(false) }
    val widths = remember(headers, rows) {
        headers.indices.map { column ->
            val longest = (listOf(headers[column]) +
                rows.take(100).map { it.getOrElse(column) { "" } })
                .maxOfOrNull { it.length } ?: 8
            (longest.coerceIn(8, 28) * 7).dp
        }
    }
    val limit = if (expanded) 500 else 24
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
                    row.forEachIndexed { i, value ->
                        if (i < widths.size)
                            Text(value, Modifier.width(widths[i]), color = colors.ink2, fontSize = 12.sp)
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (rows.size > 24) {
                ZxChip(if (expanded) "收起" else "查看全部 ${rows.size} 行", false) {
                    expanded = !expanded
                }
            }
            ZxChip("复制表格", false) {
                val csv = (listOf(headers) + rows).joinToString("\n") { record ->
                    record.joinToString(",") { cell -> "\"" + cell.replace("\"", "\"\"") + "\"" }
                }
                clipboard.setText(AnnotatedString(csv))
            }
        }
        if (rows.size > 500 && expanded)
            Text("当前仅显示前 500 行 · 复制可获取全部内容",
                fontSize = 11.sp, color = colors.ink2)
    }
}

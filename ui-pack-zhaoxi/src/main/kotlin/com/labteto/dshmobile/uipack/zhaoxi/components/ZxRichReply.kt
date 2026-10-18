package com.labteto.dshmobile.uipack.zhaoxi.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.labteto.dshmobile.uipack.zhaoxi.theme.ZhaoxiTheme

/** Read-only display blocks; incomplete streaming syntax stays visible as plain text. */
internal sealed interface ZxReplyBlock {
    data class Paragraph(val text: String) : ZxReplyBlock
    data class Heading(val level: Int, val text: String) : ZxReplyBlock
    data class Code(val language: String, val source: String) : ZxReplyBlock
    data class Table(val headers: List<String>, val rows: List<List<String>>) : ZxReplyBlock
    data class Bullet(val text: String, val indent: Int, val marker: String) : ZxReplyBlock
    data class Quote(val text: String) : ZxReplyBlock
    data object Divider : ZxReplyBlock
}

internal fun parseZxRichReply(raw: String): List<ZxReplyBlock> {
    val lines = raw.lines()
    val result = mutableListOf<ZxReplyBlock>()
    val plain = mutableListOf<String>()
    fun flush() { if (plain.isNotEmpty()) { result += ZxReplyBlock.Paragraph(plain.joinToString("\n")); plain.clear() } }
    fun cells(line: String) = line.trim().trim('|').split('|').map(String::trim)
    val sep = Regex("^:?-{3,}:?$")
    fun tableSeparator(line: String) = line.contains('|') && cells(line).all { sep.matches(it) }
    val bullet = Regex("^(\\s*)([-*+] |[0-9]+[.)] )(.*)$")
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val t = line.trim()
        when {
            t.startsWith("```") -> {
                flush()
                val language = t.drop(3).trim()
                val code = mutableListOf<String>()
                i++
                while (i < lines.size && !lines[i].trim().startsWith("```")) { code += lines[i]; i++ }
                result += ZxReplyBlock.Code(language, code.joinToString("\n"))
            }
            t.isBlank() -> flush()
            t == "---" || t == "***" -> { flush(); result += ZxReplyBlock.Divider }
            t.startsWith("#") && t.takeWhile { it == '#' }.length in 1..4 &&
                t.getOrNull(t.takeWhile { it == '#' }.length) == ' ' -> {
                flush()
                val level = t.takeWhile { it == '#' }.length
                result += ZxReplyBlock.Heading(level, t.drop(level).trim())
            }
            i + 1 < lines.size && line.contains('|') && tableSeparator(lines[i + 1]) -> {
                flush()
                val headers = cells(line)
                val rows = mutableListOf<List<String>>()
                i += 2
                while (i < lines.size && lines[i].contains('|') && lines[i].isNotBlank()) {
                    val row = cells(lines[i])
                    if (row.size != headers.size) break
                    rows += row
                    i++
                }
                result += ZxReplyBlock.Table(headers, rows)
                continue
            }
            bullet.matches(line) -> {
                flush()
                val match = requireNotNull(bullet.matchEntire(line))
                result += ZxReplyBlock.Bullet(match.groupValues[3], match.groupValues[1].length,
                    match.groupValues[2].trim())
            }
            t.startsWith("> ") -> { flush(); result += ZxReplyBlock.Quote(t.drop(2)) }
            else -> plain += line
        }
        i++
    }
    flush()
    return result
}

private fun richInline(text: String): AnnotatedString = buildAnnotatedString {
    // Conservative emphasis parsing: incomplete markers are left verbatim while tokens stream.
    val regex = Regex("(`[^`\\n]+`|\\*\\*[^*\\n]+\\*\\*|\\*[^*\\n]+\\*)")
    var position = 0
    regex.findAll(text).forEach { match ->
        append(text.substring(position, match.range.first))
        val word = match.value
        when {
            word.startsWith("`") -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(word.drop(1).dropLast(1)) }
            word.startsWith("**") -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(word.drop(2).dropLast(2)) }
            else -> withStyle(SpanStyle(fontWeight = FontWeight.Medium)) { append(word.drop(1).dropLast(1)) }
        }
        position = match.range.last + 1
    }
    append(text.substring(position))
}

/** Shared progressive/final result typography; the UI pack never parses business commands. */
@Composable
fun ZxRichReply(text: String, modifier: Modifier = Modifier) {
    val blocks = remember(text) { parseZxRichReply(text) }
    val colors = ZhaoxiTheme.colors
    val clipboard = LocalClipboardManager.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(9.dp)) {
        blocks.forEach { block ->
            when (block) {
                is ZxReplyBlock.Paragraph -> Text(richInline(block.text), fontSize = 15.sp,
                    lineHeight = 23.sp, color = colors.ink)
                is ZxReplyBlock.Heading -> Text(richInline(block.text),
                    fontSize = (20 - (block.level - 1) * 2).sp,
                    fontWeight = FontWeight.SemiBold, lineHeight = 26.sp, color = colors.ink)
                is ZxReplyBlock.Bullet -> Row(Modifier.padding(start = (block.indent * 4).coerceAtMost(24).dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (block.marker.firstOrNull()?.isDigit() == true) block.marker else "•",
                        color = colors.ink2, fontSize = 14.sp)
                    Text(richInline(block.text), color = colors.ink, fontSize = 14.sp,
                        lineHeight = 21.sp, modifier = Modifier.weight(1f))
                }
                is ZxReplyBlock.Quote -> Text(richInline("❝ " + block.text),
                    color = colors.ink2, fontSize = 14.sp, lineHeight = 21.sp)
                is ZxReplyBlock.Code -> Column(Modifier.fillMaxWidth()
                    .background(colors.bg2, RoundedCornerShape(12.dp)).padding(12.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(block.language.ifBlank { "代码" }, color = colors.ink2, fontSize = 11.sp)
                        ZxChip("复制", false) { clipboard.setText(AnnotatedString(block.source)) }
                    }
                    Text(block.source, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                        lineHeight = 19.sp, color = colors.ink,
                        modifier = Modifier.horizontalScroll(rememberScrollState()))
                }
                is ZxReplyBlock.Table -> {
                    var expanded by remember(block) { mutableStateOf(false) }
                    val widths = block.headers.indices.map { column ->
                        val longest = (listOf(block.headers[column]) +
                            block.rows.take(100).map { it.getOrElse(column) { "" } })
                            .maxOfOrNull { it.length } ?: 8
                        (longest.coerceIn(8, 28) * 7).dp
                    }
                    Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            block.headers.forEachIndexed { col, value ->
                                Text(value, Modifier.width(widths[col]), color = colors.ink,
                                    fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                        HorizontalDivider()
                        (if (expanded) block.rows.take(500) else block.rows.take(24))
                            .forEach { row ->
                                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                    row.forEachIndexed { col, value ->
                                        Text(value, Modifier.width(widths[col]),
                                            color = colors.ink2, fontSize = 12.sp)
                                    }
                                }
                            }
                        if (block.rows.size > 24) {
                            ZxChip(if (expanded) "收起表格" else "展开 ${block.rows.size} 行", false) {
                                expanded = !expanded
                            }
                        }
                        if (expanded && block.rows.size > 500)
                            Text("仅显示前 500 行 · 完整内容可复制原回复",
                                fontSize = 11.sp, color = colors.ink2)
                    }
                }
                ZxReplyBlock.Divider -> HorizontalDivider()
            }
        }
    }
}

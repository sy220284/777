package com.labteto.dshmobile.uipack.next

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.labteto.dshmobile.uicontract.UiMarkdownBlock
import com.labteto.dshmobile.uicontract.UiMarkdownInline
import com.labteto.dshmobile.uicontract.parseUiMarkdown
import com.labteto.dshmobile.uicontract.parseUiMarkdownInline

/**
 * Next renders the same neutral Markdown syntax used by V6 using its own native controls.
 * Messages are read-only; parsing never interprets Markdown as tools or execution authority.
 */
@Composable
internal fun NextRichMessage(text: String, preserveSoftLineBreaks: Boolean) {
    val blocks = remember(text) { parseUiMarkdown(text) }
    val typography = MaterialTheme.typography
    val body = typography.bodyLarge.copy(lineHeight = 25.sp)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { block ->
            when (block) {
                is UiMarkdownBlock.Heading -> {
                    val style = when (block.level) {
                        1 -> typography.headlineMedium
                        2 -> typography.headlineSmall
                        3 -> typography.titleLarge
                        else -> typography.titleMedium
                    }
                    NextInlineMessage(block.text, style.copy(fontWeight = FontWeight.SemiBold),
                        Modifier.fillMaxWidth().padding(top = 8.dp))
                }
                is UiMarkdownBlock.Paragraph -> NextInlineMessage(
                    block.lines.joinToString(if (preserveSoftLineBreaks) "\n" else " "),
                    body, Modifier.fillMaxWidth(),
                )
                is UiMarkdownBlock.MdList -> {
                    val counts = IntArray(7)
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        block.items.forEachIndexed { index, item ->
                            val level = block.levels.getOrElse(index) { 0 }.coerceIn(0, 6)
                            val numbered = block.numbered.getOrElse(index) { block.ordered }
                            val marker = if (numbered) {
                                counts[level]++
                                for (next in level + 1 until counts.size) counts[next] = 0
                                counts[level].toString() + "."
                            } else "•"
                            Row(Modifier.fillMaxWidth().padding(start = (level * 15).dp),
                                verticalAlignment = Alignment.Top) {
                                Text(marker, style = body, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.width(if (numbered) 30.dp else 18.dp))
                                NextInlineMessage(item, body, Modifier.weight(1f))
                            }
                        }
                    }
                }
                is UiMarkdownBlock.Blockquote -> {
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(10.dp)) {
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
                            val callout = block.lines.firstOrNull()?.takeIf {
                                it in setOf("[!IMPORTANT]", "[!WARNING]", "[!CAUTION]", "[!TIP]", "[!NOTE]")
                            }
                            if (callout != null) Text(callout.removePrefix("[!").removeSuffix("]"),
                                style = typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            NextInlineMessage(block.lines.drop(if (callout == null) 0 else 1).joinToString("\n"),
                                body, Modifier.fillMaxWidth())
                        }
                    }
                }
                is UiMarkdownBlock.Code -> NextCodeBlock(block.lang, block.code)
                is UiMarkdownBlock.Table -> NextMarkdownTable(block, body)
            }
        }
    }
}

@Composable
private fun NextInlineMessage(text: String, style: TextStyle, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val uriHandler = LocalUriHandler.current
    val result = remember(text, colors) {
        buildAnnotatedString {
            parseUiMarkdownInline(text).forEach { segment ->
                when (segment) {
                    is UiMarkdownInline.Plain -> append(segment.text)
                    is UiMarkdownInline.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(segment.text) }
                    is UiMarkdownInline.Italic -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(segment.text) }
                    is UiMarkdownInline.Strikethrough -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(segment.text) }
                    is UiMarkdownInline.Code -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = colors.surfaceVariant)) { append(segment.text) }
                    is UiMarkdownInline.Link -> {
                        pushStringAnnotation("url", segment.url)
                        withStyle(SpanStyle(color = colors.primary, textDecoration = TextDecoration.Underline)) {
                            append(segment.text)
                        }
                        pop()
                    }
                }
            }
        }
    }
    ClickableText(text = result, modifier = modifier, style = style.copy(color = colors.onSurface),
        onClick = { offset ->
            result.getStringAnnotations("url", offset, offset).firstOrNull()?.item
                ?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
                ?.let { url -> runCatching { uriHandler.openUri(url) } }
        })
}

@Composable
private fun NextCodeBlock(language: String?, code: String) {
    val clipboard = LocalClipboardManager.current
    Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant) {
        Column {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(language?.takeIf(String::isNotBlank) ?: "text",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { clipboard.setText(AnnotatedString(code)) }) {
                    Text(stringResource(R.string.next_copy_code))
                }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
                Text(code, softWrap = false, style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace, lineHeight = 21.sp))
            }
        }
    }
}

@Composable
private fun NextMarkdownTable(block: UiMarkdownBlock.Table, style: TextStyle) {
    val widths = remember(block) {
        block.header.indices.map { column ->
            val values = block.rows.map { it.getOrElse(column) { "" } } + block.header[column]
            (values.maxOf { it.length } * 10 + 24).coerceIn(104, 240)
        }
    }
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(6.dp)) {
            (listOf(block.header) + block.rows).forEachIndexed { index, row ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEachIndexed { column, cell ->
                        NextInlineMessage(cell, if (index == 0) style.copy(fontWeight = FontWeight.SemiBold) else style,
                            Modifier.width(widths[column].dp).padding(horizontal = 6.dp, vertical = 7.dp))
                    }
                }
            }
        }
    }
}

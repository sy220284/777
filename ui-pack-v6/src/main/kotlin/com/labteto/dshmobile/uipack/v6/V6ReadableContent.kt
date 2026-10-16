package com.labteto.dshmobile.uipack.v6

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.uicontract.*
import com.shenyan.designsystem.components.SyButton
import com.shenyan.designsystem.components.SyButtonStyle
import com.shenyan.designsystem.foundation.LocalSyColors

/** Uses the shared parser; this pack owns only typography and interaction. */
@Composable
internal fun V6ReadableContent(text: String, preserveLines: Boolean = false,
    onOpenFile: ((String) -> Unit)? = null) {
    val blocks = remember(text) { parseUiMarkdown(text) }
    val colors = LocalSyColors.current
    SelectionContainer {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            blocks.forEach { block ->
                when (block) {
                    is UiMarkdownBlock.Paragraph -> V6InlineText(
                        block.lines.joinToString(if (preserveLines) "\n" else " "),
                        MaterialTheme.typography.bodyLarge, onOpenFile = onOpenFile)
                    is UiMarkdownBlock.Heading -> V6InlineText(block.text,
                        when (block.level) {
                            1 -> MaterialTheme.typography.headlineSmall
                            2 -> MaterialTheme.typography.titleLarge
                            else -> MaterialTheme.typography.titleMedium
                        }, Modifier.padding(top = 8.dp), onOpenFile)
                    is UiMarkdownBlock.Blockquote -> Column(Modifier.fillMaxWidth()
                        .background(colors.surfaceSolid).padding(12.dp)) {
                        V6InlineText(block.lines.joinToString("\n"), MaterialTheme.typography.bodyLarge,
                            onOpenFile = onOpenFile)
                    }
                    is UiMarkdownBlock.Code -> V6CodeBlock(block)
                    is UiMarkdownBlock.MdList -> {
                        val counters = IntArray(7)
                        block.items.forEachIndexed { index, item ->
                            val ordered = block.numbered.getOrElse(index) { block.ordered }
                            val level = block.levels.getOrElse(index) { 0 }.coerceIn(0, 6)
                            val marker = if (ordered) {
                                counters[level]++
                                for (next in level + 1 until counters.size) counters[next] = 0
                                "${counters[level]}."
                            } else "•"
                            Row(Modifier.fillMaxWidth().padding(start =
                                (level * 14).dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(marker, color = colors.textSecondary)
                                Box(Modifier.weight(1f)) { V6InlineText(item,
                                    MaterialTheme.typography.bodyLarge, onOpenFile = onOpenFile) }
                            }
                        }
                    }
                    is UiMarkdownBlock.Table -> Column(Modifier.horizontalScroll(rememberScrollState())) {
                        (listOf(block.header) + block.rows).forEachIndexed { rowIndex, cells ->
                            Row(Modifier.background(if (rowIndex == 0) colors.selected else Color.Transparent)) {
                                block.header.indices.forEach { col ->
                                    Box(Modifier.width(180.dp).padding(10.dp)) {
                                        V6InlineText(cells.getOrElse(col) { "" },
                                            if (rowIndex == 0) MaterialTheme.typography.titleSmall
                                            else MaterialTheme.typography.bodyMedium,
                                            onOpenFile = onOpenFile,
                                            align = when (block.alignments.getOrNull(col)) {
                                                UiTableAlignment.CENTER -> TextAlign.Center
                                                UiTableAlignment.END -> TextAlign.End
                                                else -> TextAlign.Start
                                            })
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun V6InlineText(text: String, style: TextStyle, modifier: Modifier = Modifier,
    onOpenFile: ((String) -> Unit)? = null, align: TextAlign = TextAlign.Start) {
    val colors = LocalSyColors.current
    val uriHandler = LocalUriHandler.current
    val annotated = buildAnnotatedString {
        parseUiMarkdownInline(text).forEach { part ->
            when (part) {
                is UiMarkdownInline.Plain -> append(part.text)
                is UiMarkdownInline.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(part.text) }
                is UiMarkdownInline.Italic -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(part.text) }
                is UiMarkdownInline.Strikethrough -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(part.text) }
                is UiMarkdownInline.Code -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace,
                    background = colors.surfaceSolid)) { append(part.text) }
                is UiMarkdownInline.Link -> {
                    val url = part.url
                    if (url.startsWith("https://") || url.startsWith("http://")) {
                        withLink(LinkAnnotation.Url(url,
                            styles = TextLinkStyles(SpanStyle(color = colors.text,
                                textDecoration = TextDecoration.Underline)),
                            linkInteractionListener = { runCatching { uriHandler.openUri(url) } })) { append(part.text) }
                    } else if (onOpenFile != null && isV6ReadableFileLink(url)) {
                        withLink(LinkAnnotation.Clickable(url,
                            styles = TextLinkStyles(SpanStyle(color = colors.text,
                                textDecoration = TextDecoration.Underline)),
                            linkInteractionListener = { v6ReadableFilePath(url)?.let(onOpenFile) })) { append(part.text) }
                    } else append(part.text)
                }
            }
        }
    }
    Text(annotated, modifier.fillMaxWidth(), color = colors.text, style = style, textAlign = align)
}

/** File opening still passes through the workspace Owner; no arbitrary URI schemes. */
internal fun isV6ReadableFileLink(url: String): Boolean = v6ReadableFilePath(url) != null

internal fun v6ReadableFilePath(url: String): String? {
    val raw = url.trim().removeSurrounding("<", ">")
    if (raw.isBlank() || raw.startsWith("#")) return null
    if (raw.startsWith("file://")) return runCatching {
        val uri = java.net.URI(raw)
        uri.path.takeIf { uri.authority.isNullOrEmpty() && !it.isNullOrBlank() }
    }.getOrNull()
    if (Regex("^[A-Za-z][A-Za-z0-9+.-]*:").containsMatchIn(raw)) return null
    return raw.substringBefore('#')
}

@Composable
private fun V6CodeBlock(block: UiMarkdownBlock.Code) {
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val colors = LocalSyColors.current
    Column(Modifier.fillMaxWidth().background(colors.surfaceSolid).padding(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(block.lang.orEmpty(), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
                color = colors.textSecondary)
            SyButton("复制代码", { clipboard.setText(AnnotatedString(block.code)) }, style = SyButtonStyle.GHOST)
        }
        Text(block.code, Modifier.horizontalScroll(rememberScrollState()), color = colors.text,
            fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium, softWrap = false)
    }
}

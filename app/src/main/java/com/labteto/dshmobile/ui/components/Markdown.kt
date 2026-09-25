package com.labteto.dshmobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.text.ClickableText
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.ui.theme.DsColors
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.DshTheme

val LocalFileOpener = staticCompositionLocalOf<(String) -> Unit> { {} }

/**
 * Block-level Markdown renderer: fenced code blocks, #-#### headings, bullet and
 * ordered lists, blockquotes, and paragraphs with inline **bold**, *italic*,
 * `code` chips and [links](https://example.com). Pipe tables render as scrollable grids.
 */
@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier, allowCodeCopy: Boolean = true) {
    val colors = DsTheme.colors
    val blocks = remember(text) { parseMarkdown(text) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Heading -> {
                    val style = when (block.level) {
                        1 -> DsType.mdH1
                        2 -> DsType.mdH2
                        3 -> DsType.mdH3
                        else -> DsType.mdH4
                    }
                    InlineMarkdown(block.text, style.copy(color = colors.labelPrimary), Modifier.padding(top = 10.dp))
                }
                is MdBlock.Paragraph -> InlineMarkdown(
                    block.lines.joinToString(" "),
                    DsType.mdBody.copy(color = colors.labelPrimary),
                    Modifier.fillMaxWidth(),
                )
                is MdBlock.MdList -> MdListBlock(block)
                is MdBlock.Blockquote -> MdBlockquote(block)
                is MdBlock.Code -> CodeBlock(block.lang, block.code, allowCopy = allowCodeCopy)
                is MdBlock.Table -> MarkdownTable(block)
            }
        }
    }
}

// ---- Parser (deterministic, line-based) ------------------------------------

private val HEADING_REGEX = Regex("^(#{1,4})\\s+(.*)$")
private val ORDERED_REGEX = Regex("^\\d+\\.\\s+")

internal enum class TableAlignment { START, CENTER, END }

internal sealed interface MdBlock {
    data class Paragraph(val lines: List<String>) : MdBlock
    data class Heading(val level: Int, val text: String) : MdBlock
    data class MdList(val items: List<String>, val ordered: Boolean) : MdBlock
    data class Blockquote(val lines: List<String>) : MdBlock
    data class Code(val lang: String?, val code: String) : MdBlock
    data class Table(
        val header: List<String>,
        val rows: List<List<String>>,
        val alignments: List<TableAlignment>,
    ) : MdBlock
}

internal fun parseMarkdown(markdown: String): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    val lines = markdown.replace("\r\n", "\n").split("\n")
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trimStart()
        when {
            trimmed.startsWith("```") -> {
                val lang = trimmed.removePrefix("```").trim().ifEmpty { null }
                val code = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                    code.append(lines[i]).append('\n')
                    i++
                }
                i++ // skip closing fence
                blocks += MdBlock.Code(lang, code.toString().trimEnd('\n'))
            }
            HEADING_REGEX.matches(trimmed) -> {
                val match = checkNotNull(HEADING_REGEX.matchEntire(trimmed)) { "标题正则匹配状态不一致" }
                val level = match.groupValues[1].length
                val text = match.groupValues[2].trim().trimEnd('#').trim()
                blocks += MdBlock.Heading(level, text)
                i++
            }
            trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                val items = mutableListOf<String>()
                while (i < lines.size) {
                    val t = lines[i].trimStart()
                    if (!t.startsWith("- ") && !t.startsWith("* ")) break
                    items += t.removePrefix("- ").removePrefix("* ").trim()
                    i++
                }
                blocks += MdBlock.MdList(items, ordered = false)
            }
            ORDERED_REGEX.containsMatchIn(trimmed) -> {
                val items = mutableListOf<String>()
                while (i < lines.size && ORDERED_REGEX.containsMatchIn(lines[i].trimStart())) {
                    items += ORDERED_REGEX.replace(lines[i].trim(), "").trim()
                    i++
                }
                blocks += MdBlock.MdList(items, ordered = true)
            }
            trimmed.startsWith(">") -> {
                val quote = mutableListOf<String>()
                while (i < lines.size && lines[i].trimStart().startsWith(">")) {
                    quote += lines[i].trim().removePrefix(">").trim()
                    i++
                }
                blocks += MdBlock.Blockquote(quote)
            }
            isMarkdownTableStart(lines, i) -> {
                val header = splitTableRow(lines[i])
                val alignments = parseTableAlignments(lines[i + 1], header.size)
                val rows = mutableListOf<List<String>>()
                i += 2
                while (i < lines.size && lines[i].trimStart().startsWith("|")) {
                    val cells = splitTableRow(lines[i])
                    rows += List(header.size) { column -> cells.getOrElse(column) { "" } }
                    i++
                }
                blocks += MdBlock.Table(header, rows, alignments)
            }
            line.isBlank() -> i++
            else -> {
                val para = mutableListOf(line)
                i++
                while (i < lines.size && lines[i].isNotBlank() && !isSpecialLine(lines[i])) {
                    para += lines[i]
                    i++
                }
                blocks += MdBlock.Paragraph(para)
            }
        }
    }
    return blocks
}

private fun isSpecialLine(line: String): Boolean {
    val trimmed = line.trimStart()
    return trimmed.startsWith("```") ||
        HEADING_REGEX.matches(trimmed) ||
        trimmed.startsWith("- ") ||
        trimmed.startsWith("* ") ||
        ORDERED_REGEX.containsMatchIn(trimmed) ||
        trimmed.startsWith(">") ||
        trimmed.startsWith("|")
}

private fun isMarkdownTableStart(lines: List<String>, index: Int): Boolean =
    index + 1 < lines.size &&
        lines[index].trimStart().startsWith("|") &&
        isTableSeparator(lines[index + 1])

/** A separator cell is at least three dashes with optional leading/trailing alignment colons. */
internal fun isTableSeparator(line: String): Boolean {
    if (!line.trimStart().startsWith("|")) return false
    val cells = splitTableRow(line)
    return cells.isNotEmpty() && cells.all { it.trim().matches(Regex("^:?-{3,}:?$")) }
}

internal fun parseTableAlignments(line: String, columns: Int): List<TableAlignment> {
    val cells = splitTableRow(line)
    return List(columns) { index ->
        val cell = cells.getOrElse(index) { "" }.trim()
        when {
            cell.startsWith(":") && cell.endsWith(":") -> TableAlignment.CENTER
            cell.endsWith(":") -> TableAlignment.END
            else -> TableAlignment.START
        }
    }
}

/**
 * Split one pipe row while respecting escaped pipes and pipes inside inline code.
 * Outer pipes are syntax and are not returned as empty cells.
 */
internal fun splitTableRow(line: String): List<String> {
    val source = line.trim().removePrefix("|").removeSuffix("|")
    val cells = mutableListOf<String>()
    val current = StringBuilder()
    var escaped = false
    var inCode = false
    source.forEach { char ->
        when {
            escaped -> {
                current.append(char)
                escaped = false
            }
            char == '\\' -> escaped = true
            char == '`' -> {
                inCode = !inCode
                current.append(char)
            }
            char == '|' && !inCode -> {
                cells += current.toString().trim()
                current.clear()
            }
            else -> current.append(char)
        }
    }
    if (escaped) current.append('\\')
    cells += current.toString().trim()
    return cells
}

// ---- Inline rendering ------------------------------------------------------

private sealed interface InlineSegment {
    data class Plain(val text: String) : InlineSegment
    data class Bold(val text: String) : InlineSegment
    data class Italic(val text: String) : InlineSegment
    data class Code(val text: String) : InlineSegment
    data class Link(val text: String, val url: String) : InlineSegment
}

private fun parseInlineSegments(text: String): List<InlineSegment> {
    val segments = mutableListOf<InlineSegment>()
    val sb = StringBuilder()
    var i = 0
    fun flush() {
        if (sb.isNotEmpty()) {
            segments += InlineSegment.Plain(sb.toString())
            sb.clear()
        }
    }
    while (i < text.length) {
        when {
            text.startsWith("`", i) -> {
                val end = text.indexOf('`', i + 1)
                if (end != -1) {
                    flush()
                    segments += InlineSegment.Code(text.substring(i + 1, end))
                    i = end + 1
                } else {
                    sb.append(text[i]); i++
                }
            }
            text.startsWith("**", i) -> {
                val end = text.indexOf("**", i + 2)
                if (end != -1) {
                    flush()
                    segments += InlineSegment.Bold(text.substring(i + 2, end))
                    i = end + 2
                } else {
                    sb.append(text[i]); i++
                }
            }
            text.startsWith("*", i) -> {
                val end = text.indexOf("*", i + 1)
                if (end != -1) {
                    flush()
                    segments += InlineSegment.Italic(text.substring(i + 1, end))
                    i = end + 1
                } else {
                    sb.append(text[i]); i++
                }
            }
            text.startsWith("[", i) -> {
                val close = text.indexOf("](", i + 1)
                if (close != -1) {
                    val end = text.indexOf(')', close + 2)
                    if (end != -1) {
                        flush()
                        segments += InlineSegment.Link(text.substring(i + 1, close), text.substring(close + 2, end))
                        i = end + 1
                    } else {
                        sb.append(text[i]); i++
                    }
                } else {
                    sb.append(text[i]); i++
                }
            }
            else -> {
                sb.append(text[i]); i++
            }
        }
    }
    flush()
    return segments
}

/** Renders one line of markdown with bold/italic/code/link spans. */
@Composable
private fun InlineMarkdown(text: String, style: TextStyle, modifier: Modifier = Modifier) {
    val colors = DsTheme.colors
    val codeStyle = style.copy(
        fontFamily = DsType.codeFont,
        color = colors.labelPrimary,
    )
    val result = remember(text, style, codeStyle, colors) {
        buildInlineContent(text, codeStyle, colors)
    }
    val openFile = LocalFileOpener.current
    val uriHandler = LocalUriHandler.current
    ClickableText(
        result, modifier = modifier, style = style,
        onClick = { offset ->
            result.getStringAnnotations("url", offset, offset).firstOrNull()?.item?.let { url ->
                if (url.startsWith("https://") || url.startsWith("http://")) runCatching { uriHandler.openUri(url) }
                else com.labteto.dshmobile.ui.screens.main.previewPath(url)?.let(openFile)
            }
        },
    )
}

private fun buildInlineContent(
    text: String,
    codeStyle: TextStyle,
    colors: DsColors,
): AnnotatedString {
    val builder = AnnotatedString.Builder()
    parseInlineSegments(text).forEach { segment ->
        when (segment) {
            is InlineSegment.Plain -> builder.append(segment.text)
            is InlineSegment.Bold -> builder.withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(segment.text) }
            is InlineSegment.Italic -> builder.withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(segment.text) }
            is InlineSegment.Code -> builder.withStyle(
                SpanStyle(fontFamily = codeStyle.fontFamily, color = codeStyle.color),
            ) { append(segment.text) }
            is InlineSegment.Link -> {
                builder.pushStringAnnotation("url", segment.url)
                builder.withStyle(SpanStyle(color = colors.accent)) { append(segment.text) }
                builder.pop()
            }
        }
    }
    return builder.toAnnotatedString()
}

// ---- Block renderers --------------------------------------------------------

@Composable
private fun MdListBlock(block: MdBlock.MdList) {
    val colors = DsTheme.colors
    Column(Modifier.fillMaxWidth().padding(start = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        block.items.forEachIndexed { index, item ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Text(
                    if (block.ordered) "${index + 1}." else "•",
                    style = DsType.mdBody.copy(color = colors.labelSecondary),
                    textAlign = if (block.ordered) TextAlign.End else TextAlign.Start,
                    modifier = Modifier.width(if (block.ordered) 28.dp else 18.dp),
                )
                Spacer(Modifier.width(6.dp))
                InlineMarkdown(item, DsType.mdBody.copy(color = colors.labelPrimary), Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun MdBlockquote(block: MdBlock.Blockquote) {
    val colors = DsTheme.colors
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(vertical = 2.dp)) {
        Box(
            Modifier
                .width(2.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(1.dp))
                .background(colors.citation),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            block.lines.forEach { line ->
                InlineMarkdown(line, DsType.mdSmall.copy(color = colors.labelTertiary), Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun MarkdownTable(block: MdBlock.Table) {
    val colors = DsTheme.colors
    val allRows = listOf(block.header) + block.rows
    val widths = remember(block) {
        block.header.indices.map { column ->
            val maxUnits = allRows.maxOfOrNull { row ->
                row.getOrElse(column) { "" }.sumOf { char -> if (char.code > 0x7f) 2 else 1 }
            } ?: 0
            (maxUnits * 7 + 28).coerceIn(96, 240).dp
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .clip(DsShapes.block)
            .border(1.dp, colors.borderL1, DsShapes.block),
    ) {
        fun textAlign(column: Int): TextAlign = when (block.alignments.getOrElse(column) { TableAlignment.START }) {
            TableAlignment.START -> TextAlign.Start
            TableAlignment.CENTER -> TextAlign.Center
            TableAlignment.END -> TextAlign.End
        }

        Row(Modifier.background(colors.bgLayer1)) {
            block.header.forEachIndexed { column, cell ->
                InlineMarkdown(
                    cell,
                    DsType.mdSmall.copy(
                        color = colors.labelPrimary,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = textAlign(column),
                    ),
                    Modifier
                        .width(widths[column])
                        .border(0.5.dp, colors.borderL1)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                )
            }
        }
        block.rows.forEach { row ->
            Row {
                block.header.indices.forEach { column ->
                    InlineMarkdown(
                        row.getOrElse(column) { "" },
                        DsType.mdSmall.copy(
                            color = colors.labelPrimary,
                            textAlign = textAlign(column),
                        ),
                        Modifier
                            .width(widths[column])
                            .border(0.5.dp, colors.borderL1)
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

/** Fenced code block with a sticky banner (lang · copy) and a mono pre. */
@Composable
private fun CodeBlock(
    lang: String?,
    code: String,
    modifier: Modifier = Modifier,
    allowCopy: Boolean = true,
) {
    val colors = DsTheme.colors
    val clipboard = LocalClipboardManager.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(DsShapes.block)
            .background(colors.codeBlockBg)
            .border(1.dp, colors.borderL1, DsShapes.block),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.codeBlockBanner)
                .padding(start = 12.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                when {
                    allowCopy && lang != null -> "$lang · copy"
                    allowCopy -> "copy"
                    lang != null -> lang
                    else -> "code"
                },
                style = DsType.caption11Strong.copy(fontFamily = DsType.codeFont, color = colors.labelCaption),
                color = colors.labelCaption,
                modifier = Modifier.weight(1f),
            )
            if (allowCopy) {
                Icon(
                    Icons.Filled.ContentCopy,
                    contentDescription = "Copy code",
                    tint = colors.labelTertiary,
                    modifier = Modifier
                        .size(16.dp)
                        .clip(DsShapes.chip)
                        .clickable { clipboard.setText(AnnotatedString(code)) }
                        .padding(2.dp),
                )
            }
        }
        Text(
            code,
            style = DsType.mdCode,
            color = colors.labelPrimary,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        )
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun MarkdownTextPreview() {
    DshTheme {
        MarkdownText(
            text = """
                # Heading

                A paragraph with **bold**, *italic* and `inline code` plus a [link](https://example.com).

                - first item
                - second item

                1. ordered one
                2. ordered two

                > A quoted thought.

                ```kotlin
                val answer = 42
                ```

                | col a | col b |
                | ----- | ----- |
                | 1     | 2     |
            """.trimIndent(),
            modifier = Modifier.padding(16.dp),
        )
    }
}

package com.labteto.dshmobile.ui.components

import com.shenyan.designsystem.foundation.SyMetric
import com.labteto.dshmobile.uicontract.UiMarkdownBlock
import com.labteto.dshmobile.uicontract.UiMarkdownInline
import com.labteto.dshmobile.uicontract.parseUiMarkdown
import com.labteto.dshmobile.uicontract.parseUiMarkdownInline
import com.labteto.dshmobile.uicontract.isUiMarkdownTableSeparator
import com.labteto.dshmobile.uicontract.parseUiMarkdownTableAlignments
import com.labteto.dshmobile.uicontract.splitUiMarkdownTableRow

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.text.ClickableText
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import com.labteto.dshmobile.R
import com.shenyan.designsystem.foundation.SyColors
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.withReadingWeight
import com.labteto.dshmobile.ui.theme.DshTheme

val LocalFileOpener = staticCompositionLocalOf<(String) -> Unit> { {} }

/**
 * Block-level Markdown renderer: fenced code blocks, #-#### headings, bullet and
 * ordered lists, blockquotes, and paragraphs with inline **bold**, *italic*,
 * `code` chips and [links](https://example.com). Pipe tables render as scrollable grids.
 */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    allowCodeCopy: Boolean = true,
    bodyStyle: TextStyle = DsType.mdBody,
    preserveSoftLineBreaks: Boolean = false,
) {
    val colors = com.shenyan.designsystem.foundation.LocalSyColors.current
    val blocks = remember(text) { parseMarkdown(text) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Heading -> {
                    val style = when (block.level) {
                        1 -> DsType.mdH1
                        2 -> DsType.mdH2
                        3 -> DsType.mdH3
                        else -> DsType.mdH4
                    }
                    InlineMarkdown(block.text, style.copy(color = colors.text), Modifier.padding(top = 10.dp))
                }
                is MdBlock.Paragraph -> InlineMarkdown(
                    markdownParagraphDisplayText(block.lines, preserveSoftLineBreaks),
                    bodyStyle.copy(color = colors.text),
                    Modifier.fillMaxWidth(),
                )
                is MdBlock.MdList -> MdListBlock(block, bodyStyle)
                is MdBlock.Blockquote -> MdBlockquote(block, bodyStyle)
                is MdBlock.Code -> CodeBlock(block.lang, block.code, allowCopy = allowCodeCopy)
                is MdBlock.Table -> MarkdownTable(block)
            }
        }
    }
}

/** The chat surface respects authored dialogue line breaks; technical Markdown keeps CommonMark folding. */
internal fun markdownParagraphDisplayText(lines: List<String>, preserveSoftLineBreaks: Boolean): String =
    lines.joinToString(if (preserveSoftLineBreaks) "\n" else " ")

// ---- Parser (deterministic, line-based) ------------------------------------

internal enum class TableAlignment { START, CENTER, END }

internal sealed interface MdBlock {
    data class Paragraph(val lines: List<String>) : MdBlock
    data class Heading(val level: Int, val text: String) : MdBlock
    data class MdList(
        val items: List<String>,
        val ordered: Boolean,
        val levels: List<Int> = emptyList(),
        val numbered: List<Boolean> = emptyList(),
    ) : MdBlock
    data class Blockquote(val lines: List<String>) : MdBlock
    data class Code(val lang: String?, val code: String) : MdBlock
    data class Table(val header: List<String>, val rows: List<List<String>>, val alignments: List<TableAlignment>) : MdBlock
}

/** Retain V6 public test/renderer shapes while both packs use the same neutral parser. */
internal fun parseMarkdown(markdown: String): List<MdBlock> = parseUiMarkdown(markdown).map { block ->
    when (block) {
        is UiMarkdownBlock.Paragraph -> MdBlock.Paragraph(block.lines)
        is UiMarkdownBlock.Heading -> MdBlock.Heading(block.level, block.text)
        is UiMarkdownBlock.MdList -> MdBlock.MdList(block.items, block.ordered, block.levels, block.numbered)
        is UiMarkdownBlock.Blockquote -> MdBlock.Blockquote(block.lines)
        is UiMarkdownBlock.Code -> MdBlock.Code(block.lang, block.code)
        is UiMarkdownBlock.Table -> MdBlock.Table(block.header, block.rows, block.alignments.map { TableAlignment.valueOf(it.name) })
    }
}
internal fun isTableSeparator(line: String): Boolean = isUiMarkdownTableSeparator(line)
internal fun parseTableAlignments(line: String, columns: Int): List<TableAlignment> =
    parseUiMarkdownTableAlignments(line, columns).map { TableAlignment.valueOf(it.name) }
internal fun splitTableRow(line: String): List<String> = splitUiMarkdownTableRow(line)

// ---- Inline rendering ------------------------------------------------------

/** Renders one line of markdown with bold/italic/code/link spans. */
@Composable
private fun InlineMarkdown(text: String, style: TextStyle, modifier: Modifier = Modifier) {
    val colors = com.shenyan.designsystem.foundation.LocalSyColors.current
    val effectiveStyle = style.withReadingWeight()
    val codeStyle = effectiveStyle.copy(
        fontFamily = DsType.codeFont,
        color = colors.text,
    )
    val result = remember(text, effectiveStyle, codeStyle, colors) {
        buildInlineContent(text, codeStyle, colors)
    }
    val openFile = LocalFileOpener.current
    val uriHandler = LocalUriHandler.current
    ClickableText(
        result, modifier = modifier, style = effectiveStyle,
        onClick = { offset ->
            result.getStringAnnotations("url", offset, offset).firstOrNull()?.item?.let { url ->
                if (url.startsWith("https://") || url.startsWith("http://")) runCatching { uriHandler.openUri(url) }
                else localPreviewPath(url)?.let(openFile)
            }
        },
    )
}

private fun buildInlineContent(
    text: String,
    codeStyle: TextStyle,
    colors: SyColors,
): AnnotatedString {
    val builder = AnnotatedString.Builder()
    parseUiMarkdownInline(text).forEach { segment ->
        when (segment) {
            is UiMarkdownInline.Plain -> builder.append(segment.text)
            is UiMarkdownInline.Bold -> builder.withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(segment.text) }
            is UiMarkdownInline.Italic -> builder.withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(segment.text) }
            is UiMarkdownInline.Strikethrough -> builder.withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(segment.text) }
            is UiMarkdownInline.Code -> builder.withStyle(
                SpanStyle(fontFamily = codeStyle.fontFamily, color = codeStyle.color, background = colors.materialL1),
            ) { append(segment.text) }
            is UiMarkdownInline.Link -> {
                builder.pushStringAnnotation("url", segment.url)
                builder.withStyle(SpanStyle(color = colors.primary)) { append(segment.text) }
                builder.pop()
            }
        }
    }
    return builder.toAnnotatedString()
}

// ---- Block renderers --------------------------------------------------------

@Composable
private fun MdListBlock(block: MdBlock.MdList, bodyStyle: TextStyle) {
    val colors = com.shenyan.designsystem.foundation.LocalSyColors.current
    val counters = IntArray(7)
    Column(Modifier.fillMaxWidth().padding(start = 4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        block.items.forEachIndexed { index, item ->
            val level = block.levels.getOrElse(index) { 0 }.coerceIn(0, 6)
            val ordered = block.numbered.getOrElse(index) { block.ordered }
            val marker = if (ordered) {
                counters[level]++
                for (next in level + 1 until counters.size) counters[next] = 0
                "${counters[level]}."
            } else "•"
            Row(
                Modifier.fillMaxWidth().padding(start = (level * 15).dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    marker,
                    style = bodyStyle.withReadingWeight().copy(color = colors.textSecondary),
                    textAlign = if (ordered) TextAlign.End else TextAlign.Start,
                    modifier = Modifier.width(if (ordered) 28.dp else 18.dp),
                )
                Spacer(Modifier.width(6.dp))
                InlineMarkdown(item, bodyStyle.copy(color = colors.text), Modifier.weight(1f))
            }
        }
    }
}

private val CODE_SYNTAX_TOKENS = Regex(
    """//[^\n]*|#[^\n]*|"[^"\n]*"|'[^'\n]*'|\b(?:fun|val|var|class|object|interface|data|sealed|return|if|else|when|for|while|try|catch|import|package|private|public|internal|override|suspend|const|let|def|from|async|await|function|true|false|null|None|True|False)\b""",
)

internal fun highlightedCode(code: String, language: String?, colors: SyColors): AnnotatedString {
    val supported = setOf("kotlin", "kt", "java", "python", "py", "javascript", "js", "typescript", "ts")
    val normalizedLanguage = language?.trim()?.lowercase().orEmpty()
    if (normalizedLanguage !in supported || code.length > 20_000) return AnnotatedString(code)
    val builder = AnnotatedString.Builder(code)
    CODE_SYNTAX_TOKENS.findAll(code).forEach { match ->
        val token = match.value
        val tint = when {
            token.startsWith("//") || token.startsWith("#") -> colors.textSecondary
            token.firstOrNull() == '"' || token.startsWith("'") -> colors.warningInk
            else -> colors.primary
        }
        builder.addStyle(SpanStyle(color = tint), match.range.first, match.range.last + 1)
    }
    return builder.toAnnotatedString()
}

internal enum class MarkdownCalloutKind { QUOTE, NOTE, IMPORTANT, WARNING, TIP }

internal fun markdownCalloutKind(lines: List<String>): MarkdownCalloutKind = when (
    lines.firstOrNull()?.trim()?.uppercase()
) {
    "[!NOTE]" -> MarkdownCalloutKind.NOTE
    "[!IMPORTANT]" -> MarkdownCalloutKind.IMPORTANT
    "[!WARNING]", "[!CAUTION]" -> MarkdownCalloutKind.WARNING
    "[!TIP]" -> MarkdownCalloutKind.TIP
    else -> MarkdownCalloutKind.QUOTE
}

@Composable
private fun MdBlockquote(block: MdBlock.Blockquote, bodyStyle: TextStyle) {
    val colors = com.shenyan.designsystem.foundation.LocalSyColors.current
    val kind = remember(block.lines) { markdownCalloutKind(block.lines) }
    val title = when (kind) {
        MarkdownCalloutKind.NOTE -> stringResource(R.string.markdown_callout_note)
        MarkdownCalloutKind.IMPORTANT -> stringResource(R.string.markdown_callout_important)
        MarkdownCalloutKind.WARNING -> stringResource(R.string.markdown_callout_warning)
        MarkdownCalloutKind.TIP -> stringResource(R.string.markdown_callout_tip)
        MarkdownCalloutKind.QUOTE -> null
    }
    val stripeColor = when (kind) {
        MarkdownCalloutKind.WARNING -> colors.warning
        MarkdownCalloutKind.IMPORTANT -> colors.primary
        else -> colors.textSecondary
    }
    val content = if (title != null) block.lines.drop(1) else block.lines
    Row(
        Modifier
            .fillMaxWidth()
            .clip(DsShapes.block)
            .background(colors.materialL3)
            .height(IntrinsicSize.Min)
            .padding(vertical = 10.dp, horizontal = 12.dp),
    ) {
        Box(
            Modifier
                .width(3.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(2.dp))
                .background(stripeColor),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (title != null) Text(
                title,
                style = DsType.mdSmall.withReadingWeight().copy(fontWeight = FontWeight.SemiBold),
                color = if (kind == MarkdownCalloutKind.WARNING) colors.warningInk
                    else if (kind == MarkdownCalloutKind.IMPORTANT) colors.primary
                    else colors.text,
            )
            content.forEach { line ->
                InlineMarkdown(
                    line,
                    bodyStyle.copy(color = colors.text),
                    Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun MarkdownTable(block: MdBlock.Table) {
    val colors = com.shenyan.designsystem.foundation.LocalSyColors.current
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
            .border(1.dp, colors.border, DsShapes.block),
    ) {
        fun textAlign(column: Int): TextAlign = when (block.alignments.getOrElse(column) { TableAlignment.START }) {
            TableAlignment.START -> TextAlign.Start
            TableAlignment.CENTER -> TextAlign.Center
            TableAlignment.END -> TextAlign.End
        }

        Row(Modifier.background(colors.materialL3)) {
            block.header.forEachIndexed { column, cell ->
                InlineMarkdown(
                    cell,
                    DsType.mdSmall.copy(
                        color = colors.text,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = textAlign(column),
                    ),
                    Modifier
                        .width(widths[column])
                        .border(0.5.dp, colors.border)
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
                            color = colors.text,
                            textAlign = textAlign(column),
                        ),
                        Modifier
                            .width(widths[column])
                            .border(0.5.dp, colors.border)
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
    val colors = com.shenyan.designsystem.foundation.LocalSyColors.current
    val clipboard = LocalClipboardManager.current
    val styledCode = remember(code, lang, colors) { highlightedCode(code, lang, colors) }
    var copied by remember(code) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1500)
            copied = false
        }
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(DsShapes.block)
            .background(colors.materialL2)
            .border(1.dp, colors.border, DsShapes.block),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.materialL1)
                .padding(start = 12.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                lang ?: stringResource(R.string.markdown_code_language),
                style = DsType.caption11Strong.withReadingWeight().copy(fontFamily = DsType.codeFont),
                color = colors.textSecondary,
                modifier = Modifier.weight(1f),
            )
            if (allowCopy) {
                Box(
                    modifier = Modifier
                        .size(SyMetric.touch)
                        .clip(DsShapes.chip)
                        .clickable {
                            clipboard.setText(AnnotatedString(code))
                            copied = true
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (copied) FeatherIcons.Check else FeatherIcons.Copy,
                        contentDescription = stringResource(
                            if (copied) R.string.chat_copy_success else R.string.markdown_copy_code
                        ),
                        tint = colors.textSecondary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Text(
                styledCode,
                style = DsType.mdCode.withReadingWeight(),
                color = colors.text,
                softWrap = false,
            )
        }
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

private fun localPreviewPath(value: String): String? {
    val raw = value.trim().removeSurrounding("<", ">")
    if (raw.isEmpty() || raw.startsWith('#')) return null
    if (raw.startsWith("file://")) return runCatching { java.net.URI(raw).path }.getOrNull()
    if (Regex("^[A-Za-z]:[/\\\\]").containsMatchIn(raw)) return raw
    if (Regex("^[A-Za-z][A-Za-z0-9+.-]*:").containsMatchIn(raw)) return null
    return raw.substringBefore('#')
}

package com.labteto.dshmobile.uicontract

/** Shared presentation-only Markdown parser, without model, permission or UI dependencies. */
private val HEADING_REGEX = Regex("^(#{1,4})\\s+(.*)$")
private val ORDERED_REGEX = Regex("^\\d+\\.\\s+")

enum class UiTableAlignment { START, CENTER, END }

sealed interface UiMarkdownBlock {
    data class Paragraph(val lines: List<String>) : UiMarkdownBlock
    data class Heading(val level: Int, val text: String) : UiMarkdownBlock
    data class MdList(
        val items: List<String>,
        val ordered: Boolean,
        val levels: List<Int> = emptyList(),
        val numbered: List<Boolean> = emptyList(),
    ) : UiMarkdownBlock
    data class Blockquote(val lines: List<String>) : UiMarkdownBlock
    data class Code(val lang: String?, val code: String) : UiMarkdownBlock
    data class Table(
        val header: List<String>,
        val rows: List<List<String>>,
        val alignments: List<UiTableAlignment>,
    ) : UiMarkdownBlock
}

fun parseUiMarkdown(markdown: String): List<UiMarkdownBlock> {
    val blocks = mutableListOf<UiMarkdownBlock>()
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
                blocks += UiMarkdownBlock.Code(lang, code.toString().trimEnd('\n'))
            }
            HEADING_REGEX.matches(trimmed) -> {
                val match = checkNotNull(HEADING_REGEX.matchEntire(trimmed))
                val level = match.groupValues[1].length
                val text = match.groupValues[2].trim().trimEnd('#').trim()
                blocks += UiMarkdownBlock.Heading(level, text)
                i++
            }
            isMarkdownListItem(trimmed) -> {
                val items = mutableListOf<String>()
                val levels = mutableListOf<Int>()
                val numbered = mutableListOf<Boolean>()
                while (i < lines.size) {
                    val source = lines[i]
                    val item = source.trimStart()
                    if (!isMarkdownListItem(item)) break
                    val isNumbered = ORDERED_REGEX.containsMatchIn(item)
                    numbered += isNumbered
                    levels += ((source.length - item.length) / 2).coerceIn(0, 6)
                    items += if (isNumbered) ORDERED_REGEX.replace(item, "").trim()
                        else item.drop(2).trim()
                    i++
                }
                blocks += UiMarkdownBlock.MdList(items, numbered.firstOrNull() == true, levels, numbered)
            }
            trimmed.startsWith(">") -> {
                val quote = mutableListOf<String>()
                while (i < lines.size && lines[i].trimStart().startsWith(">")) {
                    quote += lines[i].trim().removePrefix(">").trim()
                    i++
                }
                blocks += UiMarkdownBlock.Blockquote(quote)
            }
            isMarkdownTableStart(lines, i) -> {
                val header = splitUiMarkdownTableRow(lines[i])
                val alignments = parseUiMarkdownTableAlignments(lines[i + 1], header.size)
                val rows = mutableListOf<List<String>>()
                i += 2
                while (i < lines.size && isMarkdownTableRow(lines[i])) {
                    val cells = splitUiMarkdownTableRow(lines[i])
                    rows += List(header.size) { column -> cells.getOrElse(column) { "" } }
                    i++
                }
                blocks += UiMarkdownBlock.Table(header, rows, alignments)
            }
            line.isBlank() -> i++
            else -> {
                val para = mutableListOf(line)
                i++
                while (i < lines.size && lines[i].isNotBlank() && !isSpecialLine(lines[i]) &&
                    !isMarkdownTableStart(lines, i)) {
                    para += lines[i]
                    i++
                }
                blocks += UiMarkdownBlock.Paragraph(para)
            }
        }
    }
    return blocks
}

private fun isMarkdownListItem(text: String): Boolean =
    text.startsWith("- ") || text.startsWith("* ") || text.startsWith("+ ") ||
        ORDERED_REGEX.containsMatchIn(text)

private fun isSpecialLine(line: String): Boolean {
    val trimmed = line.trimStart()
    return trimmed.startsWith("```") ||
        HEADING_REGEX.matches(trimmed) ||
        isMarkdownListItem(trimmed) ||
        trimmed.startsWith(">") ||
        trimmed.startsWith("|")
}

private fun isMarkdownTableStart(lines: List<String>, index: Int): Boolean =
    index + 1 < lines.size &&
        isMarkdownTableRow(lines[index]) &&
        isUiMarkdownTableSeparator(lines[index + 1]) &&
        splitUiMarkdownTableRow(lines[index]).size == splitUiMarkdownTableRow(lines[index + 1]).size

/** A table row can omit its outside pipes; escaped and backtick pipes are not delimiters. */
private fun isMarkdownTableRow(line: String): Boolean =
    line.trimStart().startsWith("|") || splitUiMarkdownTableRow(line).size > 1

/** A separator cell is at least three dashes with optional leading/trailing alignment colons. */
fun isUiMarkdownTableSeparator(line: String): Boolean {
    if (!isMarkdownTableRow(line)) return false
    val cells = splitUiMarkdownTableRow(line)
    return cells.isNotEmpty() && cells.all { it.trim().matches(Regex("^:?-{3,}:?$")) }
}

fun parseUiMarkdownTableAlignments(line: String, columns: Int): List<UiTableAlignment> {
    val cells = splitUiMarkdownTableRow(line)
    return List(columns) { index ->
        val cell = cells.getOrElse(index) { "" }.trim()
        when {
            cell.startsWith(":") && cell.endsWith(":") -> UiTableAlignment.CENTER
            cell.endsWith(":") -> UiTableAlignment.END
            else -> UiTableAlignment.START
        }
    }
}

/**
 * Split one pipe row while respecting escaped pipes and pipes inside inline code.
 * Outer pipes are syntax and are not returned as empty cells.
 */
fun splitUiMarkdownTableRow(line: String): List<String> {
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


sealed interface UiMarkdownInline {
    data class Plain(val text: String) : UiMarkdownInline
    data class Bold(val text: String) : UiMarkdownInline
    data class Italic(val text: String) : UiMarkdownInline
    data class Strikethrough(val text: String) : UiMarkdownInline
    data class Code(val text: String) : UiMarkdownInline
    data class Link(val text: String, val url: String) : UiMarkdownInline
}

fun parseUiMarkdownInline(text: String): List<UiMarkdownInline> {
    val segments = mutableListOf<UiMarkdownInline>()
    val sb = StringBuilder()
    var i = 0
    fun flush() {
        if (sb.isNotEmpty()) {
            segments += UiMarkdownInline.Plain(sb.toString())
            sb.clear()
        }
    }
    while (i < text.length) {
        when {
            text.startsWith("`", i) -> {
                val end = text.indexOf('`', i + 1)
                if (end != -1) {
                    flush()
                    segments += UiMarkdownInline.Code(text.substring(i + 1, end))
                    i = end + 1
                } else {
                    sb.append(text[i]); i++
                }
            }
            text.startsWith("**", i) -> {
                val end = text.indexOf("**", i + 2)
                if (end != -1) {
                    flush()
                    segments += UiMarkdownInline.Bold(text.substring(i + 2, end))
                    i = end + 2
                } else {
                    sb.append(text[i]); i++
                }
            }
            text.startsWith("~~", i) -> {
                val end = text.indexOf("~~", i + 2)
                if (end != -1) {
                    flush()
                    segments += UiMarkdownInline.Strikethrough(text.substring(i + 2, end))
                    i = end + 2
                } else {
                    sb.append(text[i]); i++
                }
            }
            text.startsWith("*", i) -> {
                val end = text.indexOf("*", i + 1)
                if (end != -1) {
                    flush()
                    segments += UiMarkdownInline.Italic(text.substring(i + 1, end))
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
                        segments += UiMarkdownInline.Link(text.substring(i + 1, close), text.substring(close + 2, end))
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

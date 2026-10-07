package com.labteto.dshmobile.local.attachment

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.LinkedHashSet
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream
import java.util.zip.ZipFile

internal data class LocalDocumentText(
    val formatLabel: String,
    val text: String,
    val truncated: Boolean,
    val parsed: Boolean = true,
)

internal object LocalDocumentContent {
    private const val DEFAULT_MAX_CHARS = 40_000
    private const val MAX_ZIP_ENTRY_BYTES = 2 * 1024 * 1024
    private const val MAX_ZIP_TOTAL_BYTES = 8 * 1024 * 1024
    private const val MAX_ARCHIVE_ENTRIES = 80

    private val plainTextExtensions = setOf(
        "txt", "md", "markdown", "csv", "tsv", "json", "jsonl", "ndjson",
        "xml", "yaml", "yml", "toml", "ini", "cfg", "conf", "properties", "log",
        "html", "htm", "xhtml", "css", "js", "mjs", "cjs", "jsx", "ts", "tsx",
        "java", "kt", "kts", "py", "pyi", "c", "h", "cc", "cpp", "cxx", "hpp",
        "cs", "go", "rs", "swift", "m", "mm", "php", "rb", "pl", "lua", "sh",
        "bash", "zsh", "fish", "ps1", "bat", "cmd", "sql", "graphql", "gql",
        "proto", "gradle", "groovy", "vue", "svelte", "tex", "bib", "rst",
        "eml", "ics", "vcf", "srt", "vtt", "ipynb", "diff", "patch", "svg",
        "fb2", "env", "editorconfig", "gitignore", "dockerfile",
    )

    private val plainTextMediaTypes = setOf(
        "application/json",
        "application/ld+json",
        "application/xml",
        "application/xhtml+xml",
        "application/yaml",
        "application/x-yaml",
        "application/javascript",
        "application/sql",
        "application/graphql",
    )

    private val wordOpenXmlExtensions = setOf("docx", "docm", "dotx", "dotm")
    private val excelOpenXmlExtensions = setOf("xlsx", "xlsm", "xltx", "xltm")
    private val powerPointOpenXmlExtensions = setOf("pptx", "pptm", "ppsx", "ppsm", "potx", "potm")
    private val openDocumentExtensions = setOf("odt", "ods", "odp", "ott", "ots", "otp")
    private val zipLikeExtensions = setOf("zip", "jar", "aar", "apk", "cbz")
    private val legacyOfficeExtensions = setOf("doc", "xls", "ppt", "wps", "et", "dps")

    fun extract(
        file: File,
        displayName: String,
        mediaType: String,
        maxChars: Int = DEFAULT_MAX_CHARS,
    ): LocalDocumentText {
        require(file.isFile) { "附件不存在：${file.name}" }
        require(maxChars in 1..200_000) { "文档提取字符预算无效" }
        val extension = displayName.substringAfterLast('.', file.extension).lowercase()
        val normalizedMediaType = mediaType.substringBefore(';').trim().lowercase()
        return when {
            extension in plainTextExtensions ||
                normalizedMediaType.startsWith("text/") ||
                normalizedMediaType in plainTextMediaTypes ->
                extractPlainText(file, extension, maxChars)
            extension in wordOpenXmlExtensions ||
                normalizedMediaType in WORD_OPEN_XML_MEDIA_TYPES ->
                extractDocx(file, maxChars)
            extension in excelOpenXmlExtensions ||
                normalizedMediaType in EXCEL_OPEN_XML_MEDIA_TYPES ->
                extractXlsx(file, maxChars)
            extension in powerPointOpenXmlExtensions ||
                normalizedMediaType in POWERPOINT_OPEN_XML_MEDIA_TYPES ->
                extractPptx(file, maxChars)
            extension in openDocumentExtensions ||
                normalizedMediaType.startsWith("application/vnd.oasis.opendocument.") ->
                extractOpenDocument(file, extension, maxChars)
            extension == "epub" || normalizedMediaType == "application/epub+zip" ->
                extractEpub(file, maxChars)
            extension == "rtf" || normalizedMediaType == "application/rtf" -> extractRtf(file, maxChars)
            extension == "pdf" || normalizedMediaType == "application/pdf" -> extractPdfBestEffort(file, maxChars)
            extension in zipLikeExtensions || normalizedMediaType == "application/zip" ->
                extractZip(file, maxChars)
            extension == "gz" || normalizedMediaType in setOf("application/gzip", "application/x-gzip") ->
                extractGzip(file, displayName, maxChars)
            extension == "tar" -> extractTar(file, maxChars)
            extension in legacyOfficeExtensions ||
                normalizedMediaType in LEGACY_OFFICE_MEDIA_TYPES ->
                extractLegacyOffice(
                    file,
                    extension.ifBlank {
                        when (normalizedMediaType) {
                            "application/msword" -> "doc"
                            "application/vnd.ms-excel" -> "xls"
                            else -> "ppt"
                        }
                    },
                    maxChars,
                )
            else -> LocalDocumentText(
                formatLabel = extension.ifBlank { normalizedMediaType.ifBlank { "二进制文件" } }.uppercase(),
                text = "",
                truncated = false,
                parsed = false,
            )
        }
    }

    private fun extractPlainText(file: File, extension: String, maxChars: Int): LocalDocumentText {
        val bytes = readPrefix(file, MAX_ZIP_ENTRY_BYTES)
        var text = decodeText(bytes)
        if (extension in setOf("html", "htm", "xhtml")) text = htmlToText(text)
        val result = bounded(extension.ifBlank { "文本" }.uppercase(), text, maxChars)
        return result.copy(truncated = result.truncated || file.length() > bytes.size)
    }

    private fun extractDocx(file: File, maxChars: Int): LocalDocumentText {
        val out = BoundedText(maxChars)
        withSafeZip(file) { zip ->
            val names = buildList {
                add("word/document.xml")
                zip.entries().asSequence()
                    .map { it.name }
                    .filter {
                        it.matches(Regex("""word/(?:header|footer)\d+\.xml""")) ||
                            it in setOf("word/footnotes.xml", "word/endnotes.xml", "word/comments.xml")
                    }
                    .sorted()
                    .forEach(::add)
            }
            names.distinct().forEach { name ->
                val xml = zip.readEntryText(name) ?: return@forEach
                val body = wordXmlText(xml)
                if (body.isNotBlank()) {
                    if (name != "word/document.xml") out.appendLine("[${name.substringAfter("word/").substringBeforeLast('.')}]")
                    out.appendLine(body)
                }
            }
        }
        return LocalDocumentText("Word DOCX", out.value(), out.truncated)
    }

    private fun extractXlsx(file: File, maxChars: Int): LocalDocumentText {
        val out = BoundedText(maxChars)
        withSafeZip(file) { zip ->
            val shared = zip.readEntryText("xl/sharedStrings.xml")
                ?.let(::xlsxSharedStrings)
                .orEmpty()
            val sheets = zip.entries().asSequence()
                .map { it.name }
                .filter { it.matches(Regex("""xl/worksheets/sheet\d+\.xml""")) }
                .sortedWith(compareBy { sheetNumber(it) })
                .take(200)
                .toList()
            sheets.forEachIndexed { index, name ->
                val xml = zip.readEntryText(name) ?: return@forEachIndexed
                out.appendLine("[工作表 ${index + 1}]")
                xlsxCells(xml, shared).forEach(out::appendLine)
                if (out.truncated) return@forEachIndexed
            }
        }
        return LocalDocumentText("Excel XLSX", out.value(), out.truncated)
    }

    private fun extractPptx(file: File, maxChars: Int): LocalDocumentText {
        val out = BoundedText(maxChars)
        withSafeZip(file) { zip ->
            val slides = zip.entries().asSequence()
                .map { it.name }
                .filter { it.matches(Regex("""ppt/slides/slide\d+\.xml""")) }
                .sortedWith(compareBy { slideNumber(it) })
                .take(300)
                .toList()
            slides.forEachIndexed { index, name ->
                val xml = zip.readEntryText(name) ?: return@forEachIndexed
                val text = officeTextRuns(xml, "a:t", "a:p")
                if (text.isNotBlank()) {
                    out.appendLine("[幻灯片 ${index + 1}]")
                    out.appendLine(text)
                }
            }
        }
        return LocalDocumentText("PowerPoint PPTX", out.value(), out.truncated)
    }

    private fun extractOpenDocument(file: File, extension: String, maxChars: Int): LocalDocumentText {
        val out = BoundedText(maxChars)
        withSafeZip(file) { zip ->
            val xml = zip.readEntryText("content.xml").orEmpty()
            val normalized = xml
                .replace(Regex("""<text:tab\b[^>]*/>""", RegexOption.IGNORE_CASE), "\t")
                .replace(Regex("""<text:line-break\b[^>]*/>""", RegexOption.IGNORE_CASE), "\n")
                .replace(
                    Regex("""</(?:text:p|text:h|table:table-row|draw:page)>""", RegexOption.IGNORE_CASE),
                    "\n",
                )
            out.appendLine(stripXmlTags(normalized))
        }
        val label = when (extension) {
            "odt" -> "OpenDocument ODT"
            "ods" -> "OpenDocument ODS"
            else -> "OpenDocument ODP"
        }
        return LocalDocumentText(label, out.value(), out.truncated)
    }

    private fun extractEpub(file: File, maxChars: Int): LocalDocumentText {
        val out = BoundedText(maxChars)
        withSafeZip(file) { zip ->
            val pages = zip.entries().asSequence()
                .map { it.name }
                .filter {
                    val ext = it.substringAfterLast('.', "").lowercase()
                    ext in setOf("xhtml", "html", "htm")
                }
                .filterNot { it.contains("nav", ignoreCase = true) && it.count { ch -> ch == '/' } <= 2 }
                .sorted()
                .take(MAX_ARCHIVE_ENTRIES)
                .toList()
            pages.forEach { name ->
                val html = zip.readEntryText(name) ?: return@forEach
                val text = htmlToText(html)
                if (text.isNotBlank()) {
                    out.appendLine("[${name.substringAfterLast('/')}]")
                    out.appendLine(text)
                }
            }
        }
        return LocalDocumentText("EPUB", out.value(), out.truncated)
    }

    private fun extractRtf(file: File, maxChars: Int): LocalDocumentText {
        val sourceBytes = readPrefix(file, MAX_ZIP_ENTRY_BYTES)
        val source = decodeText(sourceBytes)
        val out = StringBuilder()
        var index = 0
        var skipGroupDepth = 0
        var depth = 0
        while (index < source.length && out.length < maxChars + 1) {
            when (val ch = source[index]) {
                '{' -> {
                    depth += 1
                    index += 1
                    if (source.startsWith("\\*", index)) skipGroupDepth = depth
                }
                '}' -> {
                    if (skipGroupDepth == depth) skipGroupDepth = 0
                    depth = (depth - 1).coerceAtLeast(0)
                    index += 1
                }
                '\\' -> {
                    if (index + 1 >= source.length) break
                    val next = source[index + 1]
                    when {
                        next in setOf('\\', '{', '}') -> {
                            if (skipGroupDepth == 0) out.append(next)
                            index += 2
                        }
                        next == '\'' && index + 3 < source.length -> {
                            val value = source.substring(index + 2, index + 4).toIntOrNull(16)
                            if (value != null && skipGroupDepth == 0) {
                                out.append(byteArrayOf(value.toByte()).toString(Charset.forName("windows-1252")))
                            }
                            index += 4
                        }
                        else -> {
                            val match = Regex("""\\([a-zA-Z]+)(-?\d+)? ?""").find(source, index)
                            if (match != null && match.range.first == index) {
                                val word = match.groupValues[1]
                                val number = match.groupValues.getOrNull(2)?.toIntOrNull()
                                if (skipGroupDepth == 0) {
                                    when (word) {
                                        "par", "line" -> out.append('\n')
                                        "tab" -> out.append('\t')
                                        "u" -> if (number != null) out.append(number.toChar())
                                    }
                                }
                                index = match.range.last + 1
                                if (word == "u" && index < source.length && source[index] == '?') index += 1
                            } else {
                                index += 2
                            }
                        }
                    }
                }
                else -> {
                    if (skipGroupDepth == 0 && ch.code >= 0x20) out.append(ch)
                    index += 1
                }
            }
        }
        val result = bounded("RTF", out.toString(), maxChars)
        return result.copy(truncated = result.truncated || file.length() > sourceBytes.size)
    }

    private fun extractPdfBestEffort(file: File, maxChars: Int): LocalDocumentText {
        val raw = readBounded(file, 20 * 1024 * 1024)
        val source = String(raw, StandardCharsets.ISO_8859_1)
        val out = BoundedText(maxChars)
        var cursor = 0
        var inflatedTotal = 0
        while (cursor < source.length && !out.truncated) {
            val streamAt = source.indexOf("stream", cursor)
            if (streamAt < 0) break
            var dataStart = streamAt + 6
            if (dataStart < source.length && source[dataStart] == '\r') dataStart += 1
            if (dataStart < source.length && source[dataStart] == '\n') dataStart += 1
            val endAt = source.indexOf("endstream", dataStart)
            if (endAt < 0) break
            val dictionary = source.substring((streamAt - 800).coerceAtLeast(0), streamAt)
            val encoded = raw.copyOfRange(dataStart.coerceAtMost(raw.size), endAt.coerceAtMost(raw.size))
            val decoded = if (dictionary.contains("/FlateDecode")) {
                runCatching {
                    InflaterInputStream(ByteArrayInputStream(encoded)).use { input ->
                        readInputBounded(input, MAX_ZIP_ENTRY_BYTES)
                    }
                }.getOrNull()
            } else {
                encoded.takeIf { it.size <= MAX_ZIP_ENTRY_BYTES }
            }
            if (decoded != null) {
                inflatedTotal += decoded.size
                if (inflatedTotal > MAX_ZIP_TOTAL_BYTES) break
                extractPdfTextOperators(decoded).forEach(out::appendLine)
            }
            cursor = endAt + 9
        }
        val cleaned = out.value()
            .lineSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .joinToString("\n")
        return LocalDocumentText(
            formatLabel = "PDF",
            text = cleaned.take(maxChars),
            truncated = out.truncated || cleaned.length > maxChars,
            parsed = cleaned.isNotBlank(),
        )
    }

    private fun extractZip(file: File, maxChars: Int): LocalDocumentText {
        val out = BoundedText(maxChars)
        withSafeZip(file) { zip ->
            val entries = zip.entries().asSequence()
                .filterNot { it.isDirectory }
                .take(MAX_ARCHIVE_ENTRIES)
                .toList()
            out.appendLine("压缩包文件列表（最多显示 $MAX_ARCHIVE_ENTRIES 项）：")
            entries.forEach { out.appendLine("- ${it.name}（${it.size.coerceAtLeast(0)} B）") }
            entries.filter { entry ->
                entry.name.substringAfterLast('.', "").lowercase() in plainTextExtensions &&
                    entry.size in 0L..MAX_ZIP_ENTRY_BYTES.toLong()
            }.take(12).forEach { entry ->
                val text = zip.readEntryText(entry.name) ?: return@forEach
                out.appendLine()
                out.appendLine("[${entry.name}]")
                out.appendLine(
                    if (entry.name.substringAfterLast('.', "").lowercase() in setOf("html", "htm", "xhtml")) {
                        htmlToText(text)
                    } else {
                        text
                    },
                )
            }
        }
        return LocalDocumentText("ZIP", out.value(), out.truncated)
    }

    private fun extractGzip(file: File, displayName: String, maxChars: Int): LocalDocumentText {
        val bytes = GZIPInputStream(file.inputStream()).use { readInputBounded(it, MAX_ZIP_ENTRY_BYTES) }
        val text = decodeText(bytes)
        return bounded("GZIP ${displayName.substringBeforeLast('.')}", text, maxChars)
    }

    private fun extractTar(file: File, maxChars: Int): LocalDocumentText {
        val bytes = readBounded(file, 20 * 1024 * 1024)
        val out = BoundedText(maxChars)
        var offset = 0
        var entries = 0
        while (offset + 512 <= bytes.size && entries < MAX_ARCHIVE_ENTRIES && !out.truncated) {
            val header = bytes.copyOfRange(offset, offset + 512)
            if (header.all { it == 0.toByte() }) break
            val name = asciiZeroTerminated(header, 0, 100)
            val sizeText = asciiZeroTerminated(header, 124, 12).trim()
            val size = sizeText.toLongOrNull(8) ?: 0L
            out.appendLine("- $name（$size B）")
            val dataStart = offset + 512
            val dataEnd = (dataStart + size).coerceAtMost(bytes.size.toLong()).toInt()
            val ext = name.substringAfterLast('.', "").lowercase()
            if (ext in plainTextExtensions && size in 1L..MAX_ZIP_ENTRY_BYTES.toLong() && dataEnd > dataStart) {
                out.appendLine("[$name]")
                out.appendLine(decodeText(bytes.copyOfRange(dataStart, dataEnd)))
            }
            val padded = ((size + 511L) / 512L) * 512L
            offset = (dataStart.toLong() + padded).coerceAtMost(bytes.size.toLong()).toInt()
            entries += 1
        }
        return LocalDocumentText("TAR", out.value(), out.truncated)
    }

    private fun extractLegacyOffice(file: File, extension: String, maxChars: Int): LocalDocumentText {
        val bytes = readBounded(file, 20 * 1024 * 1024)
        val candidates = LinkedHashSet<String>()
        collectAsciiRuns(bytes, candidates)
        collectUtf16LeRuns(bytes, candidates)
        val text = candidates
            .asSequence()
            .map(String::trim)
            .filter { it.length >= 3 }
            .filter(::looksUsefulLegacyText)
            .take(4_000)
            .joinToString("\n")
        val label = when (extension) {
            "doc" -> "Word DOC（兼容提取）"
            "xls" -> "Excel XLS（兼容提取）"
            else -> "PowerPoint PPT（兼容提取）"
        }
        return bounded(label, text, maxChars).copy(parsed = text.isNotBlank())
    }

    private fun withSafeZip(file: File, block: (SafeZip) -> Unit) {
        ZipFile(file).use { raw -> block(SafeZip(raw)) }
    }

    private class SafeZip(private val zip: ZipFile) {
        private var totalInflated = 0

        fun entries() = zip.entries()

        fun readEntryText(name: String): String? {
            val entry = zip.getEntry(name) ?: return null
            if (entry.isDirectory || entry.size > MAX_ZIP_ENTRY_BYTES) return null
            val bytes = zip.getInputStream(entry).use { readInputBounded(it, MAX_ZIP_ENTRY_BYTES) }
            totalInflated += bytes.size
            require(totalInflated <= MAX_ZIP_TOTAL_BYTES) { "压缩文档解压内容超过安全上限" }
            return decodeText(bytes)
        }
    }

    private fun xlsxSharedStrings(xml: String): List<String> =
        Regex("""<si\b[^>]*>(.*?)</si>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            .findAll(xml)
            .map { match ->
                Regex("""<t\b[^>]*>(.*?)</t>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
                    .findAll(match.groupValues[1])
                    .joinToString("") { decodeXmlEntities(stripXmlTags(it.groupValues[1])) }
            }
            .toList()

    private fun xlsxCells(xml: String, shared: List<String>): Sequence<String> =
        Regex("""<c\b([^>]*)>(.*?)</c>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            .findAll(xml)
            .mapNotNull { match ->
                val attrs = match.groupValues[1]
                val body = match.groupValues[2]
                val ref = attr(attrs, "r").orEmpty()
                val type = attr(attrs, "t").orEmpty()
                val formula = Regex("""<f\b[^>]*>(.*?)</f>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
                    .find(body)?.groupValues?.getOrNull(1)?.let(::decodeXmlEntities)
                val rawValue = Regex("""<v\b[^>]*>(.*?)</v>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
                    .find(body)?.groupValues?.getOrNull(1)
                val inline = Regex("""<t\b[^>]*>(.*?)</t>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
                    .findAll(body).joinToString("") { decodeXmlEntities(it.groupValues[1]) }
                val value = when (type) {
                    "s" -> rawValue?.trim()?.toIntOrNull()?.let(shared::getOrNull).orEmpty()
                    "inlineStr", "str" -> inline.ifBlank { rawValue.orEmpty() }
                    "b" -> when (rawValue?.trim()) { "1" -> "TRUE"; "0" -> "FALSE"; else -> rawValue.orEmpty() }
                    else -> rawValue.orEmpty()
                }.trim()
                if (value.isBlank() && formula.isNullOrBlank()) null
                else buildString {
                    if (ref.isNotBlank()) append(ref).append(": ")
                    if (!formula.isNullOrBlank()) append("=").append(formula).append(" → ")
                    append(value)
                }
            }

    private fun wordXmlText(xml: String): String = officeTextRuns(xml, "w:t", "w:p")

    private fun officeTextRuns(xml: String, textTag: String, paragraphTag: String): String {
        val withBreaks = xml
            .replace(
                Regex("""</${Regex.escape(paragraphTag)}>""", RegexOption.IGNORE_CASE),
                "\n",
            )
            .replace(Regex("""<(?:w:tab|w:br|a:br)\b[^>]*/>""", RegexOption.IGNORE_CASE), "\n")
        val token = Regex(
            """<${Regex.escape(textTag)}\b[^>]*>(.*?)</${Regex.escape(textTag)}>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        )
        val out = StringBuilder()
        var cursor = 0
        token.findAll(withBreaks).forEach { match ->
            val gap = withBreaks.substring(cursor, match.range.first)
            if ('\n' in gap && out.isNotEmpty() && out.last() != '\n') out.append('\n')
            out.append(decodeXmlEntities(match.groupValues[1]))
            cursor = match.range.last + 1
        }
        return out.toString().replace(Regex("""\n{3,}"""), "\n\n").trim()
    }

    private fun htmlToText(source: String): String {
        val withoutNoise = source
            .replace(Regex("""<script\b[^>]*>.*?</script>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), " ")
            .replace(Regex("""<style\b[^>]*>.*?</style>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), " ")
            .replace(Regex("""</(?:p|div|section|article|h[1-6]|li|tr|br)>""", RegexOption.IGNORE_CASE), "\n")
        return stripXmlTags(withoutNoise)
            .replace(Regex("""[ \t]+"""), " ")
            .replace(Regex("""\n\s*\n\s*\n+"""), "\n\n")
            .trim()
    }

    private fun stripXmlTags(value: String): String =
        decodeXmlEntities(value.replace(Regex("""<[^>]+>"""), " "))

    private fun decodeXmlEntities(value: String): String {
        var text = value
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
        text = Regex("""&#(\d+);""").replace(text) { match ->
            match.groupValues[1].toIntOrNull()?.let { code -> runCatching { code.toChar().toString() }.getOrNull() }
                ?: match.value
        }
        text = Regex("""&#x([0-9a-fA-F]+);""").replace(text) { match ->
            match.groupValues[1].toIntOrNull(16)?.let { code -> runCatching { code.toChar().toString() }.getOrNull() }
                ?: match.value
        }
        return text
    }

    private fun extractPdfTextOperators(bytes: ByteArray): List<String> {
        val source = String(bytes, StandardCharsets.ISO_8859_1)
        val values = mutableListOf<String>()
        Regex("""\((?:\\.|[^\\)])*\)\s*Tj""").findAll(source).forEach { match ->
            decodePdfLiteral(match.value.substringBeforeLast(')') + ")")
                .takeIf(String::isNotBlank)?.let(values::add)
        }
        Regex("""\[(.*?)]\s*TJ""", RegexOption.DOT_MATCHES_ALL).findAll(source).forEach { array ->
            val chunk = buildString {
                Regex("""\((?:\\.|[^\\)])*\)|<[0-9A-Fa-f\s]+>""")
                    .findAll(array.groupValues[1])
                    .forEach { token ->
                        val decoded = if (token.value.startsWith("(")) {
                            decodePdfLiteral(token.value)
                        } else {
                            decodePdfHex(token.value)
                        }
                        if (decoded.isNotBlank()) append(decoded)
                    }
            }
            if (chunk.isNotBlank()) values += chunk
        }
        return values
    }

    private fun decodePdfLiteral(token: String): String {
        if (token.length < 2) return ""
        val content = token.substring(1, token.length - 1)
        val out = ByteArrayOutputStream()
        var index = 0
        while (index < content.length) {
            val ch = content[index]
            if (ch != '\\') {
                out.write(ch.code and 0xff)
                index += 1
                continue
            }
            if (index + 1 >= content.length) break
            val next = content[index + 1]
            when (next) {
                'n' -> out.write('\n'.code)
                'r' -> out.write('\r'.code)
                't' -> out.write('\t'.code)
                'b' -> out.write('\b'.code)
                'f' -> out.write(12)
                '\\', '(', ')' -> out.write(next.code)
                '\r', '\n' -> Unit
                in '0'..'7' -> {
                    var end = index + 1
                    while (end < content.length && end < index + 4 && content[end] in '0'..'7') end += 1
                    out.write(content.substring(index + 1, end).toInt(8))
                    index = end - 1
                }
                else -> out.write(next.code)
            }
            index += 2
        }
        return decodePdfBytes(out.toByteArray())
    }

    private fun decodePdfHex(token: String): String {
        var hex = token.removePrefix("<").removeSuffix(">").filterNot(Char::isWhitespace)
        if (hex.length % 2 != 0) hex += "0"
        val bytes = ByteArray(hex.length / 2)
        for (index in bytes.indices) {
            bytes[index] = hex.substring(index * 2, index * 2 + 2).toIntOrNull(16)?.toByte() ?: 0
        }
        return decodePdfBytes(bytes)
    }

    private fun decodePdfBytes(bytes: ByteArray): String {
        if (bytes.size >= 2 && bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte()) {
            return String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16BE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte()) {
            return String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16LE)
        }
        val utf8 = String(bytes, StandardCharsets.UTF_8)
        val replacementRatio = utf8.count { it == '\uFFFD' }.toDouble() / utf8.length.coerceAtLeast(1)
        return if (replacementRatio < 0.05) utf8 else String(bytes, Charset.forName("windows-1252"))
    }

    private fun collectAsciiRuns(bytes: ByteArray, target: MutableSet<String>) {
        val out = StringBuilder()
        fun flush() {
            if (out.length >= 4) target += out.toString()
            out.setLength(0)
        }
        bytes.forEach { byte ->
            val code = byte.toInt() and 0xff
            if (code in 0x20..0x7e || code in 0xa0..0xff) out.append(code.toChar()) else flush()
        }
        flush()
    }

    private fun collectUtf16LeRuns(bytes: ByteArray, target: MutableSet<String>) {
        var index = 0
        val out = StringBuilder()
        fun flush() {
            if (out.length >= 4) target += out.toString()
            out.setLength(0)
        }
        while (index + 1 < bytes.size) {
            val lo = bytes[index].toInt() and 0xff
            val hi = bytes[index + 1].toInt() and 0xff
            val code = lo or (hi shl 8)
            val ch = code.toChar()
            if (code in 0x20..0xfffd && !Character.isISOControl(ch)) out.append(ch) else flush()
            index += 2
        }
        flush()
    }

    private fun looksUsefulLegacyText(value: String): Boolean {
        if (value.length > 4_000) return false
        val useful = value.count { it.isLetterOrDigit() || it.isWhitespace() || it.code >= 0x3000 }
        return useful.toDouble() / value.length.coerceAtLeast(1) >= 0.55
    }

    private fun bounded(label: String, value: String, maxChars: Int): LocalDocumentText {
        val clean = value.trim()
        return LocalDocumentText(
            formatLabel = label,
            text = clean.take(maxChars),
            truncated = clean.length > maxChars,
            parsed = clean.isNotBlank(),
        )
    }

    private class BoundedText(private val maxChars: Int) {
        private val out = StringBuilder()
        var truncated: Boolean = false
            private set

        fun appendLine(value: String = "") {
            if (truncated) return
            val normalized = value.trimEnd()
            val remaining = maxChars - out.length
            if (remaining <= 0) {
                truncated = true
                return
            }
            val addition = if (out.isEmpty()) normalized else "\n$normalized"
            if (addition.length <= remaining) {
                out.append(addition)
            } else {
                out.append(addition.take(remaining))
                truncated = true
            }
        }

        fun value(): String = out.toString().trim()
    }

    private fun readBounded(file: File, maxBytes: Int): ByteArray =
        file.inputStream().use { readInputBounded(it, maxBytes) }

    private fun readPrefix(file: File, maxBytes: Int): ByteArray =
        file.inputStream().use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (out.size() < maxBytes) {
                val read = input.read(buffer, 0, minOf(buffer.size, maxBytes - out.size()))
                if (read < 0) break
                out.write(buffer, 0, read)
            }
            out.toByteArray()
        }

    private fun readInputBounded(input: java.io.InputStream, maxBytes: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
            require(out.size() <= maxBytes) { "文档展开内容超过安全上限" }
        }
        return out.toByteArray()
    }

    private fun decodeText(bytes: ByteArray): String = when {
        bytes.size >= 4 &&
            bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte() &&
            bytes[2] == 0x00.toByte() && bytes[3] == 0x00.toByte() ->
            String(bytes, 4, bytes.size - 4, Charset.forName("UTF-32LE"))
        bytes.size >= 4 &&
            bytes[0] == 0x00.toByte() && bytes[1] == 0x00.toByte() &&
            bytes[2] == 0xfe.toByte() && bytes[3] == 0xff.toByte() ->
            String(bytes, 4, bytes.size - 4, Charset.forName("UTF-32BE"))
        bytes.size >= 3 &&
            bytes[0] == 0xef.toByte() && bytes[1] == 0xbb.toByte() && bytes[2] == 0xbf.toByte() ->
            String(bytes, 3, bytes.size - 3, StandardCharsets.UTF_8)
        bytes.size >= 2 && bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte() ->
            String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16LE)
        bytes.size >= 2 && bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte() ->
            String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16BE)
        else -> decodeUnknownTextEncoding(bytes)
    }

    private fun decodeUnknownTextEncoding(bytes: ByteArray): String {
        val utf8 = String(bytes, StandardCharsets.UTF_8)
        val utf8Replacement = utf8.count { it == '\uFFFD' }
        if (utf8Replacement == 0 || utf8Replacement.toDouble() / utf8.length.coerceAtLeast(1) <= 0.01) {
            return utf8
        }
        val gb18030 = runCatching { String(bytes, Charset.forName("GB18030")) }.getOrNull()
        if (gb18030 != null) {
            val gbReplacement = gb18030.count { it == '\uFFFD' }
            if (gbReplacement < utf8Replacement) return gb18030
        }
        return String(bytes, Charset.forName("windows-1252"))
    }

    private fun attr(attributes: String, name: String): String? =
        Regex("""(?:^|\s)${Regex.escape(name)}="([^"]*)"""")
            .find(attributes)?.groupValues?.getOrNull(1)
            ?.let(::decodeXmlEntities)

    private fun sheetNumber(name: String): Int =
        Regex("""sheet(\d+)\.xml""").find(name)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: Int.MAX_VALUE

    private fun slideNumber(name: String): Int =
        Regex("""slide(\d+)\.xml""").find(name)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: Int.MAX_VALUE

    private fun asciiZeroTerminated(bytes: ByteArray, start: Int, length: Int): String {
        val end = (start until (start + length).coerceAtMost(bytes.size))
            .firstOrNull { bytes[it] == 0.toByte() }
            ?: (start + length).coerceAtMost(bytes.size)
        return String(bytes, start, (end - start).coerceAtLeast(0), StandardCharsets.US_ASCII)
    }

    private val WORD_OPEN_XML_MEDIA_TYPES = setOf(
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.ms-word.document.macroenabled.12",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.template",
    )

    private val EXCEL_OPEN_XML_MEDIA_TYPES = setOf(
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "application/vnd.ms-excel.sheet.macroenabled.12",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.template",
    )

    private val POWERPOINT_OPEN_XML_MEDIA_TYPES = setOf(
        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "application/vnd.ms-powerpoint.presentation.macroenabled.12",
        "application/vnd.openxmlformats-officedocument.presentationml.slideshow",
        "application/vnd.openxmlformats-officedocument.presentationml.template",
    )

    private val LEGACY_OFFICE_MEDIA_TYPES = setOf(
        "application/msword",
        "application/vnd.ms-excel",
        "application/vnd.ms-powerpoint",
    )

}

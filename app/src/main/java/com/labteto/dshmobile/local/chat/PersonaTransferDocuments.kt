package com.labteto.dshmobile.local.chat

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal const val PERSONA_WORD_MIME =
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
internal const val MAX_PERSONA_TRANSFER_BYTES = 16 * 1024 * 1024

internal enum class PersonaTransferFormat(
    val extension: String,
    val mimeType: String,
) {
    JSON("json", "application/json"),
    MARKDOWN("md", "text/markdown"),
    WORD("docx", PERSONA_WORD_MIME),
}

internal data class PersonaTransferDocument(
    val format: PersonaTransferFormat,
    val bytes: ByteArray,
)

@Serializable
internal data class PersonaTransferMemorySummary(
    val storyId: String,
    val storyTitle: String = "",
    val plotSummary: String = "",
    val continuitySummary: String = "",
    val relationshipState: String = "",
    val mood: String = "",
    val sharedMoments: List<String> = emptyList(),
    val unresolvedThreads: List<String> = emptyList(),
    val currentFocus: String = "",
    val recentImpression: String = "",
    val activeGoal: String = "",
)

@Serializable
internal data class PersonaArchiveEnvelope(
    val schema: Int = 2,
    val source: String = "神言神语",
    val entry: PersonaGalleryEntry,
    val memorySummaries: List<PersonaTransferMemorySummary> = emptyList(),
)

internal object PersonaTransferDocuments {
    private const val MARKDOWN_PAYLOAD_BEGIN = "<!-- SHENYU_PERSONA_ARCHIVE_V2_BASE64"
    private const val MARKDOWN_PAYLOAD_END = "SHENYU_PERSONA_ARCHIVE_V2_BASE64_END -->"
    private const val WORD_PAYLOAD_PREFIX = "SHENYU_PERSONA_ARCHIVE_V2_BASE64:"
    private const val CUSTOM_XML_ENTRY = "customXml/persona-transfer.xml"

    fun encode(
        json: Json,
        entry: PersonaGalleryEntry,
        format: PersonaTransferFormat,
    ): PersonaTransferDocument {
        val portable = portableEntry(entry)
        val archive = PersonaArchiveEnvelope(
            entry = portable,
            memorySummaries = portable.stories.map { story ->
                memorySummary(story, portable.persona.name)
            },
        )
        val canonicalJson = json.encodeToString(PersonaArchiveEnvelope.serializer(), archive)
        val bytes = when (format) {
            PersonaTransferFormat.JSON -> canonicalJson.toByteArray(StandardCharsets.UTF_8)
            PersonaTransferFormat.MARKDOWN ->
                renderMarkdown(archive, canonicalJson).toByteArray(StandardCharsets.UTF_8)
            PersonaTransferFormat.WORD -> renderDocx(archive, canonicalJson)
        }
        require(bytes.size <= MAX_PERSONA_TRANSFER_BYTES) {
            "人物导出内容超过 16 MB，请先精简过长的历史记录"
        }
        return PersonaTransferDocument(format = format, bytes = bytes)
    }

    fun decodeToCanonicalJson(
        bytes: ByteArray,
        fileName: String? = null,
        mimeType: String? = null,
    ): String {
        require(bytes.isNotEmpty() && bytes.size <= MAX_PERSONA_TRANSFER_BYTES) {
            "人物导入文件为空或超过 16 MB"
        }
        val looksLikeDocx =
            mimeType.equals(PERSONA_WORD_MIME, ignoreCase = true) ||
                fileName.orEmpty().endsWith(".docx", ignoreCase = true) ||
                (bytes.size >= 4 &&
                    bytes[0] == 0x50.toByte() &&
                    bytes[1] == 0x4b.toByte() &&
                    bytes[2] == 0x03.toByte() &&
                    bytes[3] == 0x04.toByte())
        if (looksLikeDocx) return extractDocxPayload(bytes)

        val text = String(bytes, StandardCharsets.UTF_8).trim()
        require(text.isNotEmpty()) { "人物导入文件为空" }
        if (text.startsWith("{")) return text
        return extractMarkdownPayload(text)
    }

    fun decodeArchive(json: Json, canonicalJson: String): PersonaArchiveEnvelope =
        json.decodeFromString(PersonaArchiveEnvelope.serializer(), canonicalJson).also {
            require(it.schema == 2) { "暂不支持这个人物迁移版本" }
        }

    private fun portableEntry(entry: PersonaGalleryEntry): PersonaGalleryEntry =
        entry.copy(
            portraitPath = "",
            stories = entry.stories.map { story ->
                story.copy(
                    sourceSessionIds = emptyList(),
                    excludedMessageKeys = emptyList(),
                    history = story.history.filter { it.role == "user" || it.role == "assistant" },
                )
            },
            storyNotes = "",
            history = emptyList(),
            chatState = ChatCharacterState(),
            sourceSessionId = "",
        )

    private fun memorySummary(
        story: PersonaGalleryStory,
        personaName: String,
    ): PersonaTransferMemorySummary =
        PersonaTransferMemorySummary(
            storyId = story.id,
            storyTitle = story.title,
            plotSummary = story.notes,
            continuitySummary = story.context(personaName),
            relationshipState = story.chatState.relationshipState,
            mood = story.chatState.mood,
            sharedMoments = story.chatState.dynamics.sharedMoments,
            unresolvedThreads = story.chatState.unresolvedThreads,
            currentFocus = story.chatState.currentFocus,
            recentImpression = story.chatState.recentImpression,
            activeGoal = story.chatState.activeGoal,
        )

    private fun renderMarkdown(
        archive: PersonaArchiveEnvelope,
        canonicalJson: String,
    ): String {
        val body = buildReadableLines(archive).joinToString("\n") { line ->
            when (line.level) {
                1 -> "# ${line.text}"
                2 -> "## ${line.text}"
                3 -> "### ${line.text}"
                else -> line.text
            }
        }
        val encoded = Base64.getEncoder().encodeToString(
            canonicalJson.toByteArray(StandardCharsets.UTF_8),
        )
        return buildString {
            appendLine(body.trimEnd())
            appendLine()
            appendLine("---")
            appendLine()
            appendLine("<!-- 以下机器数据用于重新导入 777，请勿删除或修改。 -->")
            appendLine(MARKDOWN_PAYLOAD_BEGIN)
            encoded.chunked(120).forEach(::appendLine)
            appendLine(MARKDOWN_PAYLOAD_END)
        }
    }

    private fun extractMarkdownPayload(text: String): String {
        val start = text.indexOf(MARKDOWN_PAYLOAD_BEGIN)
        require(start >= 0) { "Markdown 中未找到 777 人物迁移数据" }
        val payloadStart = start + MARKDOWN_PAYLOAD_BEGIN.length
        val end = text.indexOf(MARKDOWN_PAYLOAD_END, payloadStart)
        require(end > payloadStart) { "Markdown 人物迁移数据不完整" }
        val encoded = text.substring(payloadStart, end).filterNot(Char::isWhitespace)
        return runCatching {
            String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8)
        }.getOrElse { throw IllegalArgumentException("Markdown 人物迁移数据损坏", it) }
    }

    private data class DocLine(
        val text: String,
        val level: Int = 0,
    )

    private fun buildReadableLines(archive: PersonaArchiveEnvelope): List<DocLine> {
        val entry = archive.entry
        val persona = entry.persona
        return buildList {
            add(DocLine("人物档案：${persona.name}", 1))
            add(DocLine("神言神语人物迁移文档 · 版本 2"))
            add(DocLine("包含人物设定、记忆摘要与完整已归档对话记录。"))

            add(DocLine("人物设定", 2))
            addField("姓名", persona.name)
            addField("身份", persona.identity)
            addField("背景", persona.background)
            addField("性格", persona.personality)
            addField("说话风格", persona.speechStyle)
            addField("基础关系", persona.relationship)
            addField("世界设定", persona.worldSetting)
            addField("作品/世界来源", persona.franchise)
            addField("时间线位置", persona.timelinePosition)
            addList("核心动机", persona.coreMotivations)
            addList("价值优先级", persona.valuePriorities)
            addList("稳定行为", persona.behaviorPatterns)
            addList("内在矛盾", persona.internalContradictions)
            addList("知识边界", persona.knowledgeBoundary)
            addList("硬约束", persona.hardConstraints)
            addList("参考对白", persona.exampleDialogues)
            addList("禁用表达", persona.bannedPhrases)
            addList("标志性表达", persona.signaturePhrases)
            addList("用户纠正", persona.corrections)
            if (persona.loreEntries.isNotEmpty()) {
                add(DocLine("世界书", 3))
                persona.loreEntries.forEachIndexed { index, lore ->
                    add(DocLine("${index + 1}. ${lore.title.ifBlank { "条目" }}"))
                    lore.content.takeIf(String::isNotBlank)?.let { add(DocLine(it)) }
                    val keywords = (lore.keywords + lore.secondaryKeywords)
                        .map(String::trim)
                        .filter(String::isNotBlank)
                        .distinct()
                    if (keywords.isNotEmpty()) add(DocLine("关键词：${keywords.joinToString("、")}"))
                }
            }

            add(DocLine("记忆摘要", 2))
            if (archive.memorySummaries.isEmpty()) {
                add(DocLine("暂无已归档记忆摘要。"))
            } else {
                archive.memorySummaries.forEachIndexed { index, summary ->
                    add(DocLine(
                        "故事 ${index + 1}：${summary.storyTitle.ifBlank { "未命名故事" }}",
                        3,
                    ))
                    addField("剧情提要", summary.plotSummary)
                    addField("连续性摘要", summary.continuitySummary)
                    addField("关系状态", summary.relationshipState)
                    addField("当前情绪", summary.mood)
                    addField("当前关注", summary.currentFocus)
                    addField("近期印象", summary.recentImpression)
                    addField("当前目标", summary.activeGoal)
                    addList("共同经历", summary.sharedMoments)
                    addList("未完线索", summary.unresolvedThreads)
                }
            }

            add(DocLine("对话记录", 2))
            if (entry.stories.isEmpty()) {
                add(DocLine("暂无已归档对话。"))
            } else {
                entry.stories.forEachIndexed { index, story ->
                    add(DocLine(
                        "故事 ${index + 1}：${story.title.ifBlank { "未命名故事" }}",
                        3,
                    ))
                    story.notes.takeIf(String::isNotBlank)?.let { add(DocLine("剧情提要：$it")) }
                    if (story.history.isEmpty()) {
                        add(DocLine("暂无对话记录。"))
                    } else {
                        story.history.forEach { message ->
                            val speaker = when (message.role) {
                                "user" -> "用户"
                                "assistant" -> message.speakerName
                                    ?.takeIf(String::isNotBlank)
                                    ?: persona.name
                                else -> message.role
                            }
                            val messageText = message.content.trim()
                            if (messageText.isNotEmpty()) {
                                add(DocLine("$speaker：$messageText"))
                            }
                        }
                    }
                }
            }
        }
    }

    private fun MutableList<DocLine>.addField(label: String, value: String) {
        value.trim().takeIf(String::isNotBlank)?.let { add(DocLine("$label：$it")) }
    }

    private fun MutableList<DocLine>.addList(label: String, values: List<String>) {
        val clean = values.map(String::trim).filter(String::isNotBlank)
        if (clean.isEmpty()) return
        add(DocLine("$label："))
        clean.forEach { add(DocLine("• $it")) }
    }

    private fun renderDocx(
        archive: PersonaArchiveEnvelope,
        canonicalJson: String,
    ): ByteArray {
        val payload = Base64.getEncoder().encodeToString(
            canonicalJson.toByteArray(StandardCharsets.UTF_8),
        )
        val lines = buildReadableLines(archive)
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putUtf8("[Content_Types].xml", contentTypesXml())
            zip.putUtf8("_rels/.rels", rootRelationshipsXml())
            zip.putUtf8("word/_rels/document.xml.rels", documentRelationshipsXml())
            zip.putUtf8("word/styles.xml", stylesXml())
            zip.putUtf8("word/document.xml", documentXml(lines, payload))
            zip.putUtf8("docProps/core.xml", corePropertiesXml(archive.entry.persona.name))
            zip.putUtf8("docProps/app.xml", appPropertiesXml())
            zip.putUtf8(
                CUSTOM_XML_ENTRY,
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
                    "<personaTransfer xmlns=\"urn:shenyu:persona-transfer:v2\">" +
                    "<payload>$payload</payload></personaTransfer>",
            )
        }
        return out.toByteArray()
    }

    private fun extractDocxPayload(bytes: ByteArray): String {
        var customPayload: String? = null
        var hiddenPayload: String? = null
        var totalInflated = 0
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                if (entry.name != CUSTOM_XML_ENTRY && entry.name != "word/document.xml") continue
                val data = readZipEntryLimited(zip)
                totalInflated += data.size
                require(totalInflated <= MAX_PERSONA_TRANSFER_BYTES * 2) {
                    "Word 人物文档解压内容过大"
                }
                val text = String(data, StandardCharsets.UTF_8)
                if (entry.name == CUSTOM_XML_ENTRY) {
                    customPayload = Regex(
                        """<payload>([^<]+)</payload>""",
                        setOf(RegexOption.DOT_MATCHES_ALL),
                    ).find(text)?.groupValues?.getOrNull(1)?.filterNot(Char::isWhitespace)
                } else {
                    val plain = Regex("""<w:t(?:\s+[^>]*)?>(.*?)</w:t>""")
                        .findAll(text)
                        .joinToString("") { xmlUnescape(it.groupValues[1]) }
                    hiddenPayload = plain.substringAfter(WORD_PAYLOAD_PREFIX, "")
                        .takeIf(String::isNotBlank)
                        ?.filterNot(Char::isWhitespace)
                }
            }
        }
        val encoded = customPayload ?: hiddenPayload
        require(!encoded.isNullOrBlank()) { "Word 中未找到 777 人物迁移数据" }
        return runCatching {
            String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8)
        }.getOrElse { throw IllegalArgumentException("Word 人物迁移数据损坏", it) }
    }

    private fun readZipEntryLimited(zip: ZipInputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8_192)
        while (true) {
            val count = zip.read(buffer)
            if (count < 0) break
            out.write(buffer, 0, count)
            require(out.size() <= MAX_PERSONA_TRANSFER_BYTES) { "Word 文档内容过大" }
        }
        return out.toByteArray()
    }

    private fun ZipOutputStream.putUtf8(name: String, content: String) {
        putNextEntry(ZipEntry(name))
        write(content.toByteArray(StandardCharsets.UTF_8))
        closeEntry()
    }

    private fun documentXml(lines: List<DocLine>, payload: String): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>""")
        lines.forEach { line ->
            append("<w:p>")
            val style = when (line.level) {
                1 -> "Title"
                2 -> "Heading1"
                3 -> "Heading2"
                else -> null
            }
            if (style != null) append("""<w:pPr><w:pStyle w:val="$style"/></w:pPr>""")
            append("""<w:r><w:t xml:space="preserve">${xmlEscape(line.text)}</w:t></w:r></w:p>""")
        }
        append("<w:p><w:r><w:rPr><w:vanish/></w:rPr><w:t>")
        append(xmlEscape(WORD_PAYLOAD_PREFIX + payload))
        append("</w:t></w:r></w:p>")
        append("""<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440"/></w:sectPr>""")
        append("</w:body></w:document>")
    }

    private fun stylesXml(): String =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
          <w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/></w:style>
          <w:style w:type="paragraph" w:styleId="Title"><w:name w:val="Title"/><w:basedOn w:val="Normal"/><w:pPr><w:spacing w:after="240"/></w:pPr><w:rPr><w:b/><w:sz w:val="36"/></w:rPr></w:style>
          <w:style w:type="paragraph" w:styleId="Heading1"><w:name w:val="heading 1"/><w:basedOn w:val="Normal"/><w:pPr><w:spacing w:before="240" w:after="120"/></w:pPr><w:rPr><w:b/><w:sz w:val="28"/></w:rPr></w:style>
          <w:style w:type="paragraph" w:styleId="Heading2"><w:name w:val="heading 2"/><w:basedOn w:val="Normal"/><w:pPr><w:spacing w:before="180" w:after="80"/></w:pPr><w:rPr><w:b/><w:sz w:val="24"/></w:rPr></w:style>
        </w:styles>""".trimIndent()

    private fun contentTypesXml(): String =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
          <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
          <Default Extension="xml" ContentType="application/xml"/>
          <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
          <Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>
          <Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/>
          <Override PartName="/docProps/app.xml" ContentType="application/vnd.openxmlformats-officedocument.extended-properties+xml"/>
          <Override PartName="/customXml/persona-transfer.xml" ContentType="application/xml"/>
        </Types>""".trimIndent()

    private fun rootRelationshipsXml(): String =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
          <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
          <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/>
          <Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties" Target="docProps/app.xml"/>
        </Relationships>""".trimIndent()

    private fun documentRelationshipsXml(): String =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
          <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
        </Relationships>""".trimIndent()

    private fun corePropertiesXml(name: String): String =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties"
          xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:dcterms="http://purl.org/dc/terms/"
          xmlns:dcmitype="http://purl.org/dc/dcmitype/" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
          <dc:title>${xmlEscape("人物档案：$name")}</dc:title>
          <dc:creator>神言神语</dc:creator>
          <cp:lastModifiedBy>神言神语</cp:lastModifiedBy>
        </cp:coreProperties>""".trimIndent()

    private fun appPropertiesXml(): String =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Properties xmlns="http://schemas.openxmlformats.org/officeDocument/2006/extended-properties"
          xmlns:vt="http://schemas.openxmlformats.org/officeDocument/2006/docPropsVTypes">
          <Application>神言神语</Application>
        </Properties>""".trimIndent()

    private fun xmlEscape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    private fun xmlUnescape(value: String): String = value
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&amp;", "&")
}

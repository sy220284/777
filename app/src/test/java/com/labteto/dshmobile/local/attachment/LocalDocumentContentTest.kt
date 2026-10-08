package com.labteto.dshmobile.local.attachment

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalDocumentContentTest {
    @Test
    fun readsPlainTextAndUtf16Bom() {
        val root = kotlin.io.path.createTempDirectory("doc-text-").toFile()
        try {
            val text = File(root, "sample.txt")
            text.writeBytes(
                byteArrayOf(0xff.toByte(), 0xfe.toByte()) +
                    "你好，777".toByteArray(StandardCharsets.UTF_16LE),
            )

            val result = LocalDocumentContent.extract(text, text.name, "text/plain")

            assertTrue(result.parsed)
            assertTrue(result.text.contains("你好，777"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun readsDocxParagraphs() {
        val file = zipFile(
            "sample.docx",
            mapOf(
                "word/document.xml" to """
                    <w:document xmlns:w="urn:w"><w:body>
                      <w:p><w:r><w:t>第一段</w:t></w:r></w:p>
                      <w:p><w:r><w:t>第二段</w:t></w:r></w:p>
                    </w:body></w:document>
                """.trimIndent(),
            ),
        )
        try {
            val result = LocalDocumentContent.extract(
                file,
                file.name,
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            )

            assertTrue(result.text.contains("第一段"))
            assertTrue(result.text.contains("第二段"))
        } finally {
            file.parentFile?.deleteRecursively()
        }
    }

    @Test
    fun readsXlsxSharedStringsAndNumbers() {
        val file = zipFile(
            "sample.xlsx",
            mapOf(
                "xl/sharedStrings.xml" to """
                    <sst><si><t>姓名</t></si><si><t>热忱</t></si></sst>
                """.trimIndent(),
                "xl/worksheets/sheet1.xml" to """
                    <worksheet><sheetData><row>
                      <c r="A1" t="s"><v>0</v></c>
                      <c r="B1" t="s"><v>1</v></c>
                      <c r="C1"><v>42</v></c>
                    </row></sheetData></worksheet>
                """.trimIndent(),
            ),
        )
        try {
            val result = LocalDocumentContent.extract(
                file,
                file.name,
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            )

            assertTrue(result.text.contains("A1: 姓名"))
            assertTrue(result.text.contains("B1: 热忱"))
            assertTrue(result.text.contains("C1: 42"))
        } finally {
            file.parentFile?.deleteRecursively()
        }
    }

    @Test
    fun readsPptxSlideTextInOrder() {
        val file = zipFile(
            "sample.pptx",
            mapOf(
                "ppt/slides/slide2.xml" to "<p:sld><a:p><a:r><a:t>第二页</a:t></a:r></a:p></p:sld>",
                "ppt/slides/slide1.xml" to "<p:sld><a:p><a:r><a:t>第一页</a:t></a:r></a:p></p:sld>",
            ),
        )
        try {
            val result = LocalDocumentContent.extract(
                file,
                file.name,
                "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            )

            assertTrue(result.text.indexOf("第一页") < result.text.indexOf("第二页"))
        } finally {
            file.parentFile?.deleteRecursively()
        }
    }

    @Test
    fun readsOpenDocumentAndEpubText() {
        val odt = zipFile(
            "sample.odt",
            mapOf(
                "content.xml" to "<office:document><text:p>开放文档内容</text:p></office:document>",
            ),
        )
        val epub = zipFile(
            "sample.epub",
            mapOf(
                "OPS/ch1.xhtml" to "<html><body><h1>第一章</h1><p>电子书正文</p></body></html>",
            ),
        )
        try {
            assertTrue(
                LocalDocumentContent.extract(
                    odt,
                    odt.name,
                    "application/vnd.oasis.opendocument.text",
                ).text.contains("开放文档内容"),
            )
            val epubText = LocalDocumentContent.extract(epub, epub.name, "application/epub+zip").text
            assertTrue(epubText.contains("第一章"))
            assertTrue(epubText.contains("电子书正文"))
        } finally {
            odt.parentFile?.deleteRecursively()
            epub.parentFile?.deleteRecursively()
        }
    }

    @Test
    fun zipListsEntriesAndPreviewsTextFiles() {
        val file = zipFile(
            "bundle.zip",
            mapOf(
                "README.md" to "# 标题\n压缩包内正文",
                "data.bin" to "\u0000\u0001\u0002",
            ),
        )
        try {
            val result = LocalDocumentContent.extract(file, file.name, "application/zip")

            assertTrue(result.text.contains("README.md"))
            assertTrue(result.text.contains("压缩包内正文"))
        } finally {
            file.parentFile?.deleteRecursively()
        }
    }

    @Test
    fun bestEffortPdfExtractsLiteralTextWhenAvailable() {
        val root = kotlin.io.path.createTempDirectory("doc-pdf-").toFile()
        try {
            val file = File(root, "sample.pdf")
            file.writeBytes(
                ("%PDF-1.4\n1 0 obj\n<< /Length 25 >>\nstream\n" +
                    "BT (Hello PDF) Tj ET\nendstream\nendobj\n%%EOF")
                    .toByteArray(StandardCharsets.ISO_8859_1),
            )

            val result = LocalDocumentContent.extract(file, file.name, "application/pdf")

            assertTrue(result.parsed)
            assertTrue(result.text.contains("Hello PDF"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun unknownBinaryStaysExplicitlyUnparsed() {
        val root = kotlin.io.path.createTempDirectory("doc-unknown-").toFile()
        try {
            val file = File(root, "sample.bin").apply { writeBytes(byteArrayOf(0, 1, 2, 3)) }

            val result = LocalDocumentContent.extract(file, file.name, "application/octet-stream")

            assertFalse(result.parsed)
            assertTrue(result.text.isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    private fun zipFile(name: String, entries: Map<String, String>): File {
        val root = kotlin.io.path.createTempDirectory("doc-zip-").toFile()
        val file = File(root, name)
        val bytes = ByteArrayOutputStream().use { output ->
            ZipOutputStream(output).use { zip ->
                entries.forEach { (entryName, text) ->
                    zip.putNextEntry(ZipEntry(entryName))
                    zip.write(text.toByteArray(StandardCharsets.UTF_8))
                    zip.closeEntry()
                }
            }
            output.toByteArray()
        }
        file.writeBytes(bytes)
        return file
    }
}

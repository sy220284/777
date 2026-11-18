package com.labteto.dshmobile.local.attachment

import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.local.model.LocalImageInputMode
import com.labteto.dshmobile.local.model.buildLocalUserModelMessage
import com.labteto.dshmobile.local.model.prepareLocalMultimodalMessages
import java.nio.file.Files
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalPdfScopedRequestAndroidTest {
    @Test
    fun selectingTheThirdScannedPageSendsOnlyOneImageAndNoOriginalDocument() = runBlocking {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val folder = Files.createTempDirectory(cache.toPath(), "pdf-scope").toFile()
        try {
            val pdf = folder.resolve("scan.pdf")
            val document = PdfDocument()
            try {
                listOf(Color.RED, Color.GREEN, Color.BLUE).forEachIndexed { index, color ->
                    val page = document.startPage(PdfDocument.PageInfo.Builder(128, 128, index + 1).create())
                    page.canvas.drawColor(color)
                    document.finishPage(page)
                }
                pdf.outputStream().use(document::writeTo)
            } finally {
                document.close()
            }
            val file = LocalImportedAttachment(
                name = pdf.name,
                relativePath = pdf.name,
                mediaType = "application/pdf",
                bytes = pdf.length(),
                pdfPages = listOf(3),
            )
            val message = buildLocalUserModelMessage("请读这一页", listOf(file))
            val wire = prepareLocalMultimodalMessages(listOf(message), folder, LocalImageInputMode.NATIVE)
                .single().getValue("content").jsonArray.map { it.jsonObject }
            val types = wire.map { it.getValue("type").jsonPrimitive.content }
            assertEquals(listOf("text", "text", "image_url"), types)
            assertTrue(wire[1].getValue("text").jsonPrimitive.content.contains("第 3 页（共 3 页）"))
            val url = wire[2].getValue("image_url").jsonObject.getValue("url").jsonPrimitive.content
            assertTrue(url.startsWith("data:image/jpeg;base64,"))
            val jpeg = Base64.getDecoder().decode(url.substringAfter(','))
            val bitmap = requireNotNull(BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size))
            try {
                val pixel = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
                assertTrue(Color.blue(pixel) > Color.red(pixel) + 100)
                assertTrue(Color.blue(pixel) > Color.green(pixel) + 100)
            } finally {
                bitmap.recycle()
            }
        } finally {
            folder.deleteRecursively()
        }
    }
}

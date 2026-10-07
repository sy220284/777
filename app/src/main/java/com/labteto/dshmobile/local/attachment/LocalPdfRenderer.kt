package com.labteto.dshmobile.local.attachment

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.ByteArrayOutputStream
import java.io.File

internal data class LocalPdfPageImage(
    val pageNumber: Int,
    val mediaType: String,
    val bytes: ByteArray,
)

internal data class LocalPdfRenderResult(
    val pageCount: Int,
    val pages: List<LocalPdfPageImage>,
    val truncated: Boolean,
)

internal fun renderLocalPdfForModel(
    file: File,
    maxPages: Int,
    maxEdge: Int = 1_800,
): LocalPdfRenderResult {
    require(file.isFile) { "PDF 附件不存在" }
    require(maxPages > 0) { "PDF 页数预算必须大于 0" }
    require(maxEdge in 512..4_096) { "PDF 渲染尺寸预算无效" }

    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
        PdfRenderer(descriptor).use { renderer ->
            val pageCount = renderer.pageCount
            val pages = buildList {
                val renderCount = minOf(pageCount, maxPages)
                repeat(renderCount) { index ->
                    val page = renderer.openPage(index)
                    try {
                        val longest = maxOf(page.width, page.height).coerceAtLeast(1)
                        val scale = maxEdge.toFloat() / longest.toFloat()
                        val width = (page.width * scale).toInt().coerceAtLeast(1)
                        val height = (page.height * scale).toInt().coerceAtLeast(1)
                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        try {
                            Canvas(bitmap).drawColor(Color.WHITE)
                            val matrix = Matrix().apply { setScale(scale, scale) }
                            page.render(
                                bitmap,
                                null,
                                matrix,
                                PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY,
                            )
                            val encoded = ByteArrayOutputStream().use { out ->
                                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)) {
                                    "PDF 页面编码失败"
                                }
                                out.toByteArray()
                            }
                            add(
                                LocalPdfPageImage(
                                    pageNumber = index + 1,
                                    mediaType = "image/jpeg",
                                    bytes = encoded,
                                ),
                            )
                        } finally {
                            bitmap.recycle()
                        }
                    } finally {
                        page.close()
                    }
                }
            }
            return LocalPdfRenderResult(
                pageCount = pageCount,
                pages = pages,
                truncated = pageCount > pages.size,
            )
        }
    }
}

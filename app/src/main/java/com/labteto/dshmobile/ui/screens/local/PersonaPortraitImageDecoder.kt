package com.labteto.dshmobile.ui.screens.local

import android.graphics.ImageDecoder
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File

/**
 * Decodes app-private persona artwork for every gallery/avatar surface.
 *
 * ImageDecoder applies the source orientation metadata before returning the bitmap, so a portrait
 * opens upright on its first render instead of requiring UI rotation/offset correction.
 */
internal fun decodePersonaPortraitBitmap(
    path: String,
    maxEdgePx: Int,
): ImageBitmap? {
    if (path.isBlank() || maxEdgePx <= 0) return null
    val file = File(path)
    if (!file.isFile) return null

    return runCatching {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            var sample = 1
            val longest = maxOf(info.size.width, info.size.height)
            while (longest / sample > maxEdgePx) sample *= 2
            if (sample > 1) decoder.setTargetSampleSize(sample)
        }.asImageBitmap()
    }.getOrNull()
}

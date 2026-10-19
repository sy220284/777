package com.labteto.dshmobile.ui.screens.local

import android.content.res.AssetManager
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
        decodePersonaImageBitmap(ImageDecoder.createSource(file), maxEdgePx)
    }.getOrNull()
}

/** Preview a bundled character image before the preset has been installed to private storage. */
internal fun decodePersonaPresetArtworkBitmap(
    assets: AssetManager,
    assetPath: String,
    maxEdgePx: Int,
): ImageBitmap? {
    if (maxEdgePx <= 0 ||
        !assetPath.startsWith("persona-presets/") ||
        ".." in assetPath ||
        assetPath.substringAfterLast('.', "").lowercase() !in setOf("jpg", "jpeg", "png", "webp")
    ) return null

    return runCatching {
        decodePersonaImageBitmap(ImageDecoder.createSource(assets, assetPath), maxEdgePx)
    }.getOrNull()
}

private fun decodePersonaImageBitmap(
    source: ImageDecoder.Source,
    maxEdgePx: Int,
): ImageBitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    var sample = 1
    val longest = maxOf(info.size.width, info.size.height)
    while (longest / sample > maxEdgePx) sample *= 2
    if (sample > 1) decoder.setTargetSampleSize(sample)
}.asImageBitmap()

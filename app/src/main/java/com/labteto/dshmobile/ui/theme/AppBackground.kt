package com.labteto.dshmobile.ui.theme

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

/** Directory under `filesDir` that holds the one background image this app keeps. */
const val APP_BACKGROUND_DIR = "app-background"

/** One-slot file name for the copied custom background. */
const val APP_BACKGROUND_FILE = "background"

/** Coarse vertical regions used by foreground surfaces to adapt to the picture below them. */
enum class BackgroundRegion { ALL, TOP, MIDDLE, BOTTOM }

/** Lightweight image statistics. Every value is normalized to 0..1. */
data class BackgroundRegionStats(
    val luminance: Float = 0.5f,
    val contrast: Float = 0f,
    val detail: Float = 0f,
)

data class BackgroundAnalysis(
    val luminance: Float = 0.5f,
    val saturation: Float = 0f,
    val contrast: Float = 0f,
    val detail: Float = 0f,
    val top: BackgroundRegionStats = BackgroundRegionStats(),
    val middle: BackgroundRegionStats = BackgroundRegionStats(),
    val bottom: BackgroundRegionStats = BackgroundRegionStats(),
) {
    fun region(region: BackgroundRegion): BackgroundRegionStats = when (region) {
        BackgroundRegion.ALL -> BackgroundRegionStats(luminance, contrast, detail)
        BackgroundRegion.TOP -> top
        BackgroundRegion.MIDDLE -> middle
        BackgroundRegion.BOTTOM -> bottom
    }

    val complexity: Float
        get() = (contrast * 0.48f + detail * 0.52f).coerceIn(0f, 1f)
}

/**
 * Current custom-background state exposed to screens.
 *
 * This intentionally carries only cheap visual statistics. Foreground composables can ask for a
 * local surface opacity without decoding or re-sampling the bitmap again.
 */
data class AppBackgroundState(
    val image: ImageBitmap? = null,
    val analysis: BackgroundAnalysis? = null,
    val adaptiveContrast: Boolean = true,
    val darkTheme: Boolean = false,
) {
    val hasImage: Boolean get() = image != null

    fun surfaceColor(
        base: Color,
        region: BackgroundRegion = BackgroundRegion.ALL,
        minAlpha: Float,
        maxAlpha: Float,
    ): Color {
        val stats = analysis?.region(region)
        if (!hasImage || !adaptiveContrast || stats == null) return base

        val complexity = (stats.contrast * 0.52f + stats.detail * 0.48f).coerceIn(0f, 1f)
        // Light text needs more protection over bright pictures; dark text needs more over dark ones.
        val luminanceMismatch = if (darkTheme) stats.luminance else 1f - stats.luminance
        val saturationPenalty = (analysis.saturation - 0.45f).coerceAtLeast(0f)
        val alpha = (
            minAlpha +
                complexity * 0.26f +
                luminanceMismatch * 0.22f +
                saturationPenalty * 0.08f
            ).coerceIn(minAlpha, maxAlpha)
        return base.copy(alpha = alpha)
    }
}

/** The image painted behind the whole app shell, or null for the plain theme colour. */
val LocalAppBackground = staticCompositionLocalOf<ImageBitmap?> { null }

/** Adaptive picture statistics for foreground surfaces. */
val LocalAppBackgroundState = staticCompositionLocalOf { AppBackgroundState() }

private data class LoadedBackground(
    val image: ImageBitmap,
    val analysis: BackgroundAnalysis,
)

/**
 * Paints [path] behind [content] and derives inexpensive per-region readability statistics.
 *
 * Decoding and sampling run off the main thread. Failures collapse to "no background" because a
 * decorative image must never prevent the app from opening.
 */
@Composable
fun AppBackgroundHost(
    path: String?,
    adaptiveEnabled: Boolean = true,
    darkTheme: Boolean = false,
    surfaceBase: Color = Color.Transparent,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val loaded = rememberBackground(path)
    val state = AppBackgroundState(
        image = loaded?.image,
        analysis = loaded?.analysis,
        adaptiveContrast = adaptiveEnabled,
        darkTheme = darkTheme,
    )
    CompositionLocalProvider(
        LocalAppBackground provides loaded?.image,
        LocalAppBackgroundState provides state,
    ) {
        Box(modifier.fillMaxSize()) {
            loaded?.let { background ->
                Image(
                    bitmap = background.image,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
                adaptiveGlobalVeil(state, surfaceBase)?.let { veil ->
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(veil),
                    )
                }
            }
            content()
        }
    }
}

/**
 * Global veil stays deliberately weak. Readability is primarily handled by local top/content/input
 * surfaces, so a good wallpaper remains visible instead of being buried under one uniform mask.
 */
private fun adaptiveGlobalVeil(state: AppBackgroundState, surfaceBase: Color): Color? {
    val analysis = state.analysis ?: return null
    if (!state.hasImage || !state.adaptiveContrast) return null
    val mismatch = if (state.darkTheme) analysis.luminance else 1f - analysis.luminance
    val alpha = (
        0.06f +
            analysis.complexity * 0.12f +
            mismatch * 0.08f +
            (analysis.saturation - 0.55f).coerceAtLeast(0f) * 0.04f
        ).coerceIn(0.06f, 0.28f)
    return surfaceBase.copy(alpha = alpha)
}

@Composable
private fun rememberBackground(path: String?): LoadedBackground? {
    var loaded by remember(path) { mutableStateOf<LoadedBackground?>(null) }
    LaunchedEffect(path) {
        loaded = if (path == null) {
            null
        } else {
            withContext(Dispatchers.IO) {
                runCatching {
                    BitmapFactory.decodeFile(path)?.let { bitmap ->
                        LoadedBackground(
                            image = bitmap.asImageBitmap(),
                            analysis = analyzeBackground(bitmap),
                        )
                    }
                }.getOrNull()
            }
        }
    }
    return loaded
}

private data class PixelSample(
    val normalizedY: Float,
    val luminance: Float,
    val saturation: Float,
    val detail: Float,
)

/**
 * Samples a small regular grid instead of scanning every pixel. The cost stays effectively
 * constant for phone wallpapers of any resolution while still catching bright/dark, saturated and
 * visually busy areas.
 */
internal fun analyzeBackground(bitmap: Bitmap): BackgroundAnalysis {
    if (bitmap.width <= 0 || bitmap.height <= 0) return BackgroundAnalysis()

    val columns = minOf(24, bitmap.width)
    val rows = minOf(36, bitmap.height)
    val previousRow = FloatArray(columns) { Float.NaN }
    val samples = ArrayList<PixelSample>(columns * rows)

    for (row in 0 until rows) {
        var left = Float.NaN
        val y = (((row + 0.5f) * bitmap.height) / rows).toInt().coerceIn(0, bitmap.height - 1)
        for (column in 0 until columns) {
            val x = (((column + 0.5f) * bitmap.width) / columns).toInt().coerceIn(0, bitmap.width - 1)
            val color = bitmap.getPixel(x, y)
            val red = AndroidColor.red(color) / 255f
            val green = AndroidColor.green(color) / 255f
            val blue = AndroidColor.blue(color) / 255f
            val luminance = (0.2126f * red + 0.7152f * green + 0.0722f * blue).coerceIn(0f, 1f)
            val max = maxOf(red, green, blue)
            val min = minOf(red, green, blue)
            val saturation = if (max <= 0.0001f) 0f else ((max - min) / max).coerceIn(0f, 1f)

            var detailSum = 0f
            var detailCount = 0
            if (!left.isNaN()) {
                detailSum += kotlin.math.abs(luminance - left)
                detailCount++
            }
            val above = previousRow[column]
            if (!above.isNaN()) {
                detailSum += kotlin.math.abs(luminance - above)
                detailCount++
            }
            val detail = if (detailCount == 0) 0f else (detailSum / detailCount * 2.2f).coerceIn(0f, 1f)
            samples += PixelSample(
                normalizedY = (row + 0.5f) / rows,
                luminance = luminance,
                saturation = saturation,
                detail = detail,
            )
            left = luminance
            previousRow[column] = luminance
        }
    }

    fun stats(from: Float, until: Float): BackgroundRegionStats {
        val regionSamples = samples.filter { it.normalizedY >= from && it.normalizedY < until }
        if (regionSamples.isEmpty()) return BackgroundRegionStats()
        val average = regionSamples.map { it.luminance }.average().toFloat()
        val variance = regionSamples
            .map { sample ->
                val delta = sample.luminance - average
                delta * delta
            }
            .average()
            .toFloat()
        val contrast = (sqrt(variance) * 2.35f).coerceIn(0f, 1f)
        val detail = regionSamples.map { it.detail }.average().toFloat().coerceIn(0f, 1f)
        return BackgroundRegionStats(average, contrast, detail)
    }

    val global = stats(0f, 1.001f)
    return BackgroundAnalysis(
        luminance = global.luminance,
        saturation = samples.map { it.saturation }.average().toFloat().coerceIn(0f, 1f),
        contrast = global.contrast,
        detail = global.detail,
        top = stats(0f, 0.30f),
        middle = stats(0.30f, 0.74f),
        bottom = stats(0.74f, 1.001f),
    )
}

/**
 * The fill for a surface that covers the whole window.
 *
 * Transparent while a background image is in use, so the picture shows through; the plain
 * [DsColors.bgBase] otherwise.
 */
@Composable
fun DsColors.rootSurface(): Color =
    if (LocalAppBackground.current != null) Color.Transparent else bgBase

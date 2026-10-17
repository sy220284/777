package com.labteto.dshmobile.ui.theme

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.shenyan.designsystem.foundation.LocalSySceneBackdrop
import com.shenyan.designsystem.foundation.SySceneBackdrop
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

/** Directory under `filesDir` that holds the one background image this app keeps. */
const val APP_BACKGROUND_DIR = "app-background"

/** One-slot file name for the copied custom background. */
const val APP_BACKGROUND_FILE = "background"

/** Coarse vertical regions used by foreground surfaces to adapt to the picture below them. */
enum class BackgroundRegion { ALL, TOP, MIDDLE, BOTTOM }

/** Visual weight for any foreground plate shown over a custom wallpaper. */
enum class WallpaperSurfaceLevel {
    PAGE,
    CHROME,
    CARD,
    INPUT,
    FLOATING,
    SHEET,
    DRAWER,
    DIALOG,
    MENU,
}

/** Lightweight image statistics. Every value is normalized to 0..1. */
data class BackgroundRegionStats(
    val luminance: Float = 0.5f,
    val contrast: Float = 0f,
    val detail: Float = 0f,
    val averageColor: Color = Color.Unspecified,
    val darkestColor: Color = Color.Unspecified,
    val brightestColor: Color = Color.Unspecified,
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
    val surfaceTransparency: Float = 0.5f,
    val fluidGlass: Boolean = false,
) {
    val hasImage: Boolean get() = image != null

    fun surfaceColor(
        base: Color,
        region: BackgroundRegion = BackgroundRegion.ALL,
        minAlpha: Float,
        maxAlpha: Float,
    ): Color {
        val stats = analysis?.region(region)
        if (!hasImage) return base
        if (!adaptiveContrast || stats == null) {
            // Disabling adaptive contrast must not force a 76% opaque floor onto every
            // legacy region surface; the user's transparency setting still applies.
            return base.copy(alpha = adjustedSurfaceAlpha(base.alpha.coerceIn(minAlpha, maxAlpha), minAlpha, maxAlpha))
        }

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
        return base.copy(alpha = adjustedSurfaceAlpha(alpha, minAlpha, maxAlpha))
    }

    private fun adjustedSurfaceAlpha(alpha: Float, minAlpha: Float, maxAlpha: Float): Float =
        resolveWallpaperAlpha(
            adaptiveAlpha = alpha,
            minAlpha = minAlpha,
            maxAlpha = maxAlpha,
            transparency = surfaceTransparency,
        )


    /**
     * Resolves one shared foreground-surface policy for wallpaper mode.
     *
     * Large page/chrome areas stay visually light so the wallpaper is never buried by a white
     * slab. Smaller interaction surfaces progressively gain opacity to preserve readability.
     * The same fallback opacities are used when adaptive contrast is disabled, so turning the
     * adaptive analyzer off does not silently restore opaque cards and dialogs.
     */
    fun wallpaperSurface(
        base: Color,
        level: WallpaperSurfaceLevel,
        region: BackgroundRegion = BackgroundRegion.ALL,
    ): Color {
        if (!hasImage) return if (fluidGlass) fluidSurfaceColor(base, level, darkTheme) else base
        if (level == WallpaperSurfaceLevel.PAGE) return Color.Transparent

        val (minAlpha, maxAlpha, fallbackAlpha) = when (level) {
            WallpaperSurfaceLevel.PAGE -> Triple(0f, 0f, 0f)
            WallpaperSurfaceLevel.CHROME -> Triple(0.08f, 0.28f, 0.16f)
            WallpaperSurfaceLevel.CARD -> Triple(0.30f, 0.62f, 0.46f)
            WallpaperSurfaceLevel.INPUT -> Triple(0.34f, 0.66f, 0.50f)
            WallpaperSurfaceLevel.FLOATING -> Triple(0.40f, 0.70f, 0.55f)
            WallpaperSurfaceLevel.SHEET -> Triple(0.44f, 0.72f, 0.58f)
            WallpaperSurfaceLevel.DRAWER -> Triple(0.40f, 0.68f, 0.54f)
            WallpaperSurfaceLevel.DIALOG -> Triple(0.50f, 0.76f, 0.63f)
            WallpaperSurfaceLevel.MENU -> Triple(0.56f, 0.82f, 0.69f)
        }

        if (!adaptiveContrast || analysis == null) {
            return base.copy(alpha = adjustedSurfaceAlpha(fallbackAlpha, minAlpha, maxAlpha))
        }
        return surfaceColor(
            base = base,
            region = region,
            minAlpha = minAlpha,
            maxAlpha = maxAlpha,
        )
    }
}

/**
 * Applies the user's transparency preference after adaptive contrast has picked a safe baseline.
 *
 * 0 means clearer/more opaque and 1 means airier/more transparent. Adaptive analysis still nudges
 * the result, but the user's choice now has enough range to be visible across cards, drawers,
 * sheets and dialogs instead of being almost completely clamped away.
 */
fun resolveWallpaperAlpha(
    adaptiveAlpha: Float,
    minAlpha: Float,
    maxAlpha: Float,
    transparency: Float,
): Float {
    val adjustment = com.shenyan.designsystem.foundation.SyMaterialPolicy.adjustment(transparency)
    val lower = (minAlpha - 0.18f).coerceAtLeast(0.14f)
    val upper = (maxAlpha + 0.08f).coerceAtMost(0.98f)
    return (adaptiveAlpha + adjustment).coerceIn(lower, upper)
}

/** Use the same three vertical sample bands as the on-screen glass renderer. */
fun BackgroundRegion.sampleY(): Float = when (this) {
    BackgroundRegion.TOP -> .15f
    BackgroundRegion.MIDDLE, BackgroundRegion.ALL -> .50f
    BackgroundRegion.BOTTOM -> .88f
}

/** Bound decoded wallpaper size; keep high-resolution display but avoid huge full-size bitmaps. */
fun wallpaperDecodeSample(width: Int, height: Int, maximumDimension: Int = 3072): Int {
    if (width <= 0 || height <= 0 || maximumDimension <= 0) return 1
    var sample = 1
    while (maxOf(width, height) / sample > maximumDimension && sample <= Int.MAX_VALUE / 2) {
        sample *= 2
    }
    return sample
}

/** The image painted behind the whole app shell, or null for the plain theme colour. */
val LocalAppBackground = staticCompositionLocalOf<ImageBitmap?> { null }

/** Adaptive picture statistics for foreground surfaces. */
val LocalAppBackgroundState = staticCompositionLocalOf { AppBackgroundState() }

private data class LoadedBackground(
    val image: ImageBitmap,
    val bitmap: Bitmap,
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
    surfaceTransparency: Float = 0.5f,
    modifier: Modifier = Modifier,
    fluidGlass: Boolean = true,
    ambientGlow: Float = .5f,
    ambientPalette: com.shenyan.designsystem.foundation.SyColors = com.shenyan.designsystem.foundation.ShenyanPalettes.light,
    content: @Composable () -> Unit,
) {
    val loaded = rememberBackground(path)
    val view = LocalView.current
    val windowOnScreen = remember { IntArray(2) }
    var viewportOrigin by remember { mutableStateOf(IntOffset.Zero) }
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }

    // ContentScale.Crop displays only the centre of many landscape wallpapers on a phone.
    // Sample what is actually visible, off the UI thread; do not tint cards from off-screen pixels.
    var croppedAnalysis by remember(path, loaded) { mutableStateOf<Pair<IntSize, BackgroundAnalysis>?>(null) }
    LaunchedEffect(loaded, viewportSize) {
        if (loaded != null && viewportSize.width > 0 && viewportSize.height > 0) {
            val analysis = withContext(Dispatchers.Default) {
                analyzeBackground(loaded.bitmap, viewportSize)
            }
            croppedAnalysis = viewportSize to analysis
        }
    }
    val visibleAnalysis = croppedAnalysis?.takeIf { it.first == viewportSize }?.second ?: loaded?.analysis
    val state = AppBackgroundState(
        image = loaded?.image,
        analysis = visibleAnalysis,
        adaptiveContrast = adaptiveEnabled,
        darkTheme = darkTheme,
        surfaceTransparency = surfaceTransparency.coerceIn(0f, 1f),
        fluidGlass = fluidGlass,
    )
    val sceneBackdrop = if (viewportSize.width > 0 && viewportSize.height > 0 &&
        (loaded != null || fluidGlass)
    ) SySceneBackdrop(
        viewportOrigin = viewportOrigin,
        viewportSize = viewportSize,
        canvas = surfaceBase,
        image = loaded?.image,
        veil = adaptiveGlobalVeil(state, surfaceBase),
        glowColors = if (loaded == null && fluidGlass) listOf(
            ambientPalette.primary, ambientPalette.mint, ambientPalette.lilac.copy(alpha = .18f),
        ) else emptyList(),
        dark = darkTheme,
        regionColors = visibleAnalysis?.let { listOf(it.top.averageColor, it.middle.averageColor, it.bottom.averageColor) } ?: emptyList(),
        regionExtremes = visibleAnalysis?.let { analysis -> listOf(analysis.top, analysis.middle, analysis.bottom)
            .map { listOf(it.darkestColor, it.brightestColor) } } ?: emptyList(),
        surfaceTransparency = state.surfaceTransparency,
        ambientGlow = ambientGlow.coerceIn(0f, 1f),
        adaptiveContrast = adaptiveEnabled,
    ) else null
    Box(modifier.fillMaxSize().background(surfaceBase).onGloballyPositioned { coordinates ->
        view.rootView.getLocationOnScreen(windowOnScreen)
        val point = coordinates.positionInWindow()
        val nextOrigin = IntOffset(point.x.roundToInt() + windowOnScreen[0], point.y.roundToInt() + windowOnScreen[1])
        if (viewportOrigin != nextOrigin) viewportOrigin = nextOrigin
        if (viewportSize != coordinates.size) viewportSize = coordinates.size
    }) {
        CompositionLocalProvider(
            LocalAppBackground provides loaded?.image,
            LocalAppBackgroundState provides state,
            LocalSySceneBackdrop provides sceneBackdrop,
        ) {
            // Main canvas and every overlay use the same position-aligned scene painter.
            // This avoids a second gradient/image implementation drifting in colour or crop.
            if (loaded != null || fluidGlass) {
                com.shenyan.designsystem.components.SySceneBackgroundSurface(
                    modifier = Modifier.fillMaxSize(),
                    shape = androidx.compose.ui.graphics.RectangleShape,
                    fallbackColor = surfaceBase,
                    tag = "appRootSceneBackground",
                ) {}
            }
            content()
        }
    }
}

/** Mobile floating surfaces need a strong veil because they overlay real page content. */
fun fluidSurfaceColor(base: Color, level: WallpaperSurfaceLevel, dark: Boolean): Color {
    if (level == WallpaperSurfaceLevel.PAGE || level == WallpaperSurfaceLevel.CHROME) return Color.Transparent
    val alpha = when (level) {
        WallpaperSurfaceLevel.CARD -> if (dark) 0.72f else 0.62f
        WallpaperSurfaceLevel.INPUT -> 0.82f
        WallpaperSurfaceLevel.FLOATING -> 0.82f
        WallpaperSurfaceLevel.SHEET, WallpaperSurfaceLevel.DRAWER,
        WallpaperSurfaceLevel.DIALOG, WallpaperSurfaceLevel.MENU -> 0.96f
        WallpaperSurfaceLevel.PAGE, WallpaperSurfaceLevel.CHROME -> 0f
    }
    return base.copy(alpha = alpha)
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
    // Keep the previous valid frame visible while decoding a newly selected picture. Reset
    // immediately on explicit clear; never flash the empty fluid-glass screen between swaps.
    var loaded by remember { mutableStateOf<LoadedBackground?>(null) }
    LaunchedEffect(path) {
        loaded = if (path == null) {
            null
        } else {
            withContext(Dispatchers.IO) {
                runCatching {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(path, bounds)
                    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
                    val options = BitmapFactory.Options().apply {
                        inSampleSize = wallpaperDecodeSample(bounds.outWidth, bounds.outHeight)
                    }
                    BitmapFactory.decodeFile(path, options)?.let { bitmap ->
                        LoadedBackground(
                            image = bitmap.asImageBitmap(),
                            bitmap = bitmap,
                            analysis = analyzeBackground(bitmap),
                        )
                    }
                }.getOrNull()
            }
        }
    }
    return if (path == null) null else loaded
}

private data class PixelSample(
    val normalizedY: Float,
    val color: Color,
    val luminance: Float,
    val saturation: Float,
    val detail: Float,
)

/**
 * Samples a small regular grid instead of scanning every pixel. The cost stays effectively
 * constant for phone wallpapers of any resolution while still catching bright/dark, saturated and
 * visually busy areas.
 */
fun analyzeBackground(bitmap: Bitmap, viewport: IntSize? = null): BackgroundAnalysis {
    if (bitmap.width <= 0 || bitmap.height <= 0) return BackgroundAnalysis()

    val visible = viewport?.takeIf { it.width > 0 && it.height > 0 }
    val ratio = visible?.let { it.width.toDouble() / it.height }
    val cropWidth = if (ratio != null && bitmap.width.toDouble() / bitmap.height > ratio)
        (bitmap.height * ratio).roundToInt().coerceIn(1, bitmap.width) else bitmap.width
    val cropHeight = if (ratio != null && bitmap.width.toDouble() / bitmap.height < ratio)
        (bitmap.width / ratio).roundToInt().coerceIn(1, bitmap.height) else bitmap.height
    val cropX = (bitmap.width - cropWidth) / 2
    val cropY = (bitmap.height - cropHeight) / 2
    val columns = minOf(24, cropWidth)
    val rows = minOf(36, cropHeight)
    val previousRow = FloatArray(columns) { Float.NaN }
    val samples = ArrayList<PixelSample>(columns * rows)

    for (row in 0 until rows) {
        var left = Float.NaN
        val y = (cropY + ((row + 0.5f) * cropHeight / rows).toInt()).coerceIn(0, bitmap.height - 1)
        for (column in 0 until columns) {
            val x = (cropX + ((column + 0.5f) * cropWidth / columns).toInt()).coerceIn(0, bitmap.width - 1)
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
                color = Color(red, green, blue),
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
        val tint = Color(
            regionSamples.map { it.color.red }.average().toFloat(),
            regionSamples.map { it.color.green }.average().toFloat(),
            regionSamples.map { it.color.blue }.average().toFloat(),
        )
        return BackgroundRegionStats(average, contrast, detail, tint,
            regionSamples.minBy { it.luminance }.color, regionSamples.maxBy { it.luminance }.color)
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
fun DsColors.wallpaperSurface(
    level: WallpaperSurfaceLevel,
    region: BackgroundRegion = BackgroundRegion.ALL,
    base: Color = when (level) {
        WallpaperSurfaceLevel.PAGE -> bgBase
        WallpaperSurfaceLevel.CHROME -> bgBase
        WallpaperSurfaceLevel.CARD -> bgLayer1
        WallpaperSurfaceLevel.INPUT -> bgLayer2
        WallpaperSurfaceLevel.FLOATING -> bgModulePlatform
        WallpaperSurfaceLevel.SHEET -> bgLayer2
        WallpaperSurfaceLevel.DRAWER -> sidebar
        WallpaperSurfaceLevel.DIALOG -> bgLayer2
        WallpaperSurfaceLevel.MENU -> bgLayer3
    },
): Color {
    if (level == WallpaperSurfaceLevel.PAGE) return LocalAppBackgroundState.current.wallpaperSurface(base, level, region)
    val tier = when (level) {
        WallpaperSurfaceLevel.INPUT -> com.shenyan.designsystem.components.SyMaterialTier.L1_SECTION
        WallpaperSurfaceLevel.CHROME -> com.shenyan.designsystem.components.SyMaterialTier.L2_BAR
        WallpaperSurfaceLevel.DRAWER -> com.shenyan.designsystem.components.SyMaterialTier.L2_BAR
        WallpaperSurfaceLevel.FLOATING, WallpaperSurfaceLevel.SHEET,
        WallpaperSurfaceLevel.DIALOG, WallpaperSurfaceLevel.MENU -> com.shenyan.designsystem.components.SyMaterialTier.L4_OVERLAY
        else -> com.shenyan.designsystem.components.SyMaterialTier.L3_CARD
    }
    val neutral = base in listOf(bgBase, bgLayer1, bgLayer2, bgLayer3, bgModulePlatform, sidebar)
    return if (neutral) com.shenyan.designsystem.components.syMaterialColor(tier, region.sampleY())
    else LocalAppBackgroundState.current.wallpaperSurface(base, level, region)
}

@Composable
fun DsColors.rootSurface(): Color = wallpaperSurface(WallpaperSurfaceLevel.PAGE)

/** V6 root surfaces preserve wallpaper visibility just like the legacy page contract. */
@Composable
fun com.shenyan.designsystem.foundation.SyColors.rootSurface(): Color =
    LocalAppBackgroundState.current.wallpaperSurface(canvas, WallpaperSurfaceLevel.PAGE)

/** V6 semantic material sampling, preserving custom-wallpaper contrast and region behavior. */
@Composable
fun com.shenyan.designsystem.foundation.SyColors.wallpaperSurface(
    level: WallpaperSurfaceLevel,
    region: BackgroundRegion = BackgroundRegion.ALL,
    base: Color = when (level) {
        WallpaperSurfaceLevel.PAGE, WallpaperSurfaceLevel.CHROME -> canvas
        WallpaperSurfaceLevel.INPUT -> materialL1
        WallpaperSurfaceLevel.CARD -> materialL3
        WallpaperSurfaceLevel.FLOATING, WallpaperSurfaceLevel.SHEET,
        WallpaperSurfaceLevel.DIALOG, WallpaperSurfaceLevel.MENU -> materialL4
        WallpaperSurfaceLevel.DRAWER -> materialL2
    },
): Color {
    if (level == WallpaperSurfaceLevel.PAGE) {
        return LocalAppBackgroundState.current.wallpaperSurface(base, level, region)
    }
    val tier = when (level) {
        WallpaperSurfaceLevel.INPUT -> com.shenyan.designsystem.components.SyMaterialTier.L1_SECTION
        WallpaperSurfaceLevel.CHROME -> com.shenyan.designsystem.components.SyMaterialTier.L2_BAR
        WallpaperSurfaceLevel.DRAWER -> com.shenyan.designsystem.components.SyMaterialTier.L2_BAR
        WallpaperSurfaceLevel.FLOATING, WallpaperSurfaceLevel.SHEET,
        WallpaperSurfaceLevel.DIALOG, WallpaperSurfaceLevel.MENU -> com.shenyan.designsystem.components.SyMaterialTier.L4_OVERLAY
        else -> com.shenyan.designsystem.components.SyMaterialTier.L3_CARD
    }
    val neutral = base in listOf(canvas, materialL1, materialL2, materialL3, materialL4, surface, surfaceRaised)
    return if (neutral) com.shenyan.designsystem.components.syMaterialColor(tier, region.sampleY())
        else LocalAppBackgroundState.current.wallpaperSurface(base, level, region)
}

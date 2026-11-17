package com.shenyan.designsystem.foundation

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.CubicBezierEasing
import kotlin.math.roundToInt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

/** 完全独立的新组件库，不依赖 777 旧版 DsTheme / DsButton / Ds*。 */
enum class ShenyanMode { LIGHT, DARK, MATTE }

@Immutable
data class SyColors(
    val canvas: Color,
    val topBar: Color,
    // V7: paper, inset, raised, overlay and reading surfaces; wallpaper tint is opt-in by scene.
    val materialL1: Color,
    val materialL2: Color,
    val materialL3: Color,
    val materialL4: Color,
    val materialL5: Color,
    val materialSheen: Color,
    val clay: Color = Color(0xFFC98B6B),
    val surface: Color,
    val surfaceRaised: Color,
    val surfaceSolid: Color,
    val border: Color,
    val text: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val primary: Color = Color(0xFF315447),
    val primaryPressed: Color = Color(0xFF244337),
    val onPrimary: Color = Color(0xFFFFFFFF),
    val mint: Color = Color(0xFF75A58C),
    val lilac: Color = Color(0xFF865950),
    val warning: Color = Color(0xFFBD8A36),
    val danger: Color = Color(0xFFB8473D),
    val selected: Color,
    val overlay: Color,
    val wallpaperTintEnabled: Boolean = true,
) {
    // Decoration and small status text have different contrast requirements.
    // Keep decorative accent fills independent from readable text colours.
    val actionFill: Color get() = primary
    val actionInk: Color get() = onPrimary
    val linkInk: Color get() = syReadableInk(primary, materialL2)
    val personaInk: Color get() = syReadableInk(lilac, materialL2)
    val successInk: Color get() = if (text.luminance() < .5f) Color(0xFF2E6755) else mint
    val warningInk: Color get() = if (text.luminance() < .5f) Color(0xFF80511F) else warning
    val dangerInk: Color get() = if (text.luminance() < .5f) Color(0xFF963B30) else Color(0xFFE89B8F)
}

/** Text foreground contrast over the opaque local reading surface. */
fun syInkContrastRatio(ink: Color, background: Color): Float {
    val solid = background.copy(alpha = 1f)
    val foreground = ink.compositeOver(solid).luminance()
    val backdrop = solid.luminance()
    return (maxOf(foreground, backdrop) + .05f) / (minOf(foreground, backdrop) + .05f)
}

/** Preserve the selected hue and adjust brightness to meet readable text contrast. */
fun syReadableInk(accent: Color, background: Color, minimumRatio: Float = 4.5f): Color {
    val solid = background.copy(alpha = 1f)
    val source = accent.copy(alpha = 1f)
    if (syInkContrastRatio(source, solid) >= minimumRatio) return source
    // Mid-tone backgrounds often favour black text even when their luminance is below .5.
    val target = if (syInkContrastRatio(Color.Black, solid) >= syInkContrastRatio(Color.White, solid))
        Color.Black else Color.White
    var low = 0f
    var high = 1f
    repeat(14) {
        val middle = (low + high) / 2f
        if (syInkContrastRatio(lerp(source, target, middle), solid) < minimumRatio) low = middle
        else high = middle
    }
    return lerp(source, target, high)
}

private val Light = SyColors(
    canvas = Color(0xFFF5F7F3), topBar = Color(0xFFECF0E8),
    materialL1 = Color(0xFFF5F7F3), materialL2 = Color(0xFFECF0E8),
    materialL3 = Color(0xFFFFFFFF), materialL4 = Color(0xFFFFFFFF),
    materialL5 = Color(0xFFFFFFFF), materialSheen = Color.Transparent,
    surface = Color(0xFFFFFFFF), surfaceRaised = Color(0xFFECF0E8), surfaceSolid = Color(0xFFFFFFFF),
    border = Color(0xFFD8DFD6), text = Color(0xFF202923),
    textSecondary = Color(0xFF58665C), textTertiary = Color(0xFF5F6A61),
    primary = Color(0xFF315447), primaryPressed = Color(0xFF244337), onPrimary = Color(0xFFFFFFFF),
    mint = Color(0xFF75A58C), lilac = Color(0xFF865950),
    warning = Color(0xFFBD8A36), danger = Color(0xFFB8473D),
    selected = Color(0xFFE7EEDC), overlay = Color(0x66101B18),
    wallpaperTintEnabled = true,
)
private val Dark = SyColors(
    canvas = Color(0xFF141C18), topBar = Color(0xFF26332A),
    materialL1 = Color(0xFF19231D), materialL2 = Color(0xFF26332A),
    materialL3 = Color(0xFF1E2922), materialL4 = Color(0xFF1E2922),
    materialL5 = Color(0xFF1E2922), materialSheen = Color.Transparent,
    surface = Color(0xFF1E2922), surfaceRaised = Color(0xFF26332A), surfaceSolid = Color(0xFF1E2922),
    border = Color(0xFF3B4B40), text = Color(0xFFEDF2E9),
    textSecondary = Color(0xFFC0CDC1), textTertiary = Color(0xFFB1BFB3),
    primary = Color(0xFFB1D3BC), primaryPressed = Color(0xFF9CC6A9), onPrimary = Color(0xFF173225),
    mint = Color(0xFF9BC6AF), lilac = Color(0xFFDAB4A9),
    warning = Color(0xFFE2BC78), danger = Color(0xFFF2B1A4),
    selected = Color(0xFF344735), overlay = Color(0x99101813),
    wallpaperTintEnabled = true,
)
private val Matte = SyColors(
    canvas = Color(0xFF000000), topBar = Color(0xFF141916),
    materialL1 = Color(0xFF090D0B), materialL2 = Color(0xFF141916),
    materialL3 = Color(0xFF191F1A), materialL4 = Color(0xFF191F1A),
    materialL5 = Color(0xFF191F1A), materialSheen = Color.Transparent,
    surface = Color(0xFF191F1A), surfaceRaised = Color(0xFF141916), surfaceSolid = Color(0xFF191F1A),
    border = Color(0xFF37453C), text = Color(0xFFEDF2E9),
    textSecondary = Color(0xFFC0CDC1), textTertiary = Color(0xFFB1BFB3),
    primary = Color(0xFFB1D3BC), primaryPressed = Color(0xFF9CC6A9), onPrimary = Color(0xFF173225),
    mint = Color(0xFF9BC6AF), lilac = Color(0xFFDAB4A9),
    warning = Color(0xFFE2BC78), danger = Color(0xFFF2B1A4),
    selected = Color(0xFF2D3B2E), overlay = Color(0xB3000000),
    wallpaperTintEnabled = false,
)

object ShenyanPalettes {
    val light: SyColors get() = Light
    val dark: SyColors get() = Dark
    val matte: SyColors get() = Matte
    fun forMode(mode: ShenyanMode): SyColors = when (mode) {
        ShenyanMode.LIGHT -> Light
        ShenyanMode.DARK -> Dark
        ShenyanMode.MATTE -> Matte
    }
}

val LocalSyColors = staticCompositionLocalOf { Light }

/** Root scene coordinates for aligned, GPU-blurred backing in V6 glass surfaces.
 * This represents the app's wallpaper / static light fields, not private content in other cards.
 */
data class SySceneBackdrop(
    val viewportOrigin: IntOffset,
    val viewportSize: IntSize,
    val canvas: Color,
    val image: ImageBitmap? = null,
    val veil: Color? = null,
    val glowColors: List<Color> = emptyList(),
    val dark: Boolean = false,
    val regionColors: List<Color> = emptyList(),
    val regionExtremes: List<List<Color>> = emptyList(),
    val surfaceTransparency: Float = .5f,
    val ambientGlow: Float = .5f,
    val adaptiveContrast: Boolean = true,
)

val LocalSySceneBackdrop = staticCompositionLocalOf<SySceneBackdrop?> { null }


/** 非业务状态量统一设计，用户自定义字体缩放交由 Compose sp / 系统处理。 */
object SyMetric {
    val touch = 48.dp
    // Navigation geometry is V6 pack-owned, shared by feature stack and peer swaps.
    val navigationPushOffset = 12.dp
    val navigationPeerOffset = 4.dp
    val gapXs = 4.dp
    val gapSm = 8.dp
    val gapMd = 12.dp
    val gapLg = 16.dp
    val gapSection = 24.dp
    val gapPage = 32.dp
    val gapHero = 48.dp
    val radiusSm = 8.dp
    val radiusControl = 16.dp
    val radiusCard = 20.dp
    val radiusSheet = 28.dp
}
/** Opacity response shared by V6 glass and retained legacy surface adapters. */
object SyMaterialPolicy {
    const val transparencyStrength = .46f
    fun adjustment(transparency: Float): Float =
        (.5f - transparency.coerceIn(0f, 1f)) * transparencyStrength
}

object SyMotion {
    // One motion vocabulary for the current V6 UI pack. Timing variants belong here,
    // not in screens or a second DsAnimations token collection.
    val ease = CubicBezierEasing(.22f, 1f, .36f, 1f)
    const val pressedScale = .985f
    const val reducedMotionMs = 0
    fun duration(ms: Int, reducedMotion: Boolean, tempo: Float = 1f): Int =
        if (reducedMotion) reducedMotionMs
        else (ms * tempo.coerceIn(.7f, 1.3f)).roundToInt()
    // Responsive V6 motion: input first, geometry second; all lengths in milliseconds.
    const val pressMs = 90
    const val selectMs = 140
    const val dialogMs = 220
    const val sheetMs = 240
    const val pushMs = 220
    const val morphMs = 220
    const val toolExpandMs = 170
    const val toolDetailDelayMs = 55
    const val toastMs = 170
    const val sidebarMs = 220
    const val sidebarContentDelayMs = 0
    const val progressMs = 280
    const val composerRevealMs = 155
    const val composerFadeMs = 95
    const val chevronMs = 130
    const val interactionColorMs = 120
    const val fadeMs = 130
    const val pagePopMs = 180
    const val pagePeerMs = 140
    const val pageEnterFadeMs = 130
    const val pageExitFadeMs = 95
    const val cascadeStaggerMs = 55
    const val livePulseMs = 900
    const val holdStartMs = 420L
    const val holdRepeatMs = 90L
    const val hapticIntervalMs = 45L
}

/** 明确传入 reduceMotion；由宿主根据系统动画缩放/无障碍设置确定，不擅自读取旧项目开关。 */
val LocalSyReduceMotion = staticCompositionLocalOf { false }
/** One app-wide tempo for the V6 animation owners; no unsupported platform locals. */
val LocalSyMotionTempo = staticCompositionLocalOf { 1f }
val LocalSyReduceTransparency = staticCompositionLocalOf { false }

@Composable
fun ShenyanTheme(
    mode: ShenyanMode = ShenyanMode.LIGHT,
    reduceMotion: Boolean = false,
    reduceTransparency: Boolean = false,
    content: @Composable () -> Unit
) {
    val colors = when (mode) {
        ShenyanMode.LIGHT -> Light
        ShenyanMode.DARK -> Dark
        ShenyanMode.MATTE -> Matte
    }
    val scheme = (if (mode == ShenyanMode.LIGHT) lightColorScheme(
        primary = colors.primary, onPrimary = colors.onPrimary,
        background = colors.canvas, onBackground = colors.text,
        surface = colors.surfaceSolid, onSurface = colors.text,
        surfaceVariant = colors.surfaceRaised, onSurfaceVariant = colors.textSecondary,
        secondary = colors.mint, tertiary = colors.lilac, error = colors.danger
    ) else darkColorScheme(
        primary = colors.primary, onPrimary = colors.onPrimary,
        background = colors.canvas, onBackground = colors.text,
        surface = colors.surfaceSolid, onSurface = colors.text,
        surfaceVariant = colors.surfaceRaised, onSurfaceVariant = colors.textSecondary,
        secondary = colors.mint, tertiary = colors.lilac, error = colors.danger
    ))
    CompositionLocalProvider(LocalSyColors provides colors, LocalSyReduceMotion provides reduceMotion,
        LocalSyReduceTransparency provides reduceTransparency) {
        MaterialTheme(
            colorScheme = scheme,
            shapes = Shapes(
                small = RoundedCornerShape(SyMetric.radiusControl),
                medium = RoundedCornerShape(SyMetric.radiusCard),
                large = RoundedCornerShape(SyMetric.radiusSheet)
            ), content = content
        )
    }
}

enum class SyPhase { IDLE, WAITING, RUNNING, DONE, FAILED, CANCELLED, BLOCKED }

data class SyOption(val id: String, val label: String, val enabled: Boolean = true)

/** Nested surfaces become solid after three translucent layers. */
val LocalSyGlassDepth = staticCompositionLocalOf { 0 }
/** A11y: use status words, never use colour alone to distinguish running/failure. */
fun SyPhase.asChineseLabel(): String = when (this) {
    SyPhase.IDLE -> "未开始"
    SyPhase.WAITING -> "等待中"
    SyPhase.RUNNING -> "运行中"
    SyPhase.DONE -> "已完成"
    SyPhase.FAILED -> "失败，可检查"
    SyPhase.CANCELLED -> "已取消"
    SyPhase.BLOCKED -> "等待批准"
}

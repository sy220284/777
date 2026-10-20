package com.shenyan.designsystem.foundation

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.CubicBezierEasing
import kotlin.math.roundToInt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
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
    // Five semantic material tiers. L1-L3 are translucent; L4 prioritises readability; L5 solid.
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
    val primary: Color = Color(0xFFE8A87C),
    val primaryPressed: Color = Color(0xFFD9926B),
    val onPrimary: Color = Color(0xFF3A342D),
    val mint: Color = Color(0xFF7FBFA8),
    val lilac: Color = Color(0xFFB9A0D4),
    val warning: Color = Color(0xFFE0A458),
    val danger: Color = Color(0xFFD9705F),
    val selected: Color,
    val overlay: Color,
) {
    // Decoration and small status text have different contrast requirements.
    val successInk: Color get() = if (text.luminance() < .5f) Color(0xFF2E6755) else mint
    val warningInk: Color get() = if (text.luminance() < .5f) Color(0xFF80511F) else warning
    val dangerInk: Color get() = if (text.luminance() < .5f) Color(0xFF963B30) else Color(0xFFE89B8F)
}

private val Light = SyColors(
    canvas = Color(0xFFEDE6DA), topBar = Color(0xFFF6F1E9),
    // All material tiers share the warm canvas hue; increasing opacity, not whiteness, adds depth.
    materialL1 = Color(0x4DEDE6DA), materialL2 = Color(0x75F1E8DC),
    materialL3 = Color(0xA3F1E8DC), materialL4 = Color(0xE0F1E8DC),
    materialL5 = Color(0xFFF1E8DC), materialSheen = Color(0x66F6F0E6),
    surface = Color(0xA3F1E8DC), surfaceRaised = Color(0x75F1E8DC), surfaceSolid = Color(0xFFF1E8DC),
    border = Color(0x66D3C5B5), text = Color(0xFF3A342D),
    textSecondary = Color(0xFF6A6156), textTertiary = Color(0xFF70665A),
    selected = Color(0xFFF2C7A8), overlay = Color(0x335A4837)
)
private val Dark = SyColors(
    canvas = Color(0xFF24211E), topBar = Color(0xFF292521),
    materialL1 = Color(0x1A302B27), materialL2 = Color(0x29302B27),
    materialL3 = Color(0x4035302B), materialL4 = Color(0xE0312B26),
    materialL5 = Color(0xFF35312D), materialSheen = Color(0x29FFF1DE),
    surface = Color(0x4035302B), surfaceRaised = Color(0x29302B27), surfaceSolid = Color(0xFF35312D),
    border = Color(0x21FFFFFF), text = Color(0xFFF5F0E8),
    textSecondary = Color(0xFFD9D0C5), textTertiary = Color(0xFFB8AB9B),
    selected = Color(0xFF60422F), overlay = Color(0x5C000000)
)
private val Matte = SyColors(
    canvas = Color(0xFF0F0F10), topBar = Color(0xFF18181A),
    materialL1 = Color(0xFF181819), materialL2 = Color(0xFF1E1E20),
    materialL3 = Color(0xFF232324), materialL4 = Color(0xFF29292B),
    materialL5 = Color(0xFF29292B), materialSheen = Color(0x0EFFFFFF),
    surface = Color(0xFF232324), surfaceRaised = Color(0xFF1E1E20), surfaceSolid = Color(0xFF29292B),
    border = Color(0x1AFFFFFF), text = Color(0xFFF5F0E8),
    textSecondary = Color(0xFFD9D0C5), textTertiary = Color(0xFFB8AB9B),
    selected = Color(0xFF573F30), overlay = Color(0x99000000)
)

/** Published read-only palettes; one source for Figma, native and migration adapters. */
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
    val navigationPushOffset = 20.dp
    val navigationPeerOffset = 6.dp
    val gapXs = 2.dp
    val gapSm = 6.dp
    val gapMd = 10.dp
    val gapLg = 14.dp
    val gapSection = 22.dp
    val gapPage = 34.dp
    val gapHero = 54.dp
    val radiusSm = 9.dp
    val radiusControl = 13.dp
    val radiusCard = 18.dp
    val radiusSheet = 24.dp
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
    const val pressMs = 70
    const val selectMs = 150
    const val dialogMs = 220
    const val sheetMs = 280
    const val pushMs = 260
    const val morphMs = 260
    const val toolExpandMs = 170
    const val toolDetailDelayMs = 55
    const val toastMs = 170
    const val sidebarMs = 180
    const val sidebarContentDelayMs = 0
    const val progressMs = 280
    const val composerRevealMs = 155
    const val composerFadeMs = 95
    const val chevronMs = 130
    const val interactionColorMs = 120
    const val fadeMs = 130
    const val pagePopMs = 220
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
data class SyAction(val id: String, val label: String, val destructive: Boolean = false, val enabled: Boolean = true)
data class SyStep(val id: String, val label: String, val detail: String, val phase: SyPhase)
data class SyFileNode(val id: String, val name: String, val isFolder: Boolean, val depth: Int = 0)
data class SyAttachment(val id: String, val name: String, val description: String = "", val phase: SyPhase = SyPhase.DONE)

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

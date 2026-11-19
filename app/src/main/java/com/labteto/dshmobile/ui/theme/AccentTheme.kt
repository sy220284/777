package com.labteto.dshmobile.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import com.shenyan.designsystem.foundation.syInkContrastRatio

/**
 * 777 的交互色卡。当前默认色与历史色仅为用户可选入口；页面最终配色由语义对比、背景与场景共同决定。
 *
 * 色卡只改变亮 / 暗操作强调色；浅色主题始终保持白色画布与黑色文字。
 */
data class AccentPalette(
    val key: String,
    val cnName: String,
    val lightAccent: Color,
    val darkAccent: Color,
    val onLight: Color = Color(0xFFFFFFFF),
    val onDark: Color = Color(0xFF0C1A19),
    val darkCanvas: Color = Color(0xFF24211E),
    val darkSurface: Color = Color(0xFF35312D),
)

object AccentPalettes {

    /** 流体杏为默认交互色；旧版默认 "celadon" 在 [of] 中跟随当前默认。 */
    val ALL = listOf(
        AccentPalette("fluid_apricot", "流体杏", Color(0xFFD9926B), Color(0xFFE8A87C),
            onLight = Color.Black, onDark = Color(0xFF3A342D)),
        AccentPalette("vermilion", "朱砂墨", Color(0xFFAA4235), Color(0xFFD87460),
            darkCanvas = Color(0xFF291F1C), darkSurface = Color(0xFF372A24)),
        AccentPalette("forest", "森野", Color(0xFF276D4E), Color(0xFF8CA66B),
            darkCanvas = Color(0xFF1D2721), darkSurface = Color(0xFF2C392F)),
        AccentPalette("amber", "琥珀", Color(0xFFAA6A21), Color(0xFFE3AD57),
            darkCanvas = Color(0xFF29231B), darkSurface = Color(0xFF3B3024)),
        AccentPalette("celadon_explicit", "青瓷", Color(0xFF307B67), Color(0xFF80B7A1),
            darkCanvas = Color(0xFF1D2928), darkSurface = Color(0xFF2C3A37)),
        AccentPalette("graphite", "岩墨", Color(0xFF665E50), Color(0xFFB9A58B),
            darkCanvas = Color(0xFF222323), darkSurface = Color(0xFF343432)),
        AccentPalette("rose_copper", "玫铜", Color(0xFFA94C50), Color(0xFFD99283),
            darkCanvas = Color(0xFF2B2123), darkSurface = Color(0xFF3D2E2D)),
        AccentPalette("olive", "橄榄", Color(0xFF5B7240), Color(0xFFADBD79),
            darkCanvas = Color(0xFF24291F), darkSurface = Color(0xFF363C2C)),
        AccentPalette("modern_coral", "珊瑚几何", Color(0xFFE44E34), Color(0xFFF2A44C),
            darkCanvas = Color(0xFF29231E), darkSurface = Color(0xFF3B3229)),
    )

    val DEFAULT = ALL.first()

    fun of(key: String?): AccentPalette = when (key) {
        null, "", "celadon" -> DEFAULT
        // Persisted historical keys migrate into supported coordinated colour families.
        "default_blue" -> ALL.first { it.key == "forest" }
        "dailan" -> ALL.first { it.key == "graphite" }
        "zhusha" -> ALL.first { it.key == "vermilion" }
        "xianghuang" -> ALL.first { it.key == "amber" }
        "zitang" -> ALL.first { it.key == "rose_copper" }
        "yanzhi" -> ALL.first { it.key == "olive" }
        else -> ALL.firstOrNull { it.key == key } ?: DEFAULT
    }
}

/**
 * Derive one readable ink from the selected primary hue before tinting the
 * gradient and hover. This keeps every action fill on the same contrast-safe
 * side of the luminance range while retaining the user's chosen hue.
 */
internal fun readableAccentForeground(preferred: Color, fills: List<Color>): Color {
    require(fills.isNotEmpty())
    fun minContrast(ink: Color): Float =
        fills.minOf { syInkContrastRatio(ink, it.copy(alpha = 1f)) }
    val preferredSolid = preferred.copy(alpha = 1f)
    if (minContrast(preferredSolid) >= 4.5f) return preferredSolid
    val target = listOf(Color.Black, Color.White).maxBy(::minContrast)
    // The palette controls the fills. If the extremes straddle both polarities,
    // fail visibly in tests instead of inventing an unreadable foreground.
    check(minContrast(target) >= 4.5f) { "交互色各状态没有共同的可读文字颜色" }
    var low = 0f
    var high = 1f
    repeat(18) {
        val middle = (low + high) / 2f
        if (minContrast(lerp(preferredSolid, target, middle)) < 4.5f) low = middle
        else high = middle
    }
    return lerp(preferredSolid, target, high)
}

/**
 * 把选中的色卡覆盖进语义色：accent 家族整体换血，其余语义（文字/画布/成功/警示）原样保留。
 * 换色面刻意收窄——整页只有交互色变，Functional 族与 Family 容器色不跟卡走，页面不会花。
 */
fun SyHostColors.withAccent(palette: AccentPalette, dark: Boolean): SyHostColors {
    val accent = if (dark) palette.darkAccent else palette.lightAccent
    val preferredOn = if (dark) palette.onDark else palette.onLight
    val tertiary = if (dark) accent.copy(alpha = 0.16f) else accent.copy(alpha = 0.12f)
    // Foreground selection is made against the user's actual hue. Secondary
    // action fills move slightly *toward* the contrast-safe pole rather than
    // becoming translucent over a different luminance surface.
    val on = readableAccentForeground(preferredOn, listOf(accent))
    val toward = if (on.luminance() < 0.5f) Color.White else Color.Black
    val primaryHover = lerp(accent, toward, 0.08f)
    // Only light-mode generic hover is accent-tinted; dark generic hover stays neutral.
    val interactionHover = if (dark) this.hover else accent.copy(alpha = 0.06f)
    val interactionPressed = accent.copy(alpha = if (dark) 0.24f else 0.19f)
    val interactionActive = accent.copy(alpha = if (dark) 0.14f else 0.15f)
    // Nudge the first gradient stop away from the accent while preserving foreground contrast.
    val brandGradientStart = lerp(accent, toward, 0.10f)
    val dimmedPrimary = accent.copy(alpha = 0.30f).compositeOver(bgLayer2)
    return copy(
        brandPrimary = brandGradientStart,
        onBrandPrimary = on,
        buttonPrimaryHover = primaryHover,
        buttonPrimaryDimmed = dimmedPrimary,
        accent = accent,
        onAccent = on,
        accentTertiary = tertiary,
        accentHover = primaryHover,
        hover = interactionHover,
        hoverAccent = interactionPressed,
        active = interactionActive,
        buttonInfoFill = accent,
        buttonInfoHover = primaryHover,
        userBubble = if (dark) userBubble else accent.copy(alpha = 0.10f),
        sidebarNavAccent = accent.copy(alpha = if (dark) 0.16f else 0.10f),
    )
}

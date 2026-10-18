/**
 * 朝夕设计系统 · Compose 主题层（E8.0 设计冻结）
 *
 * 性质：实施规格（design spec），非可直接编译交付。落到 `:ui-pack-zhaoxi` 时
 * 保持 FQN 与字段名不变；值以 tokens/tokens.css 为唯一事实源，本文件为镜像。
 *
 * 门禁（对接 #712 check-ui-design-system-boundaries.py）：
 *  - 组件只允许引用 ZhaoxiTheme.colors / type / shapes / motion，禁 Color(0x…) 字面量
 *  - 材质只能经 Modifier.zxMaterial()，禁手写 blur/background 组合
 *  - 动画只能经 ZhaoxiMotion.spec()，其内部处理 reduced-motion 降级
 */
package com.labteto.dshmobile.uipack.zhaoxi.theme

import androidx.compose.animation.core.*
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ---------------------------------------------------------------------------
// 1. 色相：五套低饱和主题（一套一色，只染点缀）
// ---------------------------------------------------------------------------

enum class ZhaoxiHue(val key: String) { STONE("stone"), SAGE("sage"), STEEL("steel"), MAUVE("mauve"), DUSK("dusk") }

data class ZhaoxiAccent(val accent: Color, val tint: Color, val accentInk: Color = accent)

/** E8.18 保留原点缀色；浅色 accentInk 单独加深，确保白色及次级表面上小字至少 4.5:1。 */
fun accentFor(hue: ZhaoxiHue, dark: Boolean): ZhaoxiAccent = when (hue) {
    ZhaoxiHue.STONE -> if (dark) ZhaoxiAccent(Color(0xFF938D89), Color(0x1F938D89)) else ZhaoxiAccent(Color(0xFF78716C), Color(0x1F78716C), Color(0xFF706963))
    ZhaoxiHue.SAGE  -> if (dark) ZhaoxiAccent(Color(0xFF7C9483), Color(0x247C9483)) else ZhaoxiAccent(Color(0xFF7C9483), Color(0x247C9483), Color(0xFF526D5A))
    ZhaoxiHue.STEEL -> if (dark) ZhaoxiAccent(Color(0xFF6E88A0), Color(0x246E88A0), Color(0xFF8FA5BC)) else ZhaoxiAccent(Color(0xFF6E88A0), Color(0x246E88A0), Color(0xFF4F687D))
    ZhaoxiHue.MAUVE -> if (dark) ZhaoxiAccent(Color(0xFF967F95), Color(0x24967F95), Color(0xFFA896A7)) else ZhaoxiAccent(Color(0xFF967F95), Color(0x24967F95), Color(0xFF715B70))
    ZhaoxiHue.DUSK  -> if (dark) ZhaoxiAccent(Color(0xFFA08A76), Color(0x24A08A76), Color(0xFFB29C86)) else ZhaoxiAccent(Color(0xFFA08A76), Color(0x24A08A76), Color(0xFF786048))
}

// ---------------------------------------------------------------------------
// 2. 中性色板（明暗双档；数值 = tokens.css :root / [data-mode=dark]）
// ---------------------------------------------------------------------------

@Immutable
data class ZhaoxiColors(
    val bg: Color,          // 根表面 M0
    val bg2: Color,         // 次级填充 M0
    val side: Color,        // 抽屉底
    val card: Color,        // 卡片底
    val ink: Color,         // 主文字 / 墨色按钮
    val ink2: Color,        // 次级文字（≥4.5:1）
    val ink3: Color,        // 辅助文字 / 占位（小字按 ≥4.5:1）
    val line: Color,        // 分隔线 / 描边
    val glass: Color,       // M2 轻玻璃底
    val glass2: Color,      // M3 强玻璃底
    val accent: Color,      // 点缀色（当前 hue）
    val accentInk: Color,   // 文字安全强调色（E8.18 --accent-ink）
    val tint: Color,        // 点缀底色
    val ok: Color, val okBg: Color,
    val warn: Color, val warnBg: Color,
    val err: Color, val errBg: Color,
    val onInk: Color,       // 墨色按钮上的文字（恒近白/近黑）
    // 云母 M1：静态径向渐变两端色（零模糊成本；深色 tint 不透明度减半）
    val micaA: Color, val micaB: Color,
    // E8.9 背景体系：环境光主/辅（ambA 左上径向，ambB 右下径向；壁纸存在时整体 ×0.16）
    val ambA: Color, val ambB: Color,
)

fun zhaoxiColors(hue: ZhaoxiHue, dark: Boolean): ZhaoxiColors {
    val a = accentFor(hue, dark)
    return if (dark) ZhaoxiColors(
        bg = Color(0xFF171717), bg2 = Color(0xFF212121), side = Color(0xFF1B1B1B), card = Color(0xFF212121),
        ink = Color(0xFFECECEC), ink2 = Color(0xFF9B9B9B), ink3 = Color(0xFF9B9BA4),
        line = Color(0x17FFFFFF),
        glass = Color(0xD9171717), glass2 = Color(0xF0171717),
        accent = a.accent, accentInk = a.accentInk, tint = a.tint,
        ok = Color(0xFF7FA88F), okBg = Color(0x294E8A5A),
        warn = Color(0xFFB08030), warnBg = Color(0x29B08030),
        err = Color(0xFFB34A42), errBg = Color(0x29B34A42),
        onInk = Color(0xFF171717),
        micaA = a.tint.copy(alpha = a.tint.alpha / 2), micaB = Color(0xFF171717),
        ambA = ambFor(hue, dark).first, ambB = ambFor(hue, dark).second,
    ) else ZhaoxiColors(
        bg = Color(0xFFFFFFFF), bg2 = Color(0xFFF7F7F7), side = Color(0xFFF7F7F7), card = Color(0xFFFFFFFF),
        ink = Color(0xFF0D0D0D), ink2 = Color(0xFF6B7280), ink3 = Color(0xFF686872),
        line = Color(0x140D0D0D),
        glass = Color(0xD9FFFFFF), glass2 = Color(0xEBFFFFFF),
        accent = a.accent, accentInk = a.accentInk, tint = a.tint,
        ok = Color(0xFF4E8A5A), okBg = Color(0x1F4E8A5A),
        warn = Color(0xFF8A661C), warnBg = Color(0xFFF7F1E3),
        err = Color(0xFFB34A42), errBg = Color(0x1FB34A42),
        onInk = Color(0xFFFFFFFF),
        micaA = a.tint, micaB = Color(0xFFFFFFFF),
        ambA = ambFor(hue, dark).first, ambB = ambFor(hue, dark).second,
    )
}

/** E8.9 环境光取色（与 tokens.json color.hue.*.amb* 逐值同源，R4 合并后改用 ZhaoxiTokens.Hues 生成值） */
fun ambFor(hue: ZhaoxiHue, dark: Boolean): Pair<Color, Color> = when (hue) {
    ZhaoxiHue.STONE -> if (dark) Color(0x38938D89) to Color(0x29767E8C) else Color(0xE0D5CDC4) to Color(0xB8C6D1D6)
    ZhaoxiHue.SAGE  -> if (dark) Color(0x3D7C9483) to Color(0x296C766C) else Color(0xE6CCDBCE) to Color(0xBDD7DDCE)
    ZhaoxiHue.STEEL -> if (dark) Color(0x426E88A0) to Color(0x2E626C82) else Color(0xE6C9D7E4) to Color(0xBDD5D6E2)
    ZhaoxiHue.MAUVE -> if (dark) Color(0x3D967F95) to Color(0x2980768C) else Color(0xE6DFD3DF) to Color(0xBDDBD5E4)
    ZhaoxiHue.DUSK  -> if (dark) Color(0x3DA08A76) to Color(0x298A7B67) else Color(0xE6E5D7C7) to Color(0xBDE1D1C3)
}

// ---------------------------------------------------------------------------
// 3. 字体 / 形状 / 高程
// ---------------------------------------------------------------------------

@Immutable
data class ZhaoxiType(
    // 正文/界面无衬线；数字与代码用 mono；日记/阅读标题可用 serif
    val display: androidx.compose.ui.text.TextStyle,   // 22/30 页面大标题
    val title: androidx.compose.ui.text.TextStyle,     // 17/24 区块标题
    val body: androidx.compose.ui.text.TextStyle,      // 15/24 正文
    val caption: androidx.compose.ui.text.TextStyle,   // 12.5/17 辅助
    val mono: androidx.compose.ui.text.TextStyle,      // 13/20 代码/数字
)

@Immutable
data class ZhaoxiShapes(
    val chip: Dp,     // 999 胶囊（用百分比圆角实现，此值仅文档占位）
    val card: Dp,     // 16 卡片
    val sheet: Dp,    // 24 Sheet 顶部
    val field: Dp,    // 12 输入框
    val msg: Dp,      // 18 用户消息块
) { companion object { val Default = ZhaoxiShapes(999.dp, 16.dp, 24.dp, 12.dp, 18.dp) } }

@Immutable
data class ZhaoxiElevation(
    val e1: Dp,  // 卡片（token --shadow 等效 tonal+spot）
    val e2: Dp,  // 悬浮 toast/菜单
    val e3: Dp,  // Sheet 上托（向上投影，用自绘 shadow 实现）
) { companion object { val Default = ZhaoxiElevation(2.dp, 8.dp, 16.dp) } }

// ---------------------------------------------------------------------------
// 4. 动效（唯一入口；reduced-motion 在此降级，组件不自行判断）
// ---------------------------------------------------------------------------

enum class ZhaoxiDuration(val ms: Int) { INSTANT(100), FAST(180), NORMAL(280), SLOW(320) }

object ZhaoxiMotion {
    /** 曲线与 tokens 一致：spring 开合回弹 / ease 位移 / easeOut 入场。 */
    val SpringSpec: FiniteAnimationSpec<Float> = spring(dampingRatio = 0.78f, stiffness = 380f)  // ≈ cubic-bezier(0.34,1.4,0.64,1)
    fun tween(d: ZhaoxiDuration, easing: Easing = EaseStandard): TweenSpec<Float> = tween(d.ms, easing = easing)
    val EaseStandard: Easing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)
    val EaseOut: Easing = CubicBezierEasing(0f, 0f, 0.2f, 1f)

    /** 组件唯一动画入口。reduced-motion（无障碍/省电）返回瞬时 spec。 */
    @Composable
    fun <T> spec(d: ZhaoxiDuration, spring: Boolean = false): FiniteAnimationSpec<T> {
        val reduced = LocalReducedMotion.current
        @Suppress("UNCHECKED_CAST")
        return if (reduced) androidx.compose.animation.core.tween<Float>(1) as FiniteAnimationSpec<T>
        else if (spring) SpringSpec as FiniteAnimationSpec<T>
        else tween(d) as FiniteAnimationSpec<T>
    }
}

/** 由系统无障碍设置（animator duration scale / prefers-reduced-motion）注入。 */
val LocalReducedMotion = staticCompositionLocalOf { false }

// ---------------------------------------------------------------------------
// 5. 触感（对齐主线 rememberHapticTickFeedback / rememberHapticPulse）
// ---------------------------------------------------------------------------

enum class ZhaoxiHaptic { TICK, PULSE, SUCCESS, ERROR }
// TICK    档位滑杆过档
// PULSE   计划模式开关 / 审批点击
// SUCCESS 审批通过 / 生成成功（PerformHapticFeedback 无对应，用 View.performHapticFeedback CONFIRM，API30+ 降级 LONG_PRESS）
// ERROR   拒收回执 / 删除确认

// ---------------------------------------------------------------------------
// 6. Theme 入口
// ---------------------------------------------------------------------------

val LocalZhaoxiColors = staticCompositionLocalOf<ZhaoxiColors> { error("ZhaoxiTheme missing") }

object ZhaoxiTheme {
    val colors: ZhaoxiColors @Composable get() = LocalZhaoxiColors.current
    val shapes: ZhaoxiShapes @Composable get() = ZhaoxiShapes.Default
    val elevation: ZhaoxiElevation @Composable get() = ZhaoxiElevation.Default
}

@Composable
fun ZhaoxiTheme(
    hue: ZhaoxiHue = ZhaoxiHue.STONE,
    dark: Boolean = isSystemInDarkTheme(),
    reducedMotion: Boolean = false,
    transparency: Float = .5f,
    content: @Composable () -> Unit,
) {
    val base = zhaoxiColors(hue, dark)
    val level = transparency.coerceIn(0f, 1f)
    val colors = base.copy(
        glass = base.glass.copy(alpha = .95f - level * .20f),
        glass2 = base.glass2.copy(alpha = 1f - level * .12f),
    )
    CompositionLocalProvider(
        LocalZhaoxiColors provides colors,
        LocalReducedMotion provides reducedMotion,
    ) { content() }
}

// ---------------------------------------------------------------------------
// 7. 材质（唯一入口；模糊降级在此处理）
// ---------------------------------------------------------------------------

enum class ZhaoxiMaterial { M0, M1, M2, M3 }

/**
 * M0 纯色分层 / M1 云母（静态渐变，零模糊） / M2 轻玻璃 blur20 / M3 强玻璃 blur24+噪点。
 * 降级链：低内存/省电 → blur 半径减半；RenderEffect 不可用 → M2/M3 退化为 97% 不透明纯色。
 * 判定由 Host 注入 LocalZhaoxiBlurCapability，组件与 Pack 不自行探测。
 */
enum class ZhaoxiBlurCapability { FULL, REDUCED, NONE }
val LocalZhaoxiBlurCapability = staticCompositionLocalOf { ZhaoxiBlurCapability.FULL }

/**
 * 材质唯一入口（E8.16 实装）。
 * M0 纯色分层 / M1 云母（静态径向渐变，零模糊） / M2 轻玻璃 / M3 强玻璃+噪点。
 * 降级链：blur 不可用（NONE 或 API<31）→ M2/M3 退化为 97% 不透明纯色；
 * REDUCED → 模糊半径减半。判定由 Host 注入 LocalZhaoxiBlurCapability。
 * 内容不能使用 Modifier.blur（会把文字和控件一并模糊）；宿主未提供真正
 * 背景取样时使用可读的半透明材质，不虚构背景模糊成功。
 */
fun Modifier.zxMaterial(m: ZhaoxiMaterial): Modifier = composed {
    val c = ZhaoxiTheme.colors
    val capability = LocalZhaoxiBlurCapability.current
    val blurOk = capability != ZhaoxiBlurCapability.NONE &&
        android.os.Build.VERSION.SDK_INT >= 31
    when (m) {
        ZhaoxiMaterial.M0 -> this.background(c.bg2)
        ZhaoxiMaterial.M1 -> this.background(
            androidx.compose.ui.graphics.Brush.radialGradient(listOf(c.micaA, c.micaB)))
        ZhaoxiMaterial.M2 ->
            if (blurOk) this.background(c.glass)
            else this.background(c.glass2.copy(alpha = 0.97f))
        ZhaoxiMaterial.M3 -> {
            val base = if (blurOk) this.background(c.glass2)
            else this.background(c.glass2.copy(alpha = 0.97f))
            base.drawBehind {
                // 噪点：确定性伪随机点阵，alpha 0.022（免 Bitmap 资源）
                var seed = 0x2F6E2B1
                fun rnd(): Float { seed = seed * 1103515245 + 12345; return (seed ushr 16) / 65535f }
                val step = 6.dp.toPx()
                var y = 0f
                while (y < size.height) {
                    var x = 0f
                    while (x < size.width) {
                        if (rnd() < 0.5f)
                            drawRect(c.ink, androidx.compose.ui.geometry.Offset(x + rnd() * step, y + rnd() * step),
                                androidx.compose.ui.geometry.Size(1f, 1f), alpha = 0.022f)
                        x += step
                    }
                    y += step
                }
            }
        }
    }
}

/** Brightness-calibrated veil follows the E8.18 wallpaper rule, without blurring foreground text. */
internal fun wallpaperVeilAlpha(luminance: Float, lightMode: Boolean, adaptive: Boolean): Float =
    if (!adaptive) 0.36f else (
        0.55f + ((if (lightMode) 0.5f - luminance else luminance - 0.5f) * 0.55f)
    ).coerceIn(0.38f, 0.88f)

/**
 * E8.18 可选内置壁纸：十张高保真原稿 WebP 归档到 assets；
 * 默认不开启，启用后按五主题×明暗确定唯一资产。
 */
@Composable
fun ZhaoxiBackground(
    modifier: Modifier = Modifier,
    intensity: Float = 1f,
    workMode: Boolean = false,
    wallpaperPreset: String? = null,
    adaptiveContrast: Boolean = true,
) {
    val c = ZhaoxiTheme.colors
    val context = androidx.compose.ui.platform.LocalContext.current
    val wallpaper by produceState<Pair<androidx.compose.ui.graphics.ImageBitmap, Float>?>(null, wallpaperPreset) {
        value = if (wallpaperPreset == null) null else
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                context.assets.open("e818_wallpapers.zip").use { stream ->
                    java.util.zip.ZipInputStream(stream).use { zip ->
                        var candidate: java.util.zip.ZipEntry?
                        var decoded: Pair<androidx.compose.ui.graphics.ImageBitmap, Float>? = null
                        while (zip.nextEntry.also { candidate = it } != null) {
                            if (candidate?.name == wallpaperPreset) {
                                val bytes = zip.readBytes()
                                decoded = android.graphics.BitmapFactory.decodeByteArray(
                                    bytes, 0, bytes.size)?.let { bitmap ->
                                    val stepX = (bitmap.width / 9).coerceAtLeast(1)
                                    val stepY = (bitmap.height / 9).coerceAtLeast(1)
                                    var luminance = 0f
                                    var count = 0
                                    for (y in 0 until bitmap.height step stepY) {
                                        for (x in 0 until bitmap.width step stepX) {
                                            val pixel = bitmap.getPixel(x, y)
                                            luminance += (0.2126f * android.graphics.Color.red(pixel) +
                                                0.7152f * android.graphics.Color.green(pixel) +
                                                0.0722f * android.graphics.Color.blue(pixel)) / 255f
                                            count++
                                        }
                                    }
                                    bitmap.asImageBitmap() to luminance / count
                                }
                                break
                            }
                        }
                        decoded
                    }
                }
            }
    }
    val currentWallpaper = wallpaper
    val glow = intensity.coerceIn(0f, 1f) * (if (currentWallpaper != null) 0.16f else 1f)
    androidx.compose.foundation.layout.Box(modifier) {
        currentWallpaper?.let {
            androidx.compose.foundation.Image(
                bitmap = it.first,
                contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().drawBehind {
            if (currentWallpaper == null) {
                drawRect(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(c.bg, c.bg2)))
            } else {
                // Only the surface is veiled; controls and text above this layer remain sharp.
                drawRect(c.bg.copy(alpha = wallpaperVeilAlpha(
                    currentWallpaper.second, c.bg.red > 0.5f, adaptiveContrast)))
            }
            if (glow > 0f) {
                drawRect(androidx.compose.ui.graphics.Brush.radialGradient(
                    listOf(c.ambA.copy(alpha = c.ambA.alpha * glow), Color.Transparent),
                    center = if (workMode) androidx.compose.ui.geometry.Offset(size.width * .84f, 0f)
                        else androidx.compose.ui.geometry.Offset(size.width * .22f, size.height * .06f),
                    radius = size.maxDimension * .78f))
                drawRect(androidx.compose.ui.graphics.Brush.radialGradient(
                    listOf(c.ambB.copy(alpha = c.ambB.alpha * glow), Color.Transparent),
                    center = if (workMode) androidx.compose.ui.geometry.Offset(size.width * .1f, size.height * .98f)
                        else androidx.compose.ui.geometry.Offset(size.width * .85f, size.height * .98f),
                    radius = size.maxDimension * .78f))
            }
        })
    }
}

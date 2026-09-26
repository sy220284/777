package com.labteto.dshmobile.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * 中国风传统色卡：用户可自选的 accent 主题。
 *
 * 每张卡一对主色——亮色主题取深档（对应青瓷 Celadon600），暗色主题取浅档（对应 Celadon400），
 * 保证任何卡在两种主题下都落在既定的对比度带上。色名是专有名词，保留汉字不做翻译——
 * 这是这个功能的审美本体，不是待本地化的文案。
 */
data class AccentPalette(
    val key: String,
    val cnName: String,
    val lightAccent: Color,
    val darkAccent: Color,
    val onLight: Color = Color(0xFFFFFFFF),
    val onDark: Color = Color(0xFF0C1A19),
)

object AccentPalettes {

    /** 青瓷为默认（与设计系统同源），其余五张为可选换装。 */
    val ALL = listOf(
        AccentPalette("celadon", "青瓷", Color(0xFF3E8E8C), Color(0xFF63B3AC)),
        AccentPalette("dailan", "黛蓝", Color(0xFF44618C), Color(0xFF8FB0D9), onDark = Color(0xFF0D1520)),
        AccentPalette("zhusha", "朱砂", Color(0xFFA64B44), Color(0xFFDE8B84), onDark = Color(0xFF1C0D0C)),
        AccentPalette("xianghuang", "缃黄", Color(0xFFA67C32), Color(0xFFD9BC7A), onDark = Color(0xFF1A150A)),
        AccentPalette("zitang", "紫棠", Color(0xFF7458A8), Color(0xFFB3A0DC), onDark = Color(0xFF150F20)),
        AccentPalette("yanzhi", "胭脂", Color(0xFFA34A5E), Color(0xFFDB8B99), onDark = Color(0xFF1F0D11)),
    )

    val DEFAULT = ALL.first()

    fun of(key: String?): AccentPalette = ALL.firstOrNull { it.key == key } ?: DEFAULT
}

/**
 * 把选中的色卡覆盖进语义色：accent 家族整体换血，其余语义（文字/画布/成功/警示）原样保留。
 * 换色面刻意收窄——整页只有交互色变，Functional 族与 Family 容器色不跟卡走，页面不会花。
 */
fun DsColors.withAccent(palette: AccentPalette, dark: Boolean): DsColors {
    val accent = if (dark) palette.darkAccent else palette.lightAccent
    val on = if (dark) palette.onDark else palette.onLight
    val tertiary = if (dark) accent.copy(alpha = 0.16f) else accent.copy(alpha = 0.12f)
    val hover = accent.copy(alpha = 0.85f)
    return copy(
        accent = accent,
        onAccent = on,
        accentTertiary = tertiary,
        accentHover = hover,
        buttonInfoFill = accent,
        buttonInfoHover = hover,
        userBubble = if (dark) userBubble else accent.copy(alpha = 0.10f),
        sidebarNavAccent = if (dark) sidebarNavAccent else accent.copy(alpha = 0.10f),
    )
}

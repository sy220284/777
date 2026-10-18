package com.labteto.dshmobile.uipack.zhaoxi.theme

/** Keys map 1:1 to the ten bundled E8.18 artwork entries. */
internal data class ZhaoxiWallpaperPreset(val key: String, val label: String)
internal val zhaoxiWallpaperPresets = listOf(
    ZhaoxiWallpaperPreset("w0.webp", "暮橙 · 浅"),
    ZhaoxiWallpaperPreset("w1.webp", "暮橙 · 深"),
    ZhaoxiWallpaperPreset("w2.webp", "石灰 · 浅"),
    ZhaoxiWallpaperPreset("w3.webp", "石灰 · 深"),
    ZhaoxiWallpaperPreset("w4.webp", "鼠尾草 · 浅"),
    ZhaoxiWallpaperPreset("w5.webp", "鼠尾草 · 深"),
    ZhaoxiWallpaperPreset("w6.webp", "藕紫 · 浅"),
    ZhaoxiWallpaperPreset("w7.webp", "藕紫 · 深"),
    ZhaoxiWallpaperPreset("w8.webp", "钢蓝 · 浅"),
    ZhaoxiWallpaperPreset("w9.webp", "钢蓝 · 深"),
)

/** An explicit choice stays fixed across theme changes; only auto follows hue and mode. */
internal fun resolveZhaoxiWallpaper(
    enabled: Boolean, selected: String, hue: ZhaoxiHue, dark: Boolean,
): String? {
    if (!enabled) return null
    if (selected != "auto") return selected
    val lightIndex = when (hue) {
        ZhaoxiHue.DUSK -> 0
        ZhaoxiHue.STONE -> 2
        ZhaoxiHue.SAGE -> 4
        ZhaoxiHue.MAUVE -> 6
        ZhaoxiHue.STEEL -> 8
    }
    return "w${lightIndex + if (dark) 1 else 0}.webp"
}

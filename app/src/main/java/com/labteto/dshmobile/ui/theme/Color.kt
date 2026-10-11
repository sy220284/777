package com.labteto.dshmobile.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Fluid-glass v1.4 semantic aliases. Primitive blue/neutral scales remain available
 * for explicit appearance presets and specialist meters.
 */

// ---- Primitive scales (identical in light & dark) ---------------------------

object Ds {
    // Neutral-bluish neutral scale retained for active semantic colors.
    val Bluish50 = Color(0xFFF9FAF9)
    val Bluish75 = Color(0xFFF0F3F1)
    val Bluish100 = Color(0xFFEAEFEC)
    val Bluish600 = Color(0xFF818B87)
    val Bluish750 = Color(0xFF424A47)
    val Bluish800 = Color(0xFF2C3836)
    val Bluish850 = Color(0xFF242F2D)
    val Bluish875 = Color(0xFF1E2826)
    val Bluish900 = Color(0xFF182120)
    val Bluish1000 = Color(0xFF0F1514)

    // 默认交互蓝. The UI itself stays monochrome; blue is reserved for selection,
    // links, focus and primary interactive state.
    val AccentBlue100 = Color(0xFFE8F2FF)
    val AccentBlue400 = Color(0xFF5A9BFF)
    val AccentBlue500 = Color(0xFF356BFD)
    val AccentBlue600 = Color(0xFF1783FF)
    val AccentBlue900 = Color(0xFF132F57)

    // Semantic status colors
    val Green100 = Color(0xFFE6F4EC)
    val Green400 = Color(0xFF5BAF85)
    val Green500 = Color(0xFF3F9A6E)
    val Green900 = Color(0xFF233C2C)
    val Red50 = Color(0xFFFBF1F0)
    val Red400 = Color(0xFFDE7A72)
    val Red600 = Color(0xFFC0443C)
    val Red900 = Color(0xFF571916)
    val Amber100 = Color(0xFFFBF3E4)
    val Amber400 = Color(0xFFDBA45B)
    val Amber500 = Color(0xFFC89041)
    val Amber600 = Color(0xFFB07E33)
    val Amber900 = Color(0xFF27241F)

    // Context meter tints
    val MeterSystem = Color(0xFFADB2B8)
    val MeterTools = Color(0xFFA78BFA)
    val MeterMessages = Color(0xFF4D93F8)
}

/** Semantic alias tokens for the light theme. */
object DsLight {
    val bgBase = Color(0xFFEDE6DA)
    val bgLayer1 = Color.White
    val bgLayer2 = Color(0xFFF5F0E8)
    val bgLayer3 = Color(0xFFFAF7F2)
    val bgModulePlatform = Color(0xFFF5F0E8)
    val borderL1 = Color(0x66FFFFFF)
    val borderL2 = Color(0x1F9C9286)
    val borderL3 = Color(0x389C9286)
    val brandPrimary = Color(0xFFE8A87C)
    val onBrandPrimary = Color(0xFF3A342D)
    val labelPrimary = Color(0xFF3A342D)
    val labelSecondary = Color(0xFF6F675D)
    // Small metadata uses the stronger ink; ink3 is reserved for disabled/large text.
    val labelTertiary = Color(0xFF746A5E)
    val labelCaption = Color(0xFF746A5E)
    val labelDimmed = Color(0xFFBEB4A7)
    val accent = Color(0xFFD9926B)
    val onAccent = Color(0xFF3A342D)
    val accentTertiary = Color(0xFFF2C7A8)
    val accentHover = Color(0xFFD9926B)
    val hover = Color(0x10E8A87C)
    val hoverSolid = Color(0xFFF5EADC)
    val hoverAccent = Color(0x30E8A87C)
    val active = Color(0x26E8A87C)
    val dangerHover = Color(0x18D9705F)
    val buttonPrimaryHover = Color(0xFFD9926B)
    val buttonPrimaryDimmed = Color(0xFFF2C7A8)
    val buttonInfoFill = Color(0xFFF2C7A8)
    val buttonInfoHover = Color(0xFFD9926B)
    val error = Color(0xFFAA4435)
    val errorSecondary = Color(0xFFD9705F)
    val errorTertiary = Color(0xFFF9E4DE)
    val success = Color(0xFF407B66)
    val successSecondary = Color(0xFF7FBFA8)
    val successTertiary = Color(0xFFE2F0E9)
    val warnLabel = Color(0xFF8C5B22)
    val warn = Color(0xFFE0A458)
    val warnSecondary = Color(0xFFE0A458)
    val warnTertiary = Color(0xFFF8EDD9)
    val toastBg = Color(0xFF3A342D)
    val tooltipBg = Color(0xFF3A342D)
    val userBubble = Color(0xFFF2C7A8)
    val userBubbleHighlight = Color(0xFFE8B48E)
    val composerCard = Color.White
    val sidebar = Color(0xFFF5F0E8)
    val sidebarNavActive = Color(0xB3FFFFFF)
    val sidebarNavAccent = Color(0x40E8A87C)
    val sidebarNavHover = Color(0x66FFFFFF)
    val tipSurface = Color(0xFFF5F0E8)
    val codeBlockBg = Color(0xFFF5F0E8)
    val codeBlockBanner = Color(0xFFEDE6DA)
    val inlineCode = Color(0xFFEDE6DA)
    val citation = Color(0xFFE3D7C6)
    val markdownTag = Color(0xFFF5F0E8)
    val overlayMask = Color(0x3D3A342D)
}

/** Semantic alias tokens for the dark theme. */
object DsDark {
    val bgBase = Color(0xFF24211E)
    val bgLayer1 = Color(0xFF2E2B28)
    val bgLayer2 = Color(0xFF35312D)
    val bgLayer3 = Color(0xFF403A34)
    val bgModulePlatform = Color(0xFF35312D)
    val borderL1 = Color(0x0FFFFFFF) // rgba(255,255,255,.06)
    val borderL2 = Color(0x1FFFFFFF) // rgba(255,255,255,.12)
    val borderL3 = Color(0x29FFFFFF) // rgba(255,255,255,.16)
    val brandPrimary = Color(0xFFE8A87C)
    val onBrandPrimary = Color(0xFF3A342D)
    val labelPrimary = Color(0xFFF5F0E8)
    val labelSecondary = Color(0xFFD9D0C5)
    val labelTertiary = Color(0xFFB8AB9B)
    val labelCaption = Color(0xFFB8AB9B)
    val labelDimmed = Ds.Bluish750
    val accent = Color(0xFFE8A87C)
    val onAccent = Color(0xFF0C1A19)
    val accentTertiary = Color(0xFF5B4032)
    val accentHover = Color(0xFFD9926B)
    val hover = Color(0x14FFFFFF) // rgba(255,255,255,.08)
    val hoverSolid = Ds.Bluish850
    val hoverAccent = Color(0x3DFFFFFF) // rgba(255,255,255,.24)
    val active = Color(0x24FFFFFF) // rgba(255,255,255,.14)
    val dangerHover = Color(0x26DE7A72) // rgba(222,122,114,.15)
    val buttonPrimaryHover = Ds.Bluish100
    val buttonPrimaryDimmed = Ds.Bluish750
    val buttonInfoFill = Color(0xFFE8A87C)
    val buttonInfoHover = Color(0xFFD9926B)
    val error = Ds.Red400
    val errorSecondary = Ds.Red400
    val errorTertiary = Ds.Red900
    val success = Color(0xFF7FBFA8)
    val successSecondary = Color(0xFF69AE97)
    val successTertiary = Ds.Green900
    val warnLabel = Ds.Amber600
    val warn = Ds.Amber500
    val warnSecondary = Ds.Amber400
    val warnTertiary = Ds.Amber900
    val toastBg = Ds.Bluish750
    val tooltipBg = Ds.Bluish750
    val userBubble = Color(0xFF2C2C2E)
    val userBubbleHighlight = Color(0xFF38383A)
    val composerCard = Color(0xFF35312D)
    val sidebar = Color(0xFF2E2B28)
    val sidebarNavActive = Color(0xFF2A2A2A)
    val sidebarNavAccent = Ds.Bluish800
    val sidebarNavHover = Color(0xFF222220)
    val tipSurface = Ds.Bluish800
    val codeBlockBg = Color(0xFF202020)
    val codeBlockBanner = Color(0xFF2B2B2B)
    val inlineCode = Color(0xFF303030)
    val citation = Color(0xFF636363)
    val markdownTag = Color(0xFF403A34)
    val overlayMask = Color(0x80000000) // rgba(0,0,0,.5)
}

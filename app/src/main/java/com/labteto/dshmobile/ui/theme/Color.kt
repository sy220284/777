package com.labteto.dshmobile.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Fluid-glass v1.4 semantic aliases. Historical primitive scales remain available
 * for explicit appearance presets and specialist meters.
 */

// ---- Primitive scales (identical in light & dark) ---------------------------

object SyPrimitiveColors {
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

    // Historical opt-in accents remain available for existing user preferences;
    // production defaults come from the coordinated Shenyan palette instead.
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
    val MeterTools = Color(0xFF9B9C56)
    val MeterMessages = Color(0xFFD17D57)
}

/** Semantic alias tokens for the light theme. */
object SyHostLight {
    val bgBase = Color(0xFFFCFCFB)
    val bgLayer1 = Color.White
    val bgLayer2 = Color(0xFFF8F8F7)
    val bgLayer3 = Color.White
    val bgModulePlatform = Color(0xFFF8F8F7)
    val borderL1 = Color(0x14000000)
    val borderL2 = Color(0x24000000)
    val borderL3 = Color(0x38000000)
    val brandPrimary = Color(0xFFE8A87C)
    val onBrandPrimary = Color.Black
    val labelPrimary = Color.Black
    val labelSecondary = Color(0xFF353535)
    val labelTertiary = Color(0xFF595959)
    val labelCaption = Color(0xFF595959)
    val labelDimmed = Color(0xFF777777)
    val accent = Color(0xFFD9926B)
    val onAccent = Color.Black
    val accentTertiary = Color(0xFFF9EDE5)
    val accentHover = Color(0xFFD9926B)
    val hover = Color(0x0DE8A87C)
    val hoverSolid = Color(0xFFF5F5F3)
    val hoverAccent = Color(0x26E8A87C)
    val active = Color(0x1FE8A87C)
    val dangerHover = Color(0x18D9705F)
    val buttonPrimaryHover = Color(0xFFD9926B)
    val buttonPrimaryDimmed = Color(0xFFF9EDE5)
    val buttonInfoFill = Color(0xFFE8A87C)
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
    val progressFailedStart = Color(0xFFC98B6B)
    val progressFailedEnd = Color(0xFF9C6247)
    val toastBg = Color(0xFF202020)
    val tooltipBg = Color(0xFF202020)
    val userBubble = Color(0xFFF9F4F0)
    val userBubbleHighlight = Color(0xFFF5EBE4)
    val composerCard = Color.White
    val sidebar = Color.White
    val sidebarNavActive = Color(0xFFF9EDE5)
    val sidebarNavAccent = Color(0x24E8A87C)
    val sidebarNavHover = Color(0xFFF5F5F3)
    val tipSurface = Color.White
    val codeBlockBg = Color(0xFFF5F5F3)
    val codeBlockBanner = Color(0xFFEDEDEB)
    val inlineCode = Color(0xFFF2F2F0)
    val citation = Color(0xFFEDEDEB)
    val markdownTag = Color(0xFFF5F5F3)
    val overlayMask = Color(0x33000000)
}

/** Semantic aliases for the retained matte-black theme. */
object SyHostMatte {
    val bgBase = Color(0xFF0F0F10)
    val bgLayer1 = Color(0xFF191918)
    val bgLayer2 = Color(0xFF222220)
    val bgLayer3 = Color(0xFF2B2B28)
    val bgModulePlatform = Color(0xFF222220)
    val borderL1 = Color(0x0FF3F0E8)
    val borderL2 = Color(0x1FF3F0E8)
    val borderL3 = Color(0x29F3F0E8)
    val brandPrimary = Color(0xFFF3F0E8)
    val onBrandPrimary = Color(0xFF10100F)
    val labelPrimary = Color(0xFFF4F3EF)
    val labelSecondary = Color(0xFFD5D1C9)
    val labelTertiary = Color(0xFFAAA69E)
    val labelCaption = Color(0xFF7F7C75)
    val labelDimmed = Color(0xFF5B5954)
    val accent = Color(0xFFE8A87C)
    val onAccent = Color(0xFF3A342D)
    val accentTertiary = Color(0xFF5B4032)
    val accentHover = Color(0xFFD9926B)
    val hover = Color(0x14FFFFFF) // rgba(255,255,255,.08)
    val hoverSolid = Color(0xFF242421)
    val hoverAccent = Color(0x3DFFFFFF) // rgba(255,255,255,.24)
    val active = Color(0x24FFFFFF) // rgba(255,255,255,.14)
    val dangerHover = Color(0x26DE7A72) // rgba(222,122,114,.15)
    val buttonPrimaryHover = Color(0xFFD5D1C9)
    val buttonPrimaryDimmed = Color(0xFF4B4944)
    val buttonInfoFill = Color(0xFFE8A87C)
    val buttonInfoHover = Color(0xFFD9926B)
    val error = SyPrimitiveColors.Red400
    val errorSecondary = SyPrimitiveColors.Red400
    val errorTertiary = SyPrimitiveColors.Red900
    val success = Color(0xFF7FBFA8)
    val successSecondary = Color(0xFF69AE97)
    val successTertiary = SyPrimitiveColors.Green900
    val warnLabel = SyPrimitiveColors.Amber600
    val warn = SyPrimitiveColors.Amber500
    val warnSecondary = SyPrimitiveColors.Amber400
    val warnTertiary = SyPrimitiveColors.Amber900
    // Progress failure is muted clay from the Shenyan visual specification, not danger red.
    val progressFailedStart = Color(0xFFC98B6B)
    val progressFailedEnd = Color(0xFF9C6247)
    val toastBg = Color(0xFF363431)
    val tooltipBg = Color(0xFF363431)
    val userBubble = Color(0xFF222220)
    val userBubbleHighlight = Color(0xFF363431)
    val composerCard = Color(0xFF222220)
    val sidebar = Color(0xFF151514)
    val sidebarNavActive = Color(0xFF363431)
    val sidebarNavAccent = Color(0xFF2D2C29)
    val sidebarNavHover = Color(0xFF222220)
    val tipSurface = Color(0xFF2D2C29)
    val codeBlockBg = Color(0xFF181816)
    val codeBlockBanner = Color(0xFF1B1B19)
    val inlineCode = Color(0xFF222220)
    val citation = Color(0xFF2D2C29)
    val markdownTag = Color(0xFF222220)
    val overlayMask = Color(0x80000000) // rgba(0,0,0,.5)
}

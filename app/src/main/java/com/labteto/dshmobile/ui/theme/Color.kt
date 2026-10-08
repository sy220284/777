package com.labteto.dshmobile.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * 当前默认 surfaces with retained optional accent palettes; originally ported from
 * packages/client/ui-theme/src/styles/design-platform.css (harness repo).
 * Static primitive scales + semantic alias tokens for light and dark.
 */

// ---- Primitive scales (identical in light & dark) ---------------------------

object Ds {
    // Neutral-bluish UI scale. Dark stops carry a faint green cast (墨) so surfaces sit
    // comfortably under the celadon accent; light stops pick up the same paper cast.
    val Bluish00 = Color(0xFFFFFFFF)
    val Bluish50 = Color(0xFFF9FAF9)
    val Bluish60 = Color(0xFFF5F7F6)
    val Bluish75 = Color(0xFFF0F3F1)
    val Bluish100 = Color(0xFFEAEFEC)
    val Bluish150 = Color(0xFFE8EDEA)
    val Bluish200 = Color(0xFFE0E6E2)
    val Bluish300 = Color(0xFFCFD6D2)
    val Bluish400 = Color(0xFFADB6B1)
    val Bluish500 = Color(0xFF97A19C)
    val Bluish600 = Color(0xFF818B87)
    val Bluish700 = Color(0xFF616B67)
    val Bluish750 = Color(0xFF424A47)
    val Bluish800 = Color(0xFF2C3836)
    val Bluish850 = Color(0xFF242F2D)
    val Bluish875 = Color(0xFF1E2826)
    val Bluish900 = Color(0xFF182120)
    val Bluish950 = Color(0xFF141A19)
    val Bluish1000 = Color(0xFF0F1514)

    // Legacy celadon scale remains available for saved appearance presets.
    val Celadon50 = Color(0xFFEDF6F5)
    val Celadon100 = Color(0xFFDCEEEC)
    val Celadon200 = Color(0xFFBFE0DD)
    val Celadon300 = Color(0xFF93CCC7)
    val Celadon400 = Color(0xFF63B3AC)
    val Celadon500 = Color(0xFF4AA39C)
    val Celadon600 = Color(0xFF3E8E8C)
    val Celadon700 = Color(0xFF35726F)
    val Celadon800 = Color(0xFF2E5A58)
    val Celadon900 = Color(0xFF28403F)

    // Function-family hues for icon containers and badges (muted, 同一饱和度带).
    val FamilyPurple = Color(0xFF9B8EC9)
    val FamilyCyan = Color(0xFF6FA3C2)
    val FamilyPurpleDeep = Color(0xFF4A4370)
    val FamilyCyanDeep = Color(0xFF3D5872)

    // 默认交互蓝. The UI itself stays monochrome; blue is reserved for selection,
    // links, focus and primary interactive state.
    val AccentBlue50 = Color(0xFFF2F7FF)
    val AccentBlue100 = Color(0xFFE8F2FF)
    val AccentBlue200 = Color(0xFFCFE3FF)
    val AccentBlue400 = Color(0xFF5A9BFF)
    val AccentBlue500 = Color(0xFF356BFD)
    val AccentBlue600 = Color(0xFF1783FF)
    val AccentBlue700 = Color(0xFF126AD1)
    val AccentBlue900 = Color(0xFF132F57)

    // DeepSeek reference scale kept for compatibility with existing saved settings.
    val Deepseek50 = Color(0xFFEDF3FE)
    val Deepseek100 = Color(0xFFE4EDFD)
    val Deepseek200 = Color(0xFFD3E2FF)
    val Deepseek300 = Color(0xFFB7C8FE)
    val Deepseek400 = Color(0xFF679EFE)
    val Deepseek450 = Color(0xFF5686FE)
    val Deepseek500 = Color(0xFF4176E6)
    val Deepseek600 = Color(0xFF4868B2)
    val Deepseek800 = Color(0xFF34415B)
    val Deepseek900 = Color(0xFF283142)

    // Semantic (muted to the same satiation band as the celadon accent)
    val Green100 = Color(0xFFE6F4EC)
    val Green400 = Color(0xFF5BAF85)
    val Green500 = Color(0xFF3F9A6E)
    val Green900 = Color(0xFF233C2C)
    val Red50 = Color(0xFFFBF1F0)
    val Red100 = Color(0xFFF8E2E0)
    val Red400 = Color(0xFFDE7A72)
    val Red500 = Color(0xFFC94A42)
    val Red600 = Color(0xFFC0443C)
    val Red900 = Color(0xFF571916)
    val Amber100 = Color(0xFFFBF3E4)
    val Amber400 = Color(0xFFDBA45B)
    val Amber500 = Color(0xFFC89041)
    val Amber600 = Color(0xFFB07E33)
    val Amber900 = Color(0xFF27241F)

    // Syntax colors (shiki.css, light theme)
    val SyntaxConstant = Color(0xFF1C7ED6)
    val SyntaxString = Color(0xFF2F9E44)
    val SyntaxComment = Color(0xFF868E96)
    val SyntaxKeyword = Color(0xFFD6336C)
    val SyntaxParameter = Color(0xFFE8590C)
    val SyntaxFunction = Color(0xFF6741D9)
    val SyntaxPunctuation = Color(0xFF495057)
    val SyntaxLink = Color(0xFF1971C2)

    // Context meter tints
    val MeterSystem = Color(0xFFADB2B8)
    val MeterTools = Color(0xFFA78BFA)
    val MeterMessages = Color(0xFF4D93F8)
}

/** Semantic alias tokens for the light theme. */
object DsLight {
    // Paper canvas with white floating surfaces, matching the mobile-first hierarchy used
    // across the refreshed home, drawer and settings screens.
    val bgBase = Color(0xFFF5F5F5)
    val bgLayer1 = Color(0xFFFFFFFF)
    val bgLayer2 = Color(0xFFFFFFFF)
    val bgLayer3 = Color(0xFFFFFFFF)
    val bgModulePlatform = Color(0xFFF5F5F5)
    val borderL1 = Color(0x08000000) // rgba(0,0,0,.03)
    val borderL2 = Color(0x12000000) // rgba(0,0,0,.07)
    val borderL3 = Color(0x1F000000) // rgba(0,0,0,.12)
    val brandPrimary = Color(0xFF191919) // ink button fill
    val onBrandPrimary = Color(0xFFFFFFFF)
    val labelPrimary = Color(0xFF121212)
    val labelSecondary = Color(0xFF353535)
    val labelTertiary = Color(0xFF8F909A)
    val labelCaption = Color(0xFFC6C6D0)
    val labelDimmed = Color(0xFFE2E2E6)
    val accent = Ds.AccentBlue600
    val onAccent = Color(0xFFFFFFFF)
    val accentTertiary = Ds.AccentBlue100
    val accentHover = Ds.AccentBlue500
    val hover = Color(0x0F1E3230) // rgba(30,50,48,.06)
    val hoverSolid = Color(0xFFF1F1EF)
    val hoverAccent = Color(0x241E3230) // rgba(30,50,48,.14)
    val active = Color(0x1A1E3230) // rgba(30,50,48,.10)
    val dangerHover = Color(0x0DEC1313) // rgba(236,19,19,.05)
    val buttonPrimaryHover = Ds.Bluish750
    val buttonPrimaryDimmed = Ds.Bluish100
    val buttonInfoFill = Ds.AccentBlue600
    val buttonInfoHover = Ds.AccentBlue500
    val error = Ds.Red600
    val errorSecondary = Ds.Red400
    val errorTertiary = Ds.Red50
    val success = Ds.Green500
    val successSecondary = Ds.Green400
    val successTertiary = Ds.Green100
    val warnLabel = Ds.Amber600
    val warn = Ds.Amber500
    val warnSecondary = Ds.Amber400
    val warnTertiary = Ds.Amber100
    val toastBg = Ds.Bluish800
    val tooltipBg = Ds.Bluish850
    /**
     * One step darker than the harness's own `--dsw-specific-bubble` (`Celadon100`), and the one
     * deliberate divergence in this table.
     *
     * The web value is 1.06:1 against the white transcript — legible there only because the bubble
     * is a wide pill in a 748px column on a desk monitor. At phone size and phone brightness the
     * shape stopped reading, and a message you cannot tell apart from the assistant's is a worse
     * failure than a fill that is a shade off the reference.
     */
    val userBubble = Color(0xFFF5F5F5)
    val userBubbleHighlight = Color(0xFFEDEDED)
    val composerCard = Color(0xFFFFFFFF)
    val sidebar = Color(0xFFF5F5F5)
    val sidebarNavActive = Color(0xFFECECEC)
    val sidebarNavAccent = Ds.AccentBlue100
    val sidebarNavHover = Color(0xFFF1F1F1)
    val tipSurface = Color(0xFFF5F5F5)
    val codeBlockBg = Ds.Bluish50
    val codeBlockBanner = Ds.Bluish50
    val inlineCode = Ds.Bluish100
    val citation = Ds.Bluish100
    val markdownTag = Ds.Bluish75
    val overlayMask = Color(0x3D000000) // rgba(0,0,0,.24)
}

/** Semantic alias tokens for the dark theme. */
object DsDark {
    val bgBase = Color(0xFF121212)
    val bgLayer1 = Color(0xFF1A1A1A)
    val bgLayer2 = Color(0xFF1F1F1F)
    val bgLayer3 = Color(0xFF282828)
    val bgModulePlatform = Color(0xFF242424)
    val borderL1 = Color(0x0FFFFFFF) // rgba(255,255,255,.06)
    val borderL2 = Color(0x1FFFFFFF) // rgba(255,255,255,.12)
    val borderL3 = Color(0x29FFFFFF) // rgba(255,255,255,.16)
    val brandPrimary = Ds.Bluish50 // inverted ink button fill
    val onBrandPrimary = Ds.Bluish1000
    val labelPrimary = Color(0xFFF5F5F5)
    val labelSecondary = Color(0xFFD5D5D5)
    val labelTertiary = Color(0xFF909096)
    val labelCaption = Ds.Bluish600
    val labelDimmed = Ds.Bluish750
    val accent = Ds.AccentBlue400
    val onAccent = Color(0xFF0C1A19)
    val accentTertiary = Ds.AccentBlue900
    val accentHover = Ds.AccentBlue500
    val hover = Color(0x14FFFFFF) // rgba(255,255,255,.08)
    val hoverSolid = Ds.Bluish850
    val hoverAccent = Color(0x3DFFFFFF) // rgba(255,255,255,.24)
    val active = Color(0x24FFFFFF) // rgba(255,255,255,.14)
    val dangerHover = Color(0x26DE7A72) // rgba(222,122,114,.15)
    val buttonPrimaryHover = Ds.Bluish100
    val buttonPrimaryDimmed = Ds.Bluish750
    val buttonInfoFill = Ds.AccentBlue400
    val buttonInfoHover = Ds.AccentBlue500
    val error = Ds.Red400
    val errorSecondary = Ds.Red400
    val errorTertiary = Ds.Red900
    val success = Ds.Green500
    val successSecondary = Ds.Green400
    val successTertiary = Ds.Green900
    val warnLabel = Ds.Amber600
    val warn = Ds.Amber500
    val warnSecondary = Ds.Amber400
    val warnTertiary = Ds.Amber900
    val toastBg = Ds.Bluish750
    val tooltipBg = Ds.Bluish750
    val userBubble = Color(0xFF2C2C2E)
    val userBubbleHighlight = Color(0xFF38383A)
    val composerCard = Color(0xFF1F1F1F)
    val sidebar = Color(0xFF171717)
    val sidebarNavActive = Color(0xFF2A2A2A)
    val sidebarNavAccent = Ds.Bluish800
    val sidebarNavHover = Color(0xFF222220)
    val tipSurface = Ds.Bluish800
    val codeBlockBg = Ds.Bluish900
    val codeBlockBanner = Ds.Bluish875
    val inlineCode = Ds.Bluish850
    val citation = Ds.Bluish800
    val markdownTag = Ds.Bluish850
    val overlayMask = Color(0x80000000) // rgba(0,0,0,.5)
}

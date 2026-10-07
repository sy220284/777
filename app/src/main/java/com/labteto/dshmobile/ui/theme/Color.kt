package com.labteto.dshmobile.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * 777 design tokens aligned to the Kimi 3.1.3 mobile UI contract.
 *
 * The neutral/semantic values mirror the APK's bundled widget foundation tokens so native
 * Compose screens, inline widgets and feature pages share one visual grammar. Legacy primitive
 * names are kept where changing them would churn call sites; their values follow the Kimi palette.
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

    // Historical accent-scale names are kept for source compatibility.
    // Values now follow Kimi 3.1.3 KMBlue so existing call sites inherit the new shell.
    val Celadon50 = Color(0xFFEDF5FF)
    val Celadon100 = Color(0xFFDCEBFF)
    val Celadon200 = Color(0xFFBAD8FF)
    val Celadon300 = Color(0xFF8EC0FF)
    val Celadon400 = Color(0xFF5BA5FF)
    val Celadon500 = Color(0xFF2D8EFF)
    val Celadon600 = Color(0xFF1783FF)
    val Celadon700 = Color(0xFF167FF7)
    val Celadon800 = Color(0xFF0B66D8)
    val Celadon900 = Color(0xFF084EA5)

    // Function-family hues for icon containers and badges (muted, 同一饱和度带).
    val FamilyPurple = Color(0xFF9B8EC9)
    val FamilyCyan = Color(0xFF6FA3C2)
    val FamilyPurpleDeep = Color(0xFF4A4370)
    val FamilyCyanDeep = Color(0xFF3D5872)

    // DeepSeek brand blue — kept as reference scale; no longer the interactive accent.
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
    val Green100 = Color(0x1A16C456)
    val Green400 = Color(0xFF45D47B)
    val Green500 = Color(0xFF16C456)
    val Green900 = Color(0xFF113321)
    val Red50 = Color(0x1AFF3849)
    val Red100 = Color(0x1AFF3849)
    val Red400 = Color(0xFFFF6B77)
    val Red500 = Color(0xFFFF4756)
    val Red600 = Color(0xFFFF3849)
    val Red900 = Color(0xFF4A151B)
    val Amber100 = Color(0x1AFF9500)
    val Amber400 = Color(0xFFFFB34D)
    val Amber500 = Color(0xFFFF9500)
    val Amber600 = Color(0xFFE68600)
    val Amber900 = Color(0xFF3B270B)

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
    // Kimi 3.1.3 light contract: white canvas, #f5f5f5 secondary surfaces and alpha-based labels.
    val bgBase = Color(0xFFFFFFFF)
    val bgLayer1 = Color(0xFFFFFFFF)
    val bgLayer2 = Color(0xFFFFFFFF)
    val bgLayer3 = Color(0xFFF5F5F5)
    val bgModulePlatform = Color(0xFFF5F5F5)
    val borderL1 = Color(0x08000000) // Fills-F1: 3%
    val borderL2 = Color(0x0D000000) // Fills-F2: 5%
    val borderL3 = Color(0x21000000) // Separators-S1: 13%
    val brandPrimary = Ds.Celadon600
    val onBrandPrimary = Color(0xFFFFFFFF)
    val labelPrimary = Color(0xE6000000) // 90%
    val labelSecondary = Color(0x99000000) // 60%
    val labelTertiary = Color(0x73000000) // 45%
    val labelCaption = Color(0x45000000) // 27%
    val labelDimmed = Color(0x26000000)
    val accent = Ds.Celadon600
    val onAccent = Color(0xFFFFFFFF)
    val accentTertiary = Color(0x1A1783FF)
    val accentHover = Ds.Celadon700
    val hover = Color(0x08000000)
    val hoverSolid = Color(0xFFF5F5F5)
    val hoverAccent = Color(0x0F000000)
    val active = Color(0x14000000)
    val dangerHover = Color(0x1AFF3849)
    val buttonPrimaryHover = Ds.Celadon700
    val buttonPrimaryDimmed = Color(0x1A1783FF)
    val buttonInfoFill = Color(0x0D000000)
    val buttonInfoHover = Color(0x14000000)
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
    val toastBg = Color(0xE6000000)
    val tooltipBg = Color(0xE6000000)
    val userBubble = Color(0xFFF5F5F5)
    val userBubbleHighlight = Color(0xFFEEEEEE)
    val composerCard = Color(0xFFFFFFFF)
    val sidebar = Color(0xFFFFFFFF)
    val sidebarNavActive = Color(0x0D000000)
    val sidebarNavAccent = Color(0x0D000000)
    val sidebarNavHover = Color(0x08000000)
    val tipSurface = Color(0xFFF5F5F5)
    val codeBlockBg = Color(0xFFF7F7F7)
    val codeBlockBanner = Color(0xFFF5F5F5)
    val inlineCode = Color(0x0D000000)
    val citation = Color(0x0D000000)
    val markdownTag = Color(0x08000000)
    val overlayMask = Color(0x4D000000)
}

/** Semantic alias tokens for the dark theme. */
object DsDark {
    val bgBase = Color(0xFF121212)
    val bgLayer1 = Color(0xFF121212)
    val bgLayer2 = Color(0xFF1F1F1F)
    val bgLayer3 = Color(0xFF1F1F1F)
    val bgModulePlatform = Color(0xFF1F1F1F)
    val borderL1 = Color(0x0DFFFFFF)
    val borderL2 = Color(0x1AFFFFFF)
    val borderL3 = Color(0x1FFFFFFF)
    val brandPrimary = Color(0xFF1A88FF)
    val onBrandPrimary = Color(0xFFFFFFFF)
    val labelPrimary = Color(0xD6FFFFFF) // 84%
    val labelSecondary = Color(0x8FFFFFFF) // 56%
    val labelTertiary = Color(0x6BFFFFFF) // 42%
    val labelCaption = Color(0x47FFFFFF) // 28%
    val labelDimmed = Color(0x33FFFFFF)
    val accent = Color(0xFF1A88FF)
    val onAccent = Color(0xFFFFFFFF)
    val accentTertiary = Color(0x1A1A88FF)
    val accentHover = Color(0xFF258EFF)
    val hover = Color(0x0DFFFFFF)
    val hoverSolid = Color(0xFF1F1F1F)
    val hoverAccent = Color(0x14FFFFFF)
    val active = Color(0x25FFFFFF)
    val dangerHover = Color(0x1AFF4756)
    val buttonPrimaryHover = Color(0xFF258EFF)
    val buttonPrimaryDimmed = Color(0x331A88FF)
    val buttonInfoFill = Color(0x1AFFFFFF)
    val buttonInfoHover = Color(0x25FFFFFF)
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
    val toastBg = Color(0xFF1F1F1F)
    val tooltipBg = Color(0xFF1F1F1F)
    val userBubble = Color(0xFF292929)
    val userBubbleHighlight = Color(0xFF333333)
    val composerCard = Color(0xFF1F1F1F)
    val sidebar = Color(0xFF121212)
    val sidebarNavActive = Color(0x14FFFFFF)
    val sidebarNavAccent = Color(0x14FFFFFF)
    val sidebarNavHover = Color(0x0DFFFFFF)
    val tipSurface = Color(0xFF1F1F1F)
    val codeBlockBg = Color(0xFF181818)
    val codeBlockBanner = Color(0xFF1F1F1F)
    val inlineCode = Color(0x1AFFFFFF)
    val citation = Color(0x1AFFFFFF)
    val markdownTag = Color(0x0DFFFFFF)
    val overlayMask = Color(0x99000000)
}

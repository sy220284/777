package com.labteto.dshmobile.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.labteto.dshmobile.R

/**
 * Shenyan typographic grammar: editorial titles, generous reading line-height and compact controls.
 * Reading preferences adjust weight without replacing the reference glyph families.
 */
object SyType {
    // Uploaded design: Outfit for Latin UI, existing CJK subsets for chrome, system fallback for prose.
    val uiFont = FontFamily(
        Font(R.font.fluid_outfit_400, FontWeight.Normal),
        Font(R.font.fluid_outfit_500, FontWeight.Medium),
        Font(R.font.fluid_outfit_600, FontWeight.SemiBold),
        Font(R.font.fluid_outfit_700, FontWeight.Bold),
        Font(R.font.fluid_outfit_800, FontWeight.ExtraBold),
        Font(R.font.ui_noto_sc_400, FontWeight.Normal),
        Font(R.font.ui_noto_sc_500, FontWeight.Medium),
        Font(R.font.ui_noto_sc_600, FontWeight.SemiBold),
        Font(R.font.ui_noto_sc_700, FontWeight.Bold),
        Font(R.font.ui_noto_sc_800, FontWeight.ExtraBold),
    )
    val contentFont = FontFamily(
        Font(R.font.fluid_outfit_400, FontWeight.Normal),
        Font(R.font.fluid_outfit_500, FontWeight.Medium),
        Font(R.font.fluid_outfit_600, FontWeight.SemiBold),
        Font(R.font.fluid_outfit_700, FontWeight.Bold),
        Font(R.font.fluid_outfit_800, FontWeight.ExtraBold),
    )
    /** Android's CJK-aware serif family; centralised so a packaged subset can replace it later. */
    val titleFont = FontFamily.Serif
    val codeFont = FontFamily(
        Font(R.font.fluid_mono_regular, FontWeight.Normal),
        Font(R.font.fluid_mono_italic, FontWeight.Normal, FontStyle.Italic),
    )

    // Markdown roles
    val mdH1 = TextStyle(fontFamily = contentFont, fontWeight = FontWeight.Bold, fontSize = 25.sp, lineHeight = 34.sp)
    val mdH2 = TextStyle(fontFamily = contentFont, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 31.sp)
    val mdH3 = TextStyle(fontFamily = contentFont, fontWeight = FontWeight.SemiBold, fontSize = 19.sp, lineHeight = 28.sp)
    val mdH4 = TextStyle(fontFamily = contentFont, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 26.sp)
    val mdBody = TextStyle(fontFamily = contentFont, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 25.sp)
    val workBody = TextStyle(fontFamily = contentFont, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 25.sp)
    val mdSmall = TextStyle(fontFamily = contentFont, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 23.sp)
    val mdCode = TextStyle(fontFamily = codeFont, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 22.sp)

    // UI roles
    // Clear Realm navigation hierarchy. General UI is sans-serif; serif stays literary-only.
    val largeTitle28 = TextStyle(fontFamily = uiFont, fontWeight = FontWeight.SemiBold, fontSize = 30.sp, lineHeight = 39.sp)
    val title22 = TextStyle(fontFamily = uiFont, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 30.sp)
    val headline17 = TextStyle(fontFamily = uiFont, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 24.sp)
    val display24 = TextStyle(fontFamily = uiFont, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 33.sp)
    val hero26 = TextStyle(fontFamily = uiFont, fontWeight = FontWeight.SemiBold, fontSize = 26.sp, lineHeight = 34.sp)
    val large20 = TextStyle(fontFamily = uiFont, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 28.sp)
    val titleSerif20 = TextStyle(fontFamily = titleFont, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 28.sp)
    val base16 = TextStyle(fontFamily = uiFont, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 26.sp)
    val base16Strong = TextStyle(fontFamily = uiFont, fontWeight = FontWeight.SemiBold, fontSize = 15.5.sp, lineHeight = 27.sp)
    val std14 = TextStyle(fontFamily = uiFont, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 23.sp)
    val std14Strong = TextStyle(fontFamily = uiFont, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 23.sp)
    val small13 = TextStyle(fontFamily = uiFont, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 21.sp)
    val small13Strong = TextStyle(fontFamily = uiFont, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 21.sp)
    val xsmall12 = TextStyle(fontFamily = uiFont, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 18.sp)
    val caption11 = TextStyle(fontFamily = uiFont, fontWeight = FontWeight.Normal, fontSize = 11.5.sp, lineHeight = 19.sp)
    val caption11Strong = TextStyle(fontFamily = uiFont, fontWeight = FontWeight.Medium, fontSize = 11.5.sp, lineHeight = 19.sp)

    // Navigation and settings use larger, stronger roles for comfortable viewing distance.
    val navigationItem = TextStyle(fontFamily = uiFont, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 23.sp)
    val navigationSupporting = TextStyle(fontFamily = uiFont, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 21.sp)
    val navigationSection = TextStyle(fontFamily = uiFont, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 21.sp)
    // Uploaded fluid-glass hierarchy: actionable rows read stronger; history reads like body copy.
    val drawerItem = navigationItem.copy(fontWeight = FontWeight.Medium)
    val drawerSession = navigationItem.copy(fontFamily = contentFont, fontSize = 14.sp, lineHeight = 23.sp, fontWeight = FontWeight.Normal)
    val drawerSection = caption11.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium)
    val settingsItem = navigationItem.copy(fontSize = 15.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium)

    // Composer / rows / chat
    // User and assistant body text deliberately share the exact same metrics. Role distinction comes
    // from container, alignment and colour rather than an accidental font-size mismatch.
    val chatBody = TextStyle(fontFamily = contentFont, fontWeight = FontWeight.Normal, fontSize = 15.5.sp, lineHeight = 26.sp)
    val bubbleText = chatBody
    val rowText = TextStyle(fontFamily = uiFont, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 23.sp)
    val tabText = TextStyle(fontFamily = uiFont, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp)
    val dockTitle = TextStyle(fontFamily = uiFont, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 22.sp)
    val sectionTitle = TextStyle(fontFamily = uiFont, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 21.sp)
}

fun TextStyle.adjustedReadingWeight(adjustment: Int): TextStyle {
    val level = adjustment.coerceIn(0, 2)
    if (level == 0) return this
    val current = fontWeight ?: FontWeight.Normal
    // Each preference step must change the requested weight even for semibold headings.
    // Cap at 800 to preserve readability and avoid excessively heavy UI text.
    return copy(fontWeight = FontWeight((current.weight + 100 * level).coerceAtMost(800)))
}

@Composable
fun TextStyle.withReadingWeight(): TextStyle =
    adjustedReadingWeight(LocalSyReadingPreferences.current.textWeightAdjustment)

/** All app and canonical controls consume this same role-based Material 3 type scale. */
fun syTypography(textWeightAdjustment: Int): Typography = Typography(
    displayLarge = SyType.largeTitle28.adjustedReadingWeight(textWeightAdjustment),
    displayMedium = SyType.display24.adjustedReadingWeight(textWeightAdjustment),
    displaySmall = SyType.title22.adjustedReadingWeight(textWeightAdjustment),
    headlineLarge = SyType.display24.adjustedReadingWeight(textWeightAdjustment),
    headlineMedium = SyType.title22.adjustedReadingWeight(textWeightAdjustment),
    headlineSmall = SyType.large20.adjustedReadingWeight(textWeightAdjustment),
    titleLarge = SyType.large20.adjustedReadingWeight(textWeightAdjustment),
    titleMedium = SyType.headline17.adjustedReadingWeight(textWeightAdjustment),
    titleSmall = SyType.std14Strong.adjustedReadingWeight(textWeightAdjustment),
    bodyLarge = SyType.base16.copy(fontFamily = SyType.contentFont).adjustedReadingWeight(textWeightAdjustment),
    bodyMedium = SyType.std14.copy(fontFamily = SyType.contentFont).adjustedReadingWeight(textWeightAdjustment),
    bodySmall = SyType.small13.copy(fontFamily = SyType.contentFont).adjustedReadingWeight(textWeightAdjustment),
    labelLarge = SyType.std14Strong.adjustedReadingWeight(textWeightAdjustment),
    labelMedium = SyType.small13Strong.adjustedReadingWeight(textWeightAdjustment),
    labelSmall = SyType.caption11.adjustedReadingWeight(textWeightAdjustment),
)

/** Kept for previews/tests that need the unadjusted baseline. */
val SyTypography = syTypography(0)

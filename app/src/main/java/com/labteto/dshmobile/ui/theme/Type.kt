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
 * Mobile type roles: page titles, body, supporting text and code have independent metrics.
 * Reading preferences adjust weight without replacing the reference glyph families.
 */
object DsType {
    // Use one platform sans-serif family across Latin and CJK glyphs. The previous Latin-only
    // resource family caused device-dependent CJK fallback and uneven apparent weights.
    // Android resolves a script-appropriate glyph face at each requested weight.
    val uiFont = FontFamily.SansSerif
    /** Android's CJK-aware serif family; centralised so a packaged subset can replace it later. */
    val titleFont = FontFamily.Serif
    val codeFont = FontFamily(
        Font(R.font.code_mono_regular, FontWeight.Normal),
        Font(R.font.code_mono_italic, FontWeight.Normal, FontStyle.Italic),
    )

    // Markdown roles
    val mdH1 = TextStyle(fontFamily = uiFont, fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 32.sp)
    val mdH2 = TextStyle(fontFamily = uiFont, fontWeight = FontWeight.Bold, fontSize = 21.sp, lineHeight = 30.sp)
    val mdH3 = TextStyle(fontFamily = uiFont, fontWeight = FontWeight.SemiBold, fontSize = 19.sp, lineHeight = 28.sp)
    val mdH4 = TextStyle(fontFamily = uiFont, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 26.sp)
    val mdBody = TextStyle(fontFamily = uiFont, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 27.sp)
    val mdSmall = TextStyle(fontFamily = uiFont, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 23.sp)
    val mdCode = TextStyle(fontFamily = codeFont, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 22.sp)

    // UI roles
    // Clear Realm navigation hierarchy. General UI is sans-serif; serif stays literary-only.
    val largeTitle28 = TextStyle(fontFamily = uiFont, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 36.sp)
    val title22 = TextStyle(fontFamily = uiFont, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 30.sp)
    val headline17 = TextStyle(fontFamily = uiFont, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 26.sp)
    val display24 = TextStyle(fontFamily = uiFont, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 33.sp)
    val hero26 = TextStyle(fontFamily = uiFont, fontWeight = FontWeight.SemiBold, fontSize = 26.sp, lineHeight = 34.sp)
    val large20 = TextStyle(fontFamily = uiFont, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 28.sp)
    val titleSerif20 = TextStyle(fontFamily = titleFont, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 28.sp)
    val base16 = TextStyle(fontFamily = uiFont, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 25.sp)
    val base16Strong = TextStyle(fontFamily = uiFont, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 25.sp)
    val std14 = TextStyle(fontFamily = uiFont, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 23.sp)
    val std14Strong = TextStyle(fontFamily = uiFont, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 23.sp)
    val small13 = TextStyle(fontFamily = uiFont, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 20.sp)
    val small13Strong = TextStyle(fontFamily = uiFont, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 20.sp)
    val xsmall12 = TextStyle(fontFamily = uiFont, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 18.sp)
    val caption11 = TextStyle(fontFamily = uiFont, fontWeight = FontWeight.Normal, fontSize = 11.sp, lineHeight = 17.sp)
    val caption11Strong = TextStyle(fontFamily = uiFont, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 17.sp)

    // Navigation and settings use larger, stronger roles for comfortable viewing distance.
    val navigationItem = TextStyle(fontFamily = uiFont, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 25.sp)
    val navigationSupporting = TextStyle(fontFamily = uiFont, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 21.sp)
    val navigationSection = TextStyle(fontFamily = uiFont, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 21.sp)
    // Kimi-inspired hierarchy: actionable rows read stronger; history reads like body copy.
    val drawerItem = navigationItem.copy(fontWeight = FontWeight.Medium)
    val drawerSession = navigationItem.copy(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Normal)
    val drawerSection = navigationSection.copy(fontWeight = FontWeight.Medium)
    val settingsItem = navigationItem.copy(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium)

    // Composer / rows / chat
    // User and assistant body text deliberately share the exact same metrics. Role distinction comes
    // from container, alignment and colour rather than an accidental font-size mismatch.
    val chatBody = TextStyle(fontFamily = uiFont, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 26.sp)
    val bubbleText = chatBody
    val rowText = TextStyle(fontFamily = uiFont, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 23.sp)
    val tabText = TextStyle(fontFamily = uiFont, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp)
    val dockTitle = TextStyle(fontFamily = uiFont, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 22.sp)
    val sectionTitle = TextStyle(fontFamily = uiFont, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 20.sp)
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
    adjustedReadingWeight(LocalDsReadingPreferences.current.textWeightAdjustment)

/** Material 3 mapping: sizes follow DsType and weight follows the app-wide reading preference. */
fun dsTypography(textWeightAdjustment: Int): Typography = Typography(
    displayLarge = DsType.largeTitle28.adjustedReadingWeight(textWeightAdjustment),
    displayMedium = DsType.display24.adjustedReadingWeight(textWeightAdjustment),
    displaySmall = DsType.title22.adjustedReadingWeight(textWeightAdjustment),
    headlineLarge = DsType.display24.adjustedReadingWeight(textWeightAdjustment),
    headlineMedium = DsType.title22.adjustedReadingWeight(textWeightAdjustment),
    headlineSmall = DsType.large20.adjustedReadingWeight(textWeightAdjustment),
    titleLarge = DsType.large20.adjustedReadingWeight(textWeightAdjustment),
    titleMedium = DsType.headline17.adjustedReadingWeight(textWeightAdjustment),
    titleSmall = DsType.std14Strong.adjustedReadingWeight(textWeightAdjustment),
    bodyLarge = DsType.base16.adjustedReadingWeight(textWeightAdjustment),
    bodyMedium = DsType.std14.adjustedReadingWeight(textWeightAdjustment),
    bodySmall = DsType.small13.adjustedReadingWeight(textWeightAdjustment),
    labelLarge = DsType.std14Strong.adjustedReadingWeight(textWeightAdjustment),
    labelMedium = DsType.small13Strong.adjustedReadingWeight(textWeightAdjustment),
    labelSmall = DsType.caption11.adjustedReadingWeight(textWeightAdjustment),
)

/** Kept for previews/tests that need the unadjusted baseline. */
val DsTypography = dsTypography(0)

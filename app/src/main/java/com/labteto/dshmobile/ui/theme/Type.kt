package com.labteto.dshmobile.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * DeepSeek Harness type scale (gradient-shadow-text.css):
 * sizes 11..24, system UI stack + mono code stack.
 */
object DsType {
    val uiFont = FontFamily.SansSerif
    /** Android's CJK-aware serif family; centralised so a packaged subset can replace it later. */
    val titleFont = FontFamily.Serif
    val codeFont = FontFamily.Monospace

    // Markdown roles
    val mdH1 = TextStyle(fontFamily = uiFont, fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 34.sp)
    val mdH2 = TextStyle(fontFamily = uiFont, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 32.sp)
    val mdH3 = TextStyle(fontFamily = uiFont, fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 30.sp)
    val mdH4 = TextStyle(fontFamily = uiFont, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 28.sp)
    val mdBody = TextStyle(fontFamily = uiFont, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 28.sp)
    val mdSmall = TextStyle(fontFamily = uiFont, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 24.sp)
    val mdCode = TextStyle(fontFamily = codeFont, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 22.sp)

    // UI roles
    // Clear Realm navigation hierarchy. General UI is sans-serif; serif stays literary-only.
    val largeTitle28 = TextStyle(fontFamily = uiFont, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 34.sp)
    val title22 = TextStyle(fontFamily = uiFont, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 30.sp)
    val headline17 = TextStyle(fontFamily = uiFont, fontWeight = FontWeight.Medium, fontSize = 17.sp, lineHeight = 24.sp)
    val display24 = TextStyle(fontFamily = uiFont, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 32.sp)
    val hero26 = TextStyle(fontFamily = uiFont, fontWeight = FontWeight.SemiBold, fontSize = 26.sp, lineHeight = 32.sp)
    val large20 = TextStyle(fontFamily = uiFont, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 28.sp)
    val titleSerif20 = TextStyle(fontFamily = titleFont, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 28.sp)
    val base16 = TextStyle(fontFamily = uiFont, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp)
    val base16Strong = TextStyle(fontFamily = uiFont, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 24.sp)
    val std14 = TextStyle(fontFamily = uiFont, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp)
    val std14Strong = TextStyle(fontFamily = uiFont, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 24.sp)
    val small13 = TextStyle(fontFamily = uiFont, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 21.sp)
    val small13Strong = TextStyle(fontFamily = uiFont, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 21.sp)
    val xsmall12 = TextStyle(fontFamily = uiFont, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 19.sp)
    val caption11 = TextStyle(fontFamily = uiFont, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp)
    val caption11Strong = TextStyle(fontFamily = uiFont, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp)

    // Composer / rows / chat
    // User and assistant body text deliberately share the exact same metrics. Role distinction comes
    // from container, alignment and colour rather than an accidental font-size mismatch.
    val chatBody = TextStyle(fontFamily = uiFont, fontWeight = FontWeight.Normal, fontSize = 17.sp, lineHeight = 26.sp)
    val bubbleText = chatBody
    val rowText = TextStyle(fontFamily = uiFont, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 21.sp)
    val tabText = TextStyle(fontFamily = uiFont, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 18.sp)
    val dockTitle = TextStyle(fontFamily = uiFont, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 24.sp)
    val sectionTitle = TextStyle(fontFamily = uiFont, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 19.sp)
    val statsText = TextStyle(fontFamily = uiFont, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 20.sp)
}

fun TextStyle.adjustedReadingWeight(adjustment: Int): TextStyle {
    val level = adjustment.coerceIn(0, 2)
    if (level <= 0) return this
    val current = fontWeight ?: FontWeight.Normal
    val target = when {
        current >= FontWeight.Bold -> current
        level >= 2 && current >= FontWeight.Medium -> FontWeight.Bold
        level >= 2 -> FontWeight.SemiBold
        current >= FontWeight.Medium -> FontWeight.SemiBold
        else -> FontWeight.Medium
    }
    return copy(fontWeight = target)
}

@Composable
fun TextStyle.withReadingWeight(): TextStyle =
    adjustedReadingWeight(LocalDsReadingPreferences.current.textWeightAdjustment)

/** Material 3 mapping: sizes follow DsType and weight follows the app-wide reading preference. */
fun dsTypography(textWeightAdjustment: Int): Typography = Typography(
    displayLarge = DsType.largeTitle28.adjustedReadingWeight(textWeightAdjustment),
    headlineMedium = DsType.title22.adjustedReadingWeight(textWeightAdjustment),
    titleMedium = DsType.headline17.adjustedReadingWeight(textWeightAdjustment),
    bodyLarge = DsType.base16.adjustedReadingWeight(textWeightAdjustment),
    bodyMedium = DsType.std14.adjustedReadingWeight(textWeightAdjustment),
    bodySmall = DsType.small13.adjustedReadingWeight(textWeightAdjustment),
    labelLarge = DsType.std14Strong.adjustedReadingWeight(textWeightAdjustment),
    labelMedium = DsType.small13Strong.adjustedReadingWeight(textWeightAdjustment),
    labelSmall = DsType.caption11.adjustedReadingWeight(textWeightAdjustment),
)

/** Kept for previews/tests that need the unadjusted baseline. */
val DsTypography = dsTypography(0)

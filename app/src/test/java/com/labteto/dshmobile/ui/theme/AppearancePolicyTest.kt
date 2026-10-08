package com.labteto.dshmobile.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppearancePolicyTest {
    @Test
    fun chatUserAndAssistantBodyShareTheSameMetrics() {
        assertEquals(DsType.chatBody.fontSize, DsType.bubbleText.fontSize)
        assertEquals(DsType.chatBody.lineHeight, DsType.bubbleText.lineHeight)
    }

    @Test
    fun readingWeightAdjustmentPreservesSemanticHierarchy() {
        val regular = TextStyle(fontWeight = FontWeight.Normal)
        val medium = TextStyle(fontWeight = FontWeight.Medium)
        val bold = TextStyle(fontWeight = FontWeight.Bold)

        assertEquals(FontWeight.Normal, regular.adjustedReadingWeight(0).fontWeight)
        assertEquals(FontWeight.Medium, regular.adjustedReadingWeight(1).fontWeight)
        assertEquals(FontWeight.SemiBold, regular.adjustedReadingWeight(2).fontWeight)
        assertEquals(FontWeight.SemiBold, medium.adjustedReadingWeight(1).fontWeight)
        assertEquals(FontWeight.Bold, medium.adjustedReadingWeight(2).fontWeight)
        assertEquals(FontWeight.Bold, bold.adjustedReadingWeight(2).fontWeight)
    }

    @Test
    fun navigationTypographyUsesReadableSizesAndWeights() {
        assertTrue(DsType.navigationItem.fontSize > DsType.std14.fontSize)
        assertTrue(DsType.navigationSupporting.fontSize > DsType.small13.fontSize)
        assertTrue(DsType.navigationSection.fontSize > DsType.caption11.fontSize)
        assertTrue(DsType.navigationItem.fontWeight!! >= FontWeight.SemiBold)
        assertTrue(DsType.mdBody.fontWeight!! >= FontWeight.Medium)
        assertTrue(DsType.chatBody.fontWeight!! >= FontWeight.Medium)
    }

    @Test
    fun wallpaperTransparencyPreferenceHasVisibleMonotonicRange() {
        val opaque = resolveWallpaperAlpha(
            adaptiveAlpha = 0.58f,
            minAlpha = 0.44f,
            maxAlpha = 0.72f,
            transparency = 0f,
        )
        val balanced = resolveWallpaperAlpha(
            adaptiveAlpha = 0.58f,
            minAlpha = 0.44f,
            maxAlpha = 0.72f,
            transparency = 0.5f,
        )
        val airy = resolveWallpaperAlpha(
            adaptiveAlpha = 0.58f,
            minAlpha = 0.44f,
            maxAlpha = 0.72f,
            transparency = 1f,
        )

        assertTrue(opaque > balanced)
        assertTrue(balanced > airy)
        assertTrue(opaque - airy >= 0.30f)
        assertTrue(airy >= 0.14f)
        assertTrue(opaque <= 0.98f)
    }
}

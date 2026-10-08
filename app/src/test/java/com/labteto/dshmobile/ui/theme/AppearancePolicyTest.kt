package com.labteto.dshmobile.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
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
        val semibold = TextStyle(fontWeight = FontWeight.SemiBold)
        val bold = TextStyle(fontWeight = FontWeight.Bold)

        assertEquals(FontWeight.Normal, regular.adjustedReadingWeight(0).fontWeight)
        assertEquals(FontWeight.Medium, regular.adjustedReadingWeight(1).fontWeight)
        assertEquals(FontWeight.SemiBold, regular.adjustedReadingWeight(2).fontWeight)
        assertEquals(FontWeight.SemiBold, medium.adjustedReadingWeight(1).fontWeight)
        assertEquals(FontWeight.Bold, medium.adjustedReadingWeight(2).fontWeight)
        assertEquals(FontWeight.Bold, semibold.adjustedReadingWeight(1).fontWeight)
        assertEquals(FontWeight.ExtraBold, semibold.adjustedReadingWeight(2).fontWeight)
        assertEquals(FontWeight.ExtraBold, bold.adjustedReadingWeight(1).fontWeight)
        assertEquals(FontWeight.ExtraBold, bold.adjustedReadingWeight(2).fontWeight)
    }

    @Test
    fun navigationTypographyUsesReadableSizesAndWeights() {
        assertTrue(DsType.navigationItem.fontSize > DsType.std14.fontSize)
        assertTrue(DsType.navigationSupporting.fontSize > DsType.small13.fontSize)
        assertTrue(DsType.navigationSection.fontSize > DsType.caption11.fontSize)
        assertTrue(DsType.navigationItem.fontWeight!! >= FontWeight.SemiBold)
        assertEquals(FontWeight.Normal, DsType.mdBody.fontWeight)
        assertEquals(FontWeight.Normal, DsType.chatBody.fontWeight)
        assertEquals(FontFamily.SansSerif, DsType.uiFont)
        assertTrue(DsType.drawerItem.fontSize > DsType.drawerSession.fontSize)
        assertTrue(DsType.drawerSession.fontSize > DsType.drawerSection.fontSize)
        assertTrue(DsType.drawerItem.fontWeight!! > DsType.drawerSession.fontWeight!!)
        assertTrue(DsType.base16Strong.fontWeight!! > DsType.base16.fontWeight!!)
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

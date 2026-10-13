package com.labteto.dshmobile.ui.theme

import androidx.compose.ui.unit.dp
import com.shenyan.designsystem.foundation.SyMetric

/**
 * Legacy semantic names that now read the V6 SyMetric source of truth.
 * Remove this adapter only after active business consumers migrate.
 * Spacing tokens for consistent layout rhythm throughout the app.
 * Use these tokens instead of hardcoded dp values to maintain visual consistency.
 */
object DsSpacing {
    /** 2dp - Minimal spacing between tightly related elements (e.g., icon and label) */
    val tiny = SyMetric.gapXs
    
    /** 6dp - Extra small spacing */
    val xsmall = SyMetric.gapSm
    
    /** 10dp - Small spacing for related items within a component */
    val small = SyMetric.gapMd
    

    
    /** 14dp - Medium spacing between component elements */
    val medium = SyMetric.gapLg
    

    
    /** 22dp - Comfortable spacing for screen padding and section content */
    val comfortable = SyMetric.gapSection
    
    /** 22dp - Large spacing between major UI sections */
    val large = SyMetric.gapSection
    
    /** 34dp - Extra large spacing for distinct content blocks */
    val xlarge = SyMetric.gapPage
    
    /** 54dp - Major section spacing for clear visual separation */
    val xxlarge = SyMetric.gapHero
    
    /** 48dp - Minimum touch target size per Material Design guidelines */
    val touchTarget = SyMetric.touch
}

/** Semantic layout metrics shared by chat and work surfaces. */
object DsMetrics {
    const val drawerWidthFraction = 0.85f
    val screenHorizontal = 22.dp

    val topBarHeight = 56.dp

}

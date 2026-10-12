package com.labteto.dshmobile.ui.theme

import androidx.compose.ui.unit.dp

/**
 * Spacing tokens for consistent layout rhythm throughout the app.
 * Use these tokens instead of hardcoded dp values to maintain visual consistency.
 */
object DsSpacing {
    /** 2dp - Minimal spacing between tightly related elements (e.g., icon and label) */
    val tiny = 2.dp
    
    /** 6dp - Extra small spacing */
    val xsmall = 6.dp
    
    /** 10dp - Small spacing for related items within a component */
    val small = 10.dp
    

    
    /** 14dp - Medium spacing between component elements */
    val medium = 14.dp
    

    
    /** 22dp - Comfortable spacing for screen padding and section content */
    val comfortable = 22.dp
    
    /** 22dp - Large spacing between major UI sections */
    val large = 22.dp
    
    /** 34dp - Extra large spacing for distinct content blocks */
    val xlarge = 34.dp
    
    /** 54dp - Major section spacing for clear visual separation */
    val xxlarge = 54.dp
    
    /** 48dp - Minimum touch target size per Material Design guidelines */
    val touchTarget = 48.dp
}

/** Semantic layout metrics shared by chat and work surfaces. */
object DsMetrics {
    const val drawerWidthFraction = 0.85f
    val screenHorizontal = 22.dp

    val topBarHeight = 56.dp

}

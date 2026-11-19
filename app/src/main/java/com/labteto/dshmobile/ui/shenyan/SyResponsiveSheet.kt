package com.labteto.dshmobile.ui.shenyan

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import com.shenyan.designsystem.components.SyMaterialSurface
import com.shenyan.designsystem.components.SySheetScaffold
import com.shenyan.designsystem.components.SySnapBottomSheet
import com.shenyan.designsystem.components.SySceneBackgroundSurface
import com.shenyan.designsystem.components.SyMaterialTier
import com.shenyan.designsystem.foundation.SyMotion
import com.shenyan.designsystem.foundation.LocalSyMotionTempo
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * V5: mobile bottom sheet, tablet/desktop right-side panel.
 * Controlled callbacks only: approvals stay caller-owned, no implicit acceptance on dismiss.
 * Ordinary sheets share the aligned scene by default; risk/destructive confirmations opt into an explicit L4 overlay.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyResponsiveSheet(
    title: String?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    scrollable: Boolean = false,
    scrollBodyHeightFraction: Float = .67f,
    dismissEnabled: Boolean = true,
    showDragHandle: Boolean = true,
    floating: Boolean = false,
    surfaceTier: SyMaterialTier = SyMaterialTier.L4_OVERLAY,
    seamlessBackground: Boolean = true,
    dimScrimWhenSeamless: Boolean = true,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
    allowPartialExpansion: Boolean = false,
    useSnapStops: Boolean = false,
    bodyScrollState: androidx.compose.foundation.ScrollState = rememberScrollState(),
    bodyViewport: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val p = LocalSyPalette.current
    val cfg = LocalConfiguration.current
    val wide = cfg.screenWidthDp >= 600
    if (!wide && useSnapStops) {
        SySnapBottomSheet(
            title = requireNotNull(title), onDismiss = onDismiss,
            dismissEnabled = dismissEnabled, bodyScrollState = bodyScrollState,
            bodyViewport = bodyViewport, content = content,
        )
        return
    }
    val maxBodyHeight = cfg.screenHeightDp.dp * scrollBodyHeightFraction
    val dismissal = rememberUpdatedState(dismissEnabled)
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = !allowPartialExpansion,
        confirmValueChange = { next -> dismissal.value || next != SheetValue.Hidden },
    )
    val reducedMotion = LocalSyReducedMotion.current
    val tempo = LocalSyMotionTempo.current
    @Composable fun SheetBody() {
        SySheetScaffold(
            title = title,
            subtitle = subtitle,
            onDismiss = if (dismissEnabled) onDismiss else null,
            trailing = trailing,
            showDragHandle = !wide && showDragHandle,
            scrollable = scrollable,
            maxBodyHeight = maxBodyHeight,
            footer = footer,
            bodyScrollState = bodyScrollState, bodyViewport = bodyViewport,
            content = content,
        )
    }
    if (wide) {
        Dialog(
            onDismissRequest = { if (dismissal.value) onDismiss() },
            properties = DialogProperties(
                dismissOnBackPress = dismissal.value,
                dismissOnClickOutside = dismissal.value,
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
            ),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
                AnimatedVisibility(
                    visible = true,
                    enter = slideInHorizontally(tween(
                        SyMotion.duration(SyDurations.sheet, reducedMotion, tempo),
                        easing = SyMotion.ease
                    )) { fullWidth -> fullWidth },
                ) {
                    val panelModifier = modifier.width(420.dp).fillMaxHeight()
                        .padding(if (floating) 18.dp else 10.dp)
                    if (seamlessBackground) {
                        // Paint the root scene at the same screen coordinates, not a white plate
                        // and not transparency that reveals chat bubbles underneath the menu.
                        SySceneBackgroundSurface(panelModifier) { SheetBody() }
                    } else {
                        SyMaterialSurface(
                            modifier = panelModifier,
                            shape = RoundedCornerShape(SyDimensions.cornerOverlay),
                            tier = surfaceTier,
                        ) { SheetBody() }
                    }
                }
            }
        }
    } else {
        ModalBottomSheet(
            onDismissRequest = { if (dismissal.value) onDismiss() },
            sheetState = sheetState,
            modifier = if (floating && !seamlessBackground) modifier.padding(horizontal = 6.dp) else modifier,
            shape = RoundedCornerShape(SyDimensions.cornerOverlay),
            containerColor = Color.Transparent,
            scrimColor = if (seamlessBackground && !dimScrimWhenSeamless) Color.Transparent else p.scrim,
            tonalElevation = 0.dp,
            dragHandle = null,
            contentWindowInsets = { WindowInsets.navigationBars },
        ) {
            if (seamlessBackground) {
                SySceneBackgroundSurface(Modifier.fillMaxWidth()) { SheetBody() }
            } else {
                SyMaterialSurface(tier = surfaceTier,
                    shape = RoundedCornerShape(SyDimensions.cornerOverlay)) { SheetBody() }
            }
        }
    }
}

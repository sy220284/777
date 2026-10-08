package com.labteto.dshmobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.BackgroundRegion
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.WallpaperSurfaceLevel
import com.labteto.dshmobile.ui.theme.wallpaperSurface
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.withReadingWeight

/**
 * The app's sheet surface, themed to the harness tokens.
 *
 * Sheets rather than dialogs for pickers: they arrive from the thumb's end of the screen, size
 * themselves to their content, and let a long list scroll without fighting a fixed-height plate.
 * [trailing] holds an optional action aligned with the title.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DsBottomSheet(
    title: String?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    scrollable: Boolean = false,
    dismissEnabled: Boolean = true,
    showDragHandle: Boolean = true,
    floating: Boolean = false,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = DsTheme.colors
    val maxBodyHeight = LocalConfiguration.current.screenHeightDp.dp * 0.67f
    val dismissal = rememberUpdatedState(dismissEnabled)
    val state = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { next -> dismissal.value || next != SheetValue.Hidden },
    )
    ModalBottomSheet(
        onDismissRequest = { if (dismissal.value) onDismiss() },
        sheetState = state,
        modifier = if (floating) modifier.padding(horizontal = 6.dp, vertical = 6.dp) else modifier,
        shape = if (floating) DsShapes.floatingSheet else DsShapes.sheet,
        containerColor = colors.wallpaperSurface(WallpaperSurfaceLevel.SHEET, BackgroundRegion.BOTTOM),
        scrimColor = colors.overlayMask,
        dragHandle = null,
        contentWindowInsets = { WindowInsets.navigationBars },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(horizontal = DsSpacing.comfortable, vertical = DsSpacing.medium),
            verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
        ) {
            // A short grabber stands in for the platform drag handle so the sheet still reads as
            // draggable without the default's heavy vertical padding.
            if (showDragHandle) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                Spacer(
                    Modifier
                        .fillMaxWidth(0.12f)
                        .height(4.dp)
                        .clip(DsShapes.pillFull)
                        .background(colors.borderL3),
                )
            }
            if (title != null) {
                Row(
                    Modifier.fillMaxWidth().padding(top = DsSpacing.xsmall),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(title, style = DsType.headline17.withReadingWeight(), color = colors.labelPrimary)
                        if (subtitle != null) {
                            Text(subtitle, style = DsType.caption11.withReadingWeight(), color = colors.labelTertiary)
                        }
                    }
                    trailing?.invoke()
                }
            }
            if (scrollable) {
                Column(
                    modifier = Modifier.fillMaxWidth().heightIn(max = maxBodyHeight)
                        .verticalScroll(rememberScrollState())
                        .testTag("bottomSheetScrollBody"),
                    verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
                    content = content,
                )
            } else {
                content()
            }
            if (footer != null) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(top = DsSpacing.small),
                    verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
                    content = footer,
                )
            }
            Spacer(Modifier.height(DsSpacing.small))
        }
    }
}

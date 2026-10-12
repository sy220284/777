package com.labteto.dshmobile.ui.components

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.labteto.dshmobile.ui.shenyan.SyResponsiveSheet

/** Deprecated compatibility surface: all visuals now render through V5 SyResponsiveSheet. */
@Deprecated("V5: replace call sites with SyResponsiveSheet")
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
) = SyResponsiveSheet(
    title = title,
    onDismiss = onDismiss,
    modifier = modifier,
    subtitle = subtitle,
    trailing = trailing,
    scrollable = scrollable,
    dismissEnabled = dismissEnabled,
    showDragHandle = showDragHandle,
    floating = floating,
    footer = footer,
    content = content,
)

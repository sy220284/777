package com.labteto.dshmobile.ui.components
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
/** V5-only visual route while legacy call sites are removed. */
@Deprecated("V5: use SyEmptyState")
@Composable
fun DsPageEmptyState(icon: ImageVector, title: String, body: String, modifier: Modifier = Modifier,
    actionText: String? = null, onAction: (() -> Unit)? = null) =
    com.shenyan.designsystem.components.SyEmptyState(icon, title, body, modifier, actionText, onAction)

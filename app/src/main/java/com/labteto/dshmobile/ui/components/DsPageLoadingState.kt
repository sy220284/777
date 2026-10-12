package com.labteto.dshmobile.ui.components
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
@Deprecated("V5: use SyLoadingState")
@Composable
fun DsPageLoadingState(icon: ImageVector, label: String, modifier: Modifier = Modifier) =
    com.shenyan.designsystem.components.SyLoadingState(icon, label, modifier)

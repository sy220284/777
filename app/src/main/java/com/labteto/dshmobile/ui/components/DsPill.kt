package com.labteto.dshmobile.ui.components
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
@Deprecated("V5: use SyPill")
@Composable
fun DsPill(text: String, modifier: Modifier = Modifier,
    selected: Boolean = false, warn: Boolean = false,
    onClick: (() -> Unit)? = null) =
    com.shenyan.designsystem.components.SyPill(text, modifier, selected, warn, onClick)

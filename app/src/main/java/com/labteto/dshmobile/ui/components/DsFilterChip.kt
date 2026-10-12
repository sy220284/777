package com.labteto.dshmobile.ui.components
import androidx.compose.runtime.Composable
@Deprecated("V5: use SyFilterChip")
@Composable
fun DsFilterChip(text: String, selected: Boolean, onClick: () -> Unit) =
    com.shenyan.designsystem.components.SyFilterChip(text, selected, onClick)

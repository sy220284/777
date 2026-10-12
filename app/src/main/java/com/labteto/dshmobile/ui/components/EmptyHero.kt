package com.labteto.dshmobile.ui.components

import androidx.compose.runtime.Composable

@Composable
fun EmptyHero(headline: String, subtitle: String?, chips: List<String> = emptyList(), onChipClick: (String) -> Unit = {}) =
    com.shenyan.designsystem.components.SyEmptyHero(headline, subtitle, chips, onChipClick)

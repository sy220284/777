package com.labteto.dshmobile.ui.screens.local

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labteto.dshmobile.local.presentation.LocalWorkUiState

/** Keeps hot Work status updates inside the smallest UI restart scope that renders them. */
@Composable
internal fun LocalWorkStateContent(
    viewModel: LocalHarnessViewModel,
    content: @Composable (LocalWorkUiState) -> Unit,
) {
    val state by viewModel.workState.collectAsStateWithLifecycle()
    content(state)
}

package com.labteto.dshmobile.ui.screens.local
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labteto.dshmobile.local.LocalHarnessState

/**
 * Aggregate runtime subscription kept in a narrow restart scope. Prefer projected state elsewhere;
 * this is only for surfaces that genuinely render many runtime domains together.
 */
@Composable
internal fun LocalHarnessStateContent(
    viewModel: LocalHarnessViewModel,
    content: @Composable (LocalHarnessState) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    content(state)
}

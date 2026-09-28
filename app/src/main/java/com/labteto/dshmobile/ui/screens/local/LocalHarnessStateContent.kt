package com.labteto.dshmobile.ui.screens.local

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.presentation.LocalConversationSurfaceState

/** Keeps Chat and Work on separate invalidation paths while they share the same conversation substrate. */
@Composable
internal fun LocalConversationStateContent(
    viewModel: LocalHarnessViewModel,
    usageMode: LocalUsageMode,
    content: @Composable (LocalConversationSurfaceState) -> Unit,
) {
    val source = when (usageMode) {
        LocalUsageMode.CHAT -> viewModel.chatSurfaceState
        LocalUsageMode.WORK -> viewModel.workSurfaceState
    }
    val state by source.collectAsStateWithLifecycle()
    content(state)
}

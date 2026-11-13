package com.labteto.dshmobile.local.presentation

import com.labteto.dshmobile.local.LocalHarnessState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

internal fun StateFlow<LocalHarnessState>.projectChatSurfaceState(
    scope: CoroutineScope,
): StateFlow<LocalConversationSurfaceState> =
    map { it.toChatSurfaceUiState() }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), value.toChatSurfaceUiState())

internal fun StateFlow<LocalHarnessState>.projectWorkSurfaceState(
    scope: CoroutineScope,
): StateFlow<LocalConversationSurfaceState> =
    map { it.toWorkSurfaceUiState() }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), value.toWorkSurfaceUiState())

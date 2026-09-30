package com.labteto.dshmobile.local.presentation

import com.labteto.dshmobile.local.LocalHarnessState
import com.labteto.dshmobile.local.LocalModelProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn

internal fun StateFlow<LocalHarnessState>.projectChatSurfaceState(
    scope: CoroutineScope,
    activeProfile: StateFlow<LocalModelProfile?>,
): StateFlow<LocalConversationSurfaceState> =
    combine(activeProfile) { state, profile -> state.toChatSurfaceUiState(profile?.id) }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), value.toChatSurfaceUiState(activeProfile.value?.id))

internal fun StateFlow<LocalHarnessState>.projectWorkSurfaceState(
    scope: CoroutineScope,
    activeProfile: StateFlow<LocalModelProfile?>,
): StateFlow<LocalConversationSurfaceState> =
    combine(activeProfile) { state, profile -> state.toWorkSurfaceUiState(profile?.id) }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), value.toWorkSurfaceUiState(activeProfile.value?.id))

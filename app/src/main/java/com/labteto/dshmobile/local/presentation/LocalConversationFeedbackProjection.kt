package com.labteto.dshmobile.local.presentation

import com.labteto.dshmobile.local.LocalHarnessState
import com.labteto.dshmobile.local.send.LocalSendFeedbackState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn

private fun LocalConversationSurfaceState.withSendFeedback(
    feedback: LocalSendFeedbackState,
): LocalConversationSurfaceState =
    if (feedback.sessionId == sessionId) {
        copy(sendRejectReason = feedback.rejectReason, sendRejectLimit = feedback.rejectLimit)
    } else {
        this
    }

internal fun StateFlow<LocalHarnessState>.projectChatSurfaceState(
    scope: CoroutineScope,
    feedback: StateFlow<LocalSendFeedbackState>,
): StateFlow<LocalConversationSurfaceState> =
    combine(this, feedback) { state, send -> state.toChatSurfaceUiState().withSendFeedback(send) }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), value.toChatSurfaceUiState().withSendFeedback(feedback.value))

internal fun StateFlow<LocalHarnessState>.projectWorkSurfaceState(
    scope: CoroutineScope,
    feedback: StateFlow<LocalSendFeedbackState>,
): StateFlow<LocalConversationSurfaceState> =
    combine(this, feedback) { state, send -> state.toWorkSurfaceUiState().withSendFeedback(send) }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), value.toWorkSurfaceUiState().withSendFeedback(feedback.value))

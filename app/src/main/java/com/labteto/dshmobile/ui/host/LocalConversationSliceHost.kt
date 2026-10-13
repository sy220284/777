package com.labteto.dshmobile.ui.host

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.ui.screens.local.LocalHarnessViewModel
import com.labteto.dshmobile.uipack.next.NextConversationUiPack

/** Debug-only explicit slice entry. It cannot be selected as a complete application UI pack. */
@Composable
internal fun LocalConversationSliceHost(
    requestedSessionId: String?,
    onSessionRequestConsumed: () -> Unit,
    onClose: () -> Unit,
    viewModel: LocalHarnessViewModel = hiltViewModel(),
) {
    val shell by viewModel.shellState.collectAsStateWithLifecycle()
    LaunchedEffect(requestedSessionId, shell.loading) {
        if (!shell.loading && requestedSessionId != null) {
            if (viewModel.switchSession(requestedSessionId)) onSessionRequestConsumed()
        }
    }
    BackHandler(onBack = onClose)
    NextConversationUiPack.Render(
        chat = viewModel.chatUiPort,
        work = viewModel.workConversationUiPort,
        workMode = shell.usageMode == LocalUsageMode.WORK,
        onClose = onClose,
    )
}

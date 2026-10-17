package com.labteto.dshmobile.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.labteto.dshmobile.ui.host.LocalEncryptedUiDraftStore
import com.labteto.dshmobile.ui.host.LocalProductionUiEntry
import com.labteto.dshmobile.uihost.LocalUiPrivateTextCipher
import com.labteto.dshmobile.uihost.ProvideUiHostSystemOperationOwner

/** One Feature Port composition and one Host. Retired Shell callbacks have no application entry. */
@Composable
fun AppRoot(
    viewModel: AppViewModel = hiltViewModel(),
    requestedLocalSessionId: String? = null,
    onLocalSessionRequestConsumed: () -> Unit = {},
    requestedExternalSceneKey: String? = null,
    onExternalSceneRequestConsumed: () -> Unit = {},
) {
    val context = LocalContext.current
    val privateText = remember(context) { LocalEncryptedUiDraftStore(context) }
    CompositionLocalProvider(LocalUiPrivateTextCipher provides privateText) {
        ProvideUiHostSystemOperationOwner {
            LocalProductionUiEntry(
                appViewModel = viewModel,
                requestedLocalSessionId = requestedLocalSessionId,
                onLocalSessionRequestConsumed = onLocalSessionRequestConsumed,
                requestedExternalSceneKey = requestedExternalSceneKey,
                onExternalSceneRequestConsumed = onExternalSceneRequestConsumed,
            )
        }
    }
}

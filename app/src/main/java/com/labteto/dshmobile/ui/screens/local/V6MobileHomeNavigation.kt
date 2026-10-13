package com.labteto.dshmobile.ui.screens.local

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.LocalUsageMode

/**
 * V6 mobile design 01: three destinations project the EXISTING product screens.
 *
 * Only the current conversation is selected here. Sessions is the real drawer,
 * Persona is the existing persona detail/gallery route, and Files is the real
 * workspace. No parallel navigation state or placeholder pages are introduced.
 */
internal enum class V6MobileHomeDestination {
    SESSIONS,
    CONVERSATION,
    PERSONA,
    WORKSPACE,
}

internal fun v6MobileHomeDestinations(mode: LocalUsageMode): List<V6MobileHomeDestination> =
    when (mode) {
        LocalUsageMode.CHAT -> listOf(
            V6MobileHomeDestination.SESSIONS,
            V6MobileHomeDestination.CONVERSATION,
            V6MobileHomeDestination.PERSONA,
        )
        LocalUsageMode.WORK -> listOf(
            V6MobileHomeDestination.SESSIONS,
            V6MobileHomeDestination.CONVERSATION,
            V6MobileHomeDestination.WORKSPACE,
        )
    }

/** Keeps the 393/412dp design on phones without displaying a desktop control row. */
internal fun v6MobileHomeNavigationVisible(widthDp: Int, hasDrawer: Boolean): Boolean =
    widthDp < 600 && hasDrawer

@Composable
internal fun V6MobileHomeNavigation(
    mode: LocalUsageMode,
    onSessions: () -> Unit,
    onPersona: () -> Unit,
    onWorkspace: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tabs = v6MobileHomeDestinations(mode)
    FluidSectionNavigation(
        labels = tabs.map { destination ->
            stringResource(
                when (destination) {
                    V6MobileHomeDestination.SESSIONS -> R.string.sy_v6_mobile_sessions
                    V6MobileHomeDestination.CONVERSATION ->
                        if (mode == LocalUsageMode.CHAT) R.string.sy_v6_mobile_chat
                        else R.string.sy_v6_mobile_work
                    V6MobileHomeDestination.PERSONA -> R.string.sy_v6_mobile_persona
                    V6MobileHomeDestination.WORKSPACE -> R.string.sy_v6_mobile_workspace
                },
            )
        },
        selected = 1,
        onSelect = { index ->
            when (tabs[index]) {
                V6MobileHomeDestination.SESSIONS -> onSessions()
                V6MobileHomeDestination.CONVERSATION -> Unit
                V6MobileHomeDestination.PERSONA -> onPersona()
                V6MobileHomeDestination.WORKSPACE -> onWorkspace()
            }
        },
        modifier = modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp),
        tag = "v6MobileHomeNavigation",
    )
}

package com.labteto.dshmobile.ui.screens.local

import com.labteto.dshmobile.local.model.LocalModelProfile
import com.labteto.dshmobile.local.model.LocalModelPresets
import com.labteto.dshmobile.local.model.LocalModelTemperatureRange

internal data class LocalConversationTemperatureUi(
    val range: LocalModelTemperatureRange? = null,
    val tuningAvailable: Boolean = false,
)

/** Resolve optional account metadata outside Compose's inline expression tracking. */
internal fun localConversationTemperatureUi(profile: LocalModelProfile?, fast: Boolean): LocalConversationTemperatureUi {
    val selected = profile ?: return LocalConversationTemperatureUi()
    val range = LocalModelPresets.chatTemperatureRangeFor(selected.model, selected.baseUrl)
    val capability = LocalModelPresets.runtimeCapabilitiesFor(
        selected.model, selected.baseUrl, selected.protocol, selected.authKind,
    )
    return LocalConversationTemperatureUi(range,
        capability.temperature && range != null && (!range.requiresDisabledThinking || fast))
}
